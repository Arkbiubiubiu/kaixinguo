// streamsrv：番茄/红果取流服务。
//
// 背景：上游取流网关从 2026-10-30 15:32 起对无签名请求全量风控（HTTP 200 空包），
// 旧的 Python 直连（固定设备身份、无 X-Argus/X-Gorgon/X-Ladon）已死。
// 本服务复用 openhongguo 的纯 Go 签名实现（与官方客户端同款签名头）
// 和匿名设备注册，走新版 /novel/player/video_model/v1/。
//
// 对外协议与 playinfo.py 完全同构，App 端零改动：
//
//	POST /play  {"vid":"...", "want":"1080p"|"all"}
//	-> want=all  : {"list":[{definition,height,url,backup,spade_a,size,codec,width}]}
//	-> want=NNNp : 单个档位对象（≤NNNp 里的最高档，没有就最高档）
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/beilunyang/openhongguo/adapters/filestore"
	xht "github.com/beilunyang/openhongguo/adapters/httptransport"
	"github.com/beilunyang/openhongguo/sdk"
)

type tier struct {
	Definition string `json:"definition,omitempty"`
	Height     int    `json:"height"`
	URL        string `json:"url"`
	Backup     string `json:"backup,omitempty"`
	SpadeA     string `json:"spade_a,omitempty"`
	Size       int64  `json:"size,omitempty"`
	Codec      string `json:"codec,omitempty"`
	Width      int    `json:"width,omitempty"`
}

var (
	cacheMu sync.Mutex
	cache   = map[string]cacheEntry{}
)

const cacheTTL = 25 * time.Minute

// cacheMax 给缓存加个上限：长期跑下来（几百部剧 × 每部几十集）
// 不设限就会一直涨。满了清掉一半，丢了的再取一次也就一次请求。
const cacheMax = 400

type cacheEntry struct {
	until time.Time
	tiers []tier
}

var (
	poolMu    sync.Mutex
	curClient *sdk.Client
	// profPath 是当前这台匿名设备档案的落盘路径。作废时按它删，
	// 不再靠通配符去猜目录（见 dropClient）。
	profPath string
)

// newClient 建一个匿名客户端：档案文件每次都用新的名字，
// 这个身份被风控作废时把文件删掉，重建即重新注册一台新设备。
func newClient() (*sdk.Client, error) {
	profile := filepath.Join(os.TempDir(), fmt.Sprintf("ohg-profile-%d.json", time.Now().UnixNano()))
	if v := os.Getenv("PROFILE_DIR"); v != "" {
		profile = filepath.Join(v, fmt.Sprintf("ohg-profile-%d.json", time.Now().UnixNano()))
	}
	profPath = profile
	return sdk.NewClient(sdk.Options{
		Transport:     xht.New(),
		ProfileStore:  filestore.New(profile),
		RetryAttempts: 3,
	})
}

// dropClient 作废当前身份：删掉它的设备档案，下次 fetch 会重新注册一台新设备。
//
// 只删「这一次建的那一份」，不做目录通配。以前这里是 glob(os.TempDir())，
// 但设了 PROFILE_DIR 之后档案实际写在 /var/lib/hgtv-sync/ohg-pool，
// 于是每次都删空气、真档案一个都没删掉（实测已堆积 1300+ 个文件）。
// 通配还有个隐患：万一哪天真档案也落在那个目录，就会连正在用的身份一起删掉。
func dropClient() {
	curClient = nil
	if profPath != "" {
		_ = os.Remove(profPath)
		profPath = ""
	}
}

// fetch 拉一集的全部档位。缓存 25 分钟（流地址本身带 x-expires）。
//
// 上游风控模型（2026-10-30 实测）：一个匿名设备身份**首次请求放行，
// 之后的请求一律 HTTP 200 + 空包**（ErrAnonymousLimited）。所以这里的策略是
// 「用废即弃」：一旦空包，立刻作废当前身份、重新注册一台新设备再试，
// 最多 6 次。全程持锁串行 —— 并发请求会白白浪费身份。
func fetch(vid string) ([]tier, error) {
	poolMu.Lock()
	defer poolMu.Unlock()

	cacheMu.Lock()
	if e, ok := cache[vid]; ok && time.Now().Before(e.until) {
		cacheMu.Unlock()
		return e.tiers, nil
	}
	cacheMu.Unlock()

	var tiers []tier
	var err error
	for attempt := 0; attempt < 6; attempt++ {
		if curClient == nil {
			curClient, err = newClient()
			if err != nil {
				return nil, err
			}
			log.Printf("registered fresh anonymous device")
		}
		tiers, err = fetchOnce(curClient, vid)
		if err == nil {
	cacheMu.Lock()
	if len(cache) >= cacheMax {
		n := 0
		for k := range cache {
			delete(cache, k)
			n++
			if n >= cacheMax/2 {
				break
			}
		}
		log.Printf("cache full (%d), trimmed to %d", cacheMax, len(cache))
	}
	cache[vid] = cacheEntry{time.Now().Add(cacheTTL), tiers}
	cacheMu.Unlock()
			return tiers, nil
		}
		log.Printf("play vid=%s attempt %d fail: %v", vid, attempt+1, err)
		if !errors.Is(err, sdk.ErrAnonymousLimited) {
			return nil, err
		}
		dropClient()
		time.Sleep(400 * time.Millisecond)
	}
	return nil, err
}

