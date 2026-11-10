# 开心果（原「红果短剧 / 红果漫剧」）项目总览 README

> 更新时间：2026-11-01。本文写给后续接手的 AI 或工程师，覆盖 Windows 本机、
> 服务器 192.168.1.54 上的全部组件、构建发版流程、测试方法与已知坑。

---

## 1. 项目是什么

「开心果」是一个短剧/漫剧播放应用，数据来自红果（番茄小说系）的上游接口。
一套客户端代码按 flavor 出两个包：

| 包 | applicationId | 渠道 | 安装包 |
|---|---|---|---|
| Pad 版 | `com.hongguotv.pad` | pad flavor | `pad-pad-debug.apk`（debug 包直接发） |
| TV 版 | `com.hongguotv.nativeapp` | tv flavor | `pad-tv-release-unsigned.apk`（需 zipalign + 签名） |

- **applicationId 和签名证书不能换**：换了会断掉用户的更新通道和登录态。
  TV release 签名证书 SHA256 = `7ff39ef970636015767b295cae8c027fc9108bf9be5c49542d90f33a9fd6aeb4`（debug keystore）。
- 客户端语言：Kotlin + Jetpack Compose + Media3（ExoPlayer）。TV 端有完整的遥控器焦点体系。
- 另有 Windows 版：`/opt/hongguo-win`（Tauri + Rust + React），本文以 Android 端为主。

## 2. 硬性规则（用户明确要求，必须遵守）

1. **不准操作真机**：小米电视（MiTV-ASTP0）和小米平板一律不要 adb 安装/点按/截图。
2. **只能测模拟器**：LDPlayer（`D:\LDPlayer14`），设备号 `emulator-5554`。
3. 交付物：APK 放 `D:\开心果\{tv,pad}\` 并更新更新通道；真机由用户自己装。
4. **APK 不放桌面**（2026-10-30 起）。
5. **不主动发版**：改完 bug 只做编译和本地验证，等用户说"发版"再用 release.sh 发。
6. 服务器上改 Python 必须**本地 Write + scp**，远端 shell 的 heredoc/printf 会吃转义。

## 3. 服务器 192.168.1.54（Debian，8 核 / 7.3G 内存）

### 3.1 怎么连

```bash
ssh root@192.168.1.54          # 本机已配好免密 key，直接连
```

- 只有局域网内可直连；外网访问走 Cloudflared 隧道 `https://hgtv.fabao.xin`。
- scp 用法同上：`scp 本地文件 root@192.168.1.54:/目标路径`。
  ⚠️ Git Bash 会把以 `/` 开头的远端路径当本地路径处理（adb pull 踩过），
  远端路径一律用 `root@host:/xxx` 写法。

### 3.2 目录地图

| 路径 | 内容 |
|---|---|
| `/opt/hongguo-redo` | **Android 客户端源码**（Gradle 单模块 `:pad`，pad/tv 两个 flavor） |
| `/opt/hongguo-redo/release.sh` | **发版一条龙脚本**（见 §5） |
| `/opt/hgtv-update/serve.py` | **更新通道 + API 服务**，监听 `0.0.0.0:8080`（systemd 拉起） |
| `/opt/hgtv-update/relay.py` | **代签中继**：客户端 → 中继 → 上游接口（带磁盘缓存） |
| `/opt/hgtv-update/playinfo.py` | 取流入口，**现在只是转发壳**：转给 9110 的 streamsrv；旧的固定身份直连代码已停用（留着等上游放开） |
| `127.0.0.1:9110` | **streamsrv**（Go，源码 `/opt/research/openhongguo/cmd/streamsrv`）：真正取流的服务，纯 Go 签名 + 匿名设备池。`/play` 取档位、`/seasons` 取季清单 |
| `127.0.0.1:9100` | **签名机**（`sign_server` 二进制，POST /sign 返回带签名的 url+headers） |
| `/srv/hgtv-update/` | **更新通道目录**：APK + `version-tv.json` / `version-hgpad.json` 清单 |
| `/var/lib/hgtv-sync/` | feed-cache.json：上游响应的磁盘缓存（TTL 短） |
| `/opt/hongguo-win` | Windows 版源码 |
| `/opt/gradle/bin/gradle` | Gradle（版本 8.14.x，配好 JDK21） |
| `/opt/android-sdk/build-tools/35.0.0/` | zipalign / apksigner / aapt2 |
| `/root/.android/debug.keystore` | 签名 keystore（pass: `android`，alias: `androiddebugkey`） |
| `/root/hgtv-backup-1031/` | 2026-10-31 大改前的源码备份 |

⚠️ `build.gradle.kts` / `gradle.properties` 已调优（堆 2G、build cache、关 lintVital），
详见 §5.3；`systemctl`/`sc` 类命令在某些沙箱环境会被拦，重启服务前先确认。

### 3.3 客户端怎么连服务器

- **局域网**：`http://192.168.1.54:8080/...`（优先，快）
- **外网**：`https://hgtv.fabao.xin/...`（Cloudflared 隧道，偶发握手超时，客户端有重试）
- 端点一览（全部由 `serve.py` 提供，需 `X-Token: hgtv-play-2026` 的要标出来）：
  - `/version-tv.json`、`/version-hgpad.json` —— 更新清单
  - `/app-tv-release.apk`、`/hongguo-pad-<版本>.apk` —— 安装包（支持 Range 断点续传）
  - `/api/history?key=<sha256>` —— 观看历史跨端同步（按账号 uid 的 sha256 分桶，GET 读 / POST 合并 / `mode=delete` 删）
  - `/api/playinfo` —— 取流/全档位（转 9110 streamsrv）【需 token】
  - `/api/fetch` —— **代取 + 共享缓存**，body `{"url":...}` 走直连、`{"origin","path","query","body"}` 走代签【需 token】
  - `/api/sign` —— 把 9100 签名机透到公网【需 token】
  - `/api/seasons?id=` —— 多季剧的完整季清单（转 9110）
  - `/api/rankmeta` —— **排行榜菜单**：大类 / 子榜 / 筛选项 / 月榜（从上游 `cell_selector` 抽出来，约 38KB，服务器缓存 10 分钟）
  - `/api/fetchstats` —— relay 缓存的命中/未命中/刷新计数
  - `/api/family`、`/api/chart` —— **已下线，固定返回空数组**（漫剧库 2026-10-31 关闭；口碑榜改由客户端映射官方子榜）
  - `/sync` —— 旧的进度/收藏合并接口（早期版本用，现役客户端已不走）

## 4. 客户端源码地图（/opt/hongguo-redo/pad/src/main/java/com/hongguotv/pad/）

| 文件 | 职责 |
|---|---|
| `Api.kt` | 全部上游接口：搜索/找剧/详情/榜单/取流；`toCard`（列表条目→卡片）；续播位置上报 |
| `Store.kt` | 本地状态：历史/收藏/追更、设置、续播位置（`markPos`/`posOf`，落盘 `pos_<vid>`）、`FindMemo`（找剧页跨页状态） |
| `PlayerScreen.kt` | 播放器：预取下一集（本地代理 StreamProxy）、换集、遥控器键位、进度上报（30s 一次） |
| `Playback.kt` | ExoPlayer/StreamProxy 细节 |
| `App.kt` | 导航栈 + 侧栏 NavRail + **TV 焦点规则**（见 §6） |
| `Theme.kt` | TvRoot（TV 密度压缩到 1280dp）、TvFocusIndication（焦点框）、TvDialogIndication（弹窗内焦点框）、TvFocusFrameOverlay |
| `Updater.kt` | 更新弹窗：下载/校验/调起安装器，**弹窗内按钮用 TvDialogIndication** |
| `MiscScreens.kt` | 找剧/榜单/历史/追更/预约/设置等页面（FindScreen 里有 readOnly 搜索框 + chipEntry） |
| `DetailScreen.kt` / `Screens.kt` / `Creator.kt` | 详情/首页/创作者 |
| `Danmaku.kt` | 弹幕层 |

## 5. 构建与发版

### 5.1 手动构建

```bash
ssh root@192.168.1.54
cd /opt/hongguo-redo
/opt/gradle/bin/gradle :pad:assembleTvRelease :pad:assemblePadDebug
# TV release 需签名：
cd pad/build/outputs/apk/tv/release
/opt/android-sdk/build-tools/35.0.0/zipalign -f -p 4 pad-tv-release-unsigned.apk aligned.apk
/opt/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks /root/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out signed.apk aligned.apk
```

### 5.2 发版（推荐：一条命令）

```bash
/opt/hongguo-redo/release.sh "2.2.31：
· 变更一
· 变更二"
# 已手动改过 build.gradle.kts 的版本号时：release.sh --resume "说明"
```