func fetchOnce(c *sdk.Client, vid string) ([]tier, error) {
	pb, err := c.ResolvePlayback(context.Background(), vid, sdk.PlaybackOptions{})
	if err != nil {
		// DRM protected 是常态：漫剧流本来就是 cenc-aes-ctr 加密，
		// spade_a 在 KeyMaterial 里，App 端自己派生密钥解密（与旧链路一致）。
		if !errors.Is(err, sdk.ErrDRMProtected) || pb == nil {
			return nil, err
		}
		log.Printf("play vid=%s protected stream, key material %d bytes", vid, len(pb.KeyMaterial))
	}
	spade := pb.KeyMaterial
	out := make([]tier, 0, len(pb.Streams))
	for _, s := range pb.Streams {
		if strings.TrimSpace(s.URL) == "" {
			continue
		}
		h := s.Height
		if h <= 0 {
			digits := ""
			for _, r := range s.Definition {
				if r >= '0' && r <= '9' {
					digits += string(r)
				}
			}
			h, _ = strconv.Atoi(digits)
		}
		if h <= 0 {
			continue
		}
		out = append(out, tier{
			Definition: s.Definition,
			Height:     h,
			URL:        s.URL,
			Backup:     s.BackupURL,
			SpadeA:     spade,
			Size:       s.Size,
			Codec:      s.Codec,
			Width:      s.Width,
		})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("playback has no usable stream")
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Height > out[j].Height })

	cacheMu.Lock()
	cache[vid] = cacheEntry{time.Now().Add(cacheTTL), out}
	cacheMu.Unlock()
	return out, nil
}

func handler(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Vid  string `json:"vid"`
		Want string `json:"want"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad json", 400)
		return
	}
	vid := strings.TrimSpace(body.Vid)
	if vid == "" {
		http.Error(w, "bad vid", 400)
		return
	}
	tiers, err := fetch(vid)
	if err != nil {
		log.Printf("play vid=%s fail: %v", vid, err)
		http.Error(w, err.Error(), 502)
		return
	}
	want := strings.ToLower(strings.TrimSpace(body.Want))
	w.Header().Set("Content-Type", "application/json")
	if want == "all" || want == "*" {
		_ = json.NewEncoder(w).Encode(map[string]any{"list": tiers})
		return
	}
	cap := 0
	if strings.HasSuffix(want, "p") {
		cap, _ = strconv.Atoi(strings.TrimSuffix(want, "p"))
	}
	if cap <= 0 {
		cap = 1080
	}
	// tiers 降序：第一个 ≤cap 的就是 cap 内最高档；全都超了就给最高的
	pick := tiers[0]
	for _, t := range tiers {
		if t.Height <= cap {
			pick = t
			break
		}
	}
	log.Printf("play vid=%s want=%s -> %s %dx%d %.1fMB", vid, want, pick.Definition, pick.Width, pick.Height, float64(pick.Size)/1024/1024)
	_ = json.NewEncoder(w).Encode(pick)
}

func main() {
	addr := "127.0.0.1:9110"
	if v := os.Getenv("LISTEN"); v != "" {
		addr = v
	}
	log.Printf("streamsrv listening on %s", addr)
	http.HandleFunc("/play", handler)
	http.HandleFunc("/api/playinfo", playinfoHandler)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	log.Fatal(http.ListenAndServe(addr, nil))
}

// playinfoHandler 是开心果客户端用的入口：/api/playinfo 与旧中转服务同形，
// App 端零改动即可把它当「取流服务」使用（本机 127.0.0.1，不经过任何远程服务器）。
func playinfoHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	token := os.Getenv("TOKEN")
	if token == "" {
		token = "kxg-local"
	}
	if r.Header.Get("X-Token") != token {
		w.WriteHeader(403)
		_, _ = w.Write([]byte(`{"code":403,"error":"bad token"}`))
		return
	}
	var body struct {
		Vid  string `json:"vid"`
		Want string `json:"want"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		w.WriteHeader(400)
		_, _ = w.Write([]byte(`{"code":400,"error":"bad json"}`))
		return
	}
	vid := strings.TrimSpace(body.Vid)
	if vid == "" {
		w.WriteHeader(400)
		_, _ = w.Write([]byte(`{"code":400,"error":"bad vid"}`))
		return
	}
	tiers, err := fetch(vid)
	if err != nil {
		log.Printf("playinfo vid=%s fail: %v", vid, err)
		w.WriteHeader(502)
		_, _ = w.Write([]byte(`{"code":502,"error":"` + strings.ReplaceAll(err.Error(), `"`, "'") + `"}`))
		return
	}
	want := strings.ToLower(strings.TrimSpace(body.Want))
	cap := 0
	if strings.HasSuffix(want, "p") {
		cap, _ = strconv.Atoi(strings.TrimSuffix(want, "p"))
	}
	if cap <= 0 {
		cap = 1080
	}
	pick := tiers[0]
	for _, t := range tiers {
		if t.Height <= cap {
			pick = t
			break
		}
	}
	log.Printf("playinfo vid=%s want=%s -> %s %dx%d", vid, want, pick.Definition, pick.Width, pick.Height)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"code": 0,
		"data": map[string]any{
			"definition": pick.Definition,
			"height":     pick.Height,
			"width":      pick.Width,
			"url":        pick.URL,
			"backup":     pick.Backup,
			"spade_a":    pick.SpadeA,
			"size":       pick.Size,
			"codec":      pick.Codec,
		},
	})
}