脚本做的事：版本号自增（versionCode+1、versionName 末位+1）→ 构建两端 → zipalign+签名 →
部署到 `/srv/hgtv-update/`（`app-tv-release.apk` + `hongguo-pad-<版本>.apk`）→ 生成两个清单（含 size/sha256/notes）。
⚠️ 脚本开了 `set -euo pipefail`：**禁止写「命令 | head」式管道**（head 提前退出触发 SIGPIPE 会静默杀脚本），
一律先取进变量再 echo。

### 5.3 构建性能（已调优，别回退）

- 7.3G 内存的机器：Gradle 堆 `-Xmx2g`、`kotlin.daemon.jvmargs=-Xmx1g`、`org.gradle.caching=true`。
  之前 4G 堆把内存打满、swap 4.6G，构建 2 分半。
- `lint { checkReleaseBuilds = false }`：release 的 lintVital 分析占 35 秒，已关。
- 剩余大头是 R8（~57 秒，单线程）。**不要关 R8**：包 4.3MB → 13.4MB，用户下载慢 3 倍。
- 实测：改一行 → 全量 release ≈ 60-75 秒；构建缓存命中 ≈ 几秒。
- **不要删 `pad/build/tmp/kotlin-classes/`**，那会强制全量重编。
- ⚠️ Kotlin 增量编译对"纯注释/空白"改动可能直接跳过，测构建速度要用真实代码改动。

### 5.4 更新清单格式

```json
{
 "versionCode": 150, "versionName": "2.2.30", "url": "",
 "notes": "多行更新说明", "path": "app-tv-release.apk",
 "size": 4451483, "sha256": "…"
}
```

客户端：先试局域网清单，失败退外网；versionCode 大于本地才弹更新框。
发版后核对：`curl https://hgtv.fabao.xin/version-tv.json`。

## 6. TV 焦点体系（最容易踩坑的部分）

- **焦点框**是全局唯一的：控件把 `boundsInRoot()` 报给 `TvFocusFrame`，主窗口整屏 Canvas 绘制。
  样式：3dp 白框 + 半透明黑衬底。
- **Compose Dialog 是独立 window**：弹窗内控件的 boundsInRoot 是弹窗坐标系，
  拿到主窗口画必然错位 → 弹窗内容必须包 `LocalIndication provides TvDialogIndication`（本地绘制）。
- **`Modifier.focusRequester` 必须放在 `clickable`/`focusable` 之前**，放后面永远挂不上目标
  （requestFocus 不抛异常但静默无效——`requestFocus()` 返回 Unit，
  「没抛异常」≠「焦点落上了」，要用 onFocusChanged 回调确认）。
- **方向键在侧栏移动焦点不切页**，只有确定（clickable）才切页。侧栏按 → 走 `enterContent()`：
  切页 + 借 1dp 焦点跳板把焦点送进内容第一个控件，带重试（页面可能还在加载）。
- 内容区（顶层页）按**返回** = 焦点回侧栏当前分类（`railEntry.requestFocus()`），
  用 onKeyEvent（冒泡）实现，不抢输入框/播放器的返回；再按一次才是退出。
- 找剧搜索框 TV 上默认 `readOnly`：聚焦不弹键盘，按确定才进输入态（`typing` 状态）。
- 焦点跳板：内容区左缘 1dp 全高隐形条，← 时转发到 `railEntry`（几何 Left 会落错项）。
- TV 密度：TvRoot 把逻辑宽度压到 1280dp（density = 实际像素宽/1280）。
  **主窗口和 Dialog 的 density 可能不同**，别跨窗口换算坐标。

## 7. 数据与指标（上游接口实测）

- **卡片上的数字 = 热度值**：取值优先级 ①`video_detail.hot_score` ②`rec_text`（"4993万热度"）
  ③`sub_title_list` 里 `data_type=27`（"1857万"）④兜底 `play_cnt`。
  ⚠️ `play_cnt` 是**播放次数**不是热度（详情页 4993万热度=hot_score，786万=play_cnt，两个字段）。
- 列表接口（search/tab/v、landpage/v1/）直接就有：播放次数、评分、热度文本、评论数、集数、短简介、分类。
- 必须抓详情（video_detail/v1/）才有：热度精确数值、追剧人数 followed_cnt、点赞 digg_cnt、
  完整简介、演职员 main_creator_users、分集表 video_list、季信息、关联小说、备案。
- 评分可能出现在 `score`（列表）或 `secondary_infos`（详情，多数剧为空）——按内容匹配最稳。
- 榜单「全部」分类 id 是 `all` 不是 `*`；各大类有专属子榜 id；演员榜返回的是"人"不是剧。
- 列表响应里的 `video_detail.video_list`（整部分集表）曾占响应 96.6%，列表页用不到。

## 8. 测试方法

- 模拟器：LDPlayer `D:\LDPlayer14`，`adb.exe -s emulator-5554`。测试工具在
  `C:\Users\Ark\AppData\Local\Temp\tvfix\`：`audit.py`（批量按键+截图）、`grid.py`（截图网格坐标）、
  `locate.py`（元素定位）。Pad 版点击坐标是纯比例换算（×1.7778）；TV 版无额外偏移。
- ⚠️ **点屏幕的坐标换算**：`screencap` 出来的图是 1920×1080，而看图时是缩放过的（约 1440×810），
  所以按"看到的像素"点会偏。设备坐标 = 看到的坐标 × 1.3333。侧栏九项从上到下大约在
  y=153 / 261 / 343 / 424 / **500(历史)** / 580 / 659 / 733 / 813（x≈49）。
  点歪的表现是"点了没反应"，排查时先怀疑这里。
- **换集黑帧检测**：`adb shell screenrecord` 录屏 → `adb exec-out cat /sdcard/x.mp4 > x.mp4` 取回
  （⚠️ `adb pull /sdcard/...` 在 Git Bash 下路径会被改写，必须用 exec-out cat）→
  服务器 `ffmpeg -i x.mp4 -vf fps=20,scale=160:90` 拆帧 → PIL 算每帧平均亮度找暗帧。
- 模拟器偶尔闪退/掉线：`ldconsole quit --index 0` 后 `launch`，起来后 **1-2 分钟内尽快测完**。
- 模拟器重启后应用可能需要重新配对登录（做过 `pm clear` 会清登录态）。

## 9. 交付物与文档位置

| 位置 | 内容 |
|---|---|
| `D:\开心果\tv\`、`D:\开心果\pad\` | 各版本 APK 存档（命名 `开心果TV-2.2.30.apk`） |
| `D:\开心果\已知问题.md` | 已知问题清单（分五类，含已修记录） |
| 本机 `C:\Users\Ark\AppData\Local\Temp\tvfix\` | 测试脚本与截图（临时目录，别放重要东西） |
| 本桌面 `开心果-README.md` | 本文档 |
| 服务器 `/opt/hongguo-redo/README.md` | 本文档的服务器副本 |

## 10. 版本历史速览（截至 2.2.30 / versionCode 150）

- 2.2.26 历史续播（跨重启接着看 + 进度同步手机/Windows）
- 2.2.27 更新弹窗焦点框错位修复（TvDialogIndication）+ 卡片数字改热度值
- 2.2.29 弹窗焦点框圆角自适应（小控件不再呈椭圆）
- 2.2.30 **遥控器焦点规则重做**（侧栏 → 切页进内容带重试、返回回侧栏、启动默认焦点、找剧面板预热）
- 更早：播放器键位（左右±5s/确定暂停不弹控制条）、追更错误态、找剧 readOnly 搜索框+跨页保留、换集无缝、发版脚本等

## 11. 版本管理（git）

- **仓库**：`/opt/hongguo-redo/.git`，主分支 `main`
- **备份裸仓库**：`/mnt/data/git/kaxinguo-android.git`（本仓库里的 remote 叫 `backup`）
- **改完代码**：
  ```bash
  cd /opt/hongguo-redo
  git add -A
  git commit -m "2.2.61：改了什么"
  git push backup main
  ```
- **发版时打标签**（方便以后按版本回看）：
  ```bash
  git tag 2.2.61 && git push backup 2.2.61
  ```
- **改坏了要回滚**：
  ```bash
  git checkout -- pad/src/main/java/com/hongguotv/pad/xxx.kt   # 撤一个文件
  git reset --hard HEAD~1                                      # 撤最后一次提交
  ```
- **不进库**：`build/`、`.gradle/`、`local.properties`、任何 `*.apk` / `*.jks`（见 `.gitignore`）。
  签名和本机 SDK 路径属于机器环境，不跟着代码走。
- 首个提交 `e9ade48` 对应线上 2.2.60（versionCode 180）的源码状态，可直接当基准。
