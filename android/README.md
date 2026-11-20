# 寮€蹇冩灉锛堝師銆岀孩鏋滅煭鍓?/ 绾㈡灉婕墽銆嶏級椤圭洰鎬昏 README

> 鏇存柊鏃堕棿锛?026-11-01銆傛湰鏂囧啓缁欏悗缁帴鎵嬬殑 AI 鎴栧伐绋嬪笀锛岃鐩?Windows 鏈満銆?> 鏈嶅姟鍣?192.168.1.54 涓婄殑鍏ㄩ儴缁勪欢銆佹瀯寤哄彂鐗堟祦绋嬨€佹祴璇曟柟娉曚笌宸茬煡鍧戙€?
---

## 1. 椤圭洰鏄粈涔?
銆屽紑蹇冩灉銆嶆槸涓€涓煭鍓?婕墽鎾斁搴旂敤锛屾暟鎹潵鑷孩鏋滐紙鐣寗灏忚绯伙級鐨勪笂娓告帴鍙ｃ€?涓€濂楀鎴风浠ｇ爜鎸?flavor 鍑轰袱涓寘锛?
| 鍖?| applicationId | 娓犻亾 | 瀹夎鍖?|
|---|---|---|---|
| Pad 鐗?| `com.hongguotv.pad` | pad flavor | `pad-pad-debug.apk`锛坉ebug 鍖呯洿鎺ュ彂锛?|
| TV 鐗?| `com.hongguotv.nativeapp` | tv flavor | `pad-tv-release-unsigned.apk`锛堥渶 zipalign + 绛惧悕锛?|

- **applicationId 鍜岀鍚嶈瘉涔︿笉鑳芥崲**锛氭崲浜嗕細鏂帀鐢ㄦ埛鐨勬洿鏂伴€氶亾鍜岀櫥褰曟€併€?  TV release 绛惧悕璇佷功 SHA256 = `7ff39ef970636015767b295cae8c027fc9108bf9be5c49542d90f33a9fd6aeb4`锛坉ebug keystore锛夈€?- 瀹㈡埛绔瑷€锛欿otlin + Jetpack Compose + Media3锛圗xoPlayer锛夈€俆V 绔湁瀹屾暣鐨勯仴鎺у櫒鐒︾偣浣撶郴銆?- 鍙︽湁 Windows 鐗堬細`/opt/hongguo-win`锛圱auri + Rust + React锛夛紝鏈枃浠?Android 绔负涓汇€?
## 2. 纭€ц鍒欙紙鐢ㄦ埛鏄庣‘瑕佹眰锛屽繀椤婚伒瀹堬級

1. **涓嶅噯鎿嶄綔鐪熸満**锛氬皬绫崇數瑙嗭紙MiTV-ASTP0锛夊拰灏忕背骞虫澘涓€寰嬩笉瑕?adb 瀹夎/鐐规寜/鎴浘銆?2. **鍙兘娴嬫ā鎷熷櫒**锛歀DPlayer锛坄D:\LDPlayer14`锛夛紝璁惧鍙?`emulator-5554`銆?3. 浜や粯鐗╋細APK 鏀?`D:\寮€蹇冩灉\{tv,pad}\` 骞舵洿鏂版洿鏂伴€氶亾锛涚湡鏈虹敱鐢ㄦ埛鑷繁瑁呫€?4. **APK 涓嶆斁妗岄潰**锛?026-10-30 璧凤級銆?5. **涓嶄富鍔ㄥ彂鐗?*锛氭敼瀹?bug 鍙仛缂栬瘧鍜屾湰鍦伴獙璇侊紝绛夌敤鎴疯"鍙戠増"鍐嶇敤 release.sh 鍙戙€?6. 鏈嶅姟鍣ㄤ笂鏀?Python 蹇呴』**鏈湴 Write + scp**锛岃繙绔?shell 鐨?heredoc/printf 浼氬悆杞箟銆?
## 3. 鏈嶅姟鍣?192.168.1.54锛圖ebian锛? 鏍?/ 7.3G 鍐呭瓨锛?
### 3.1 鎬庝箞杩?
```bash
ssh root@192.168.1.54          # 鏈満宸查厤濂藉厤瀵?key锛岀洿鎺ヨ繛
```

- 鍙湁灞€鍩熺綉鍐呭彲鐩磋繛锛涘缃戣闂蛋 Cloudflared 闅ч亾 `https://hgtv.fabao.xin`銆?- scp 鐢ㄦ硶鍚屼笂锛歚scp 鏈湴鏂囦欢 root@192.168.1.54:/鐩爣璺緞`銆?  鈿狅笍 Git Bash 浼氭妸浠?`/` 寮€澶寸殑杩滅璺緞褰撴湰鍦拌矾寰勫鐞嗭紙adb pull 韪╄繃锛夛紝
  杩滅璺緞涓€寰嬬敤 `root@host:/xxx` 鍐欐硶銆?
### 3.2 鐩綍鍦板浘

| 璺緞 | 鍐呭 |
|---|---|
| `/opt/hongguo-redo` | **Android 瀹㈡埛绔簮鐮?*锛圙radle 鍗曟ā鍧?`:pad`锛宲ad/tv 涓や釜 flavor锛?|
| `/opt/hongguo-redo/release.sh` | **鍙戠増涓€鏉￠緳鑴氭湰**锛堣 搂5锛?|
| `/opt/hgtv-update/serve.py` | **鏇存柊閫氶亾 + API 鏈嶅姟**锛岀洃鍚?`0.0.0.0:8080`锛坰ystemd 鎷夎捣锛?|
| `/opt/hgtv-update/relay.py` | **浠ｇ涓户**锛氬鎴风 鈫?涓户 鈫?涓婃父鎺ュ彛锛堝甫纾佺洏缂撳瓨锛?|
| `/opt/hgtv-update/playinfo.py` | 鍙栨祦鍏ュ彛锛?*鐜板湪鍙槸杞彂澹?*锛氳浆缁?9110 鐨?streamsrv锛涙棫鐨勫浐瀹氳韩浠界洿杩炰唬鐮佸凡鍋滅敤锛堢暀鐫€绛変笂娓告斁寮€锛?|
| `127.0.0.1:9110` | **streamsrv**锛圙o锛屾簮鐮?`/opt/research/openhongguo/cmd/streamsrv`锛夛細鐪熸鍙栨祦鐨勬湇鍔★紝绾?Go 绛惧悕 + 鍖垮悕璁惧姹犮€俙/play` 鍙栨。浣嶃€乣/seasons` 鍙栧娓呭崟 |
| `127.0.0.1:9100` | **绛惧悕鏈?*锛坄sign_server` 浜岃繘鍒讹紝POST /sign 杩斿洖甯︾鍚嶇殑 url+headers锛?|
| `/srv/hgtv-update/` | **鏇存柊閫氶亾鐩綍**锛欰PK + `version-tv.json` / `version-hgpad.json` 娓呭崟 |
| `/var/lib/hgtv-sync/` | feed-cache.json锛氫笂娓稿搷搴旂殑纾佺洏缂撳瓨锛圱TL 鐭級 |
| `/opt/hongguo-win` | Windows 鐗堟簮鐮?|
| `/opt/gradle/bin/gradle` | Gradle锛堢増鏈?8.14.x锛岄厤濂?JDK21锛?|
| `/opt/android-sdk/build-tools/35.0.0/` | zipalign / apksigner / aapt2 |
| `/root/.android/debug.keystore` | 绛惧悕 keystore锛坧ass: `android`锛宎lias: `androiddebugkey`锛?|
| `/root/hgtv-backup-1031/` | 2026-10-31 澶ф敼鍓嶇殑婧愮爜澶囦唤 |

鈿狅笍 `build.gradle.kts` / `gradle.properties` 宸茶皟浼橈紙鍫?2G銆乥uild cache銆佸叧 lintVital锛夛紝
璇﹁ 搂5.3锛沗systemctl`/`sc` 绫诲懡浠ゅ湪鏌愪簺娌欑鐜浼氳鎷︼紝閲嶅惎鏈嶅姟鍓嶅厛纭銆?
### 3.3 瀹㈡埛绔€庝箞杩炴湇鍔″櫒

- **灞€鍩熺綉**锛歚http://192.168.1.54:8080/...`锛堜紭鍏堬紝蹇級
- **澶栫綉**锛歚https://hgtv.fabao.xin/...`锛圕loudflared 闅ч亾锛屽伓鍙戞彙鎵嬭秴鏃讹紝瀹㈡埛绔湁閲嶈瘯锛?- 绔偣涓€瑙堬紙鍏ㄩ儴鐢?`serve.py` 鎻愪緵锛岄渶 `X-Token: ROTATED_OUT_old_token` 鐨勮鏍囧嚭鏉ワ級锛?  - `/version-tv.json`銆乣/version-hgpad.json` 鈥斺€?鏇存柊娓呭崟
  - `/app-tv-release.apk`銆乣/hongguo-pad-<鐗堟湰>.apk` 鈥斺€?瀹夎鍖咃紙鏀寔 Range 鏂偣缁紶锛?  - `/api/history?key=<sha256>` 鈥斺€?瑙傜湅鍘嗗彶璺ㄧ鍚屾锛堟寜璐﹀彿 uid 鐨?sha256 鍒嗘《锛孏ET 璇?/ POST 鍚堝苟 / `mode=delete` 鍒狅級
  - `/api/playinfo` 鈥斺€?鍙栨祦/鍏ㄦ。浣嶏紙杞?9110 streamsrv锛夈€愰渶 token銆?  - `/api/fetch` 鈥斺€?**浠ｅ彇 + 鍏变韩缂撳瓨**锛宐ody `{"url":...}` 璧扮洿杩炪€乣{"origin","path","query","body"}` 璧颁唬绛俱€愰渶 token銆?  - `/api/sign` 鈥斺€?鎶?9100 绛惧悕鏈洪€忓埌鍏綉銆愰渶 token銆?  - `/api/seasons?id=` 鈥斺€?澶氬鍓х殑瀹屾暣瀛ｆ竻鍗曪紙杞?9110锛?  - `/api/rankmeta` 鈥斺€?**鎺掕姒滆彍鍗?*锛氬ぇ绫?/ 瀛愭 / 绛涢€夐」 / 鏈堟锛堜粠涓婃父 `cell_selector` 鎶藉嚭鏉ワ紝绾?38KB锛屾湇鍔″櫒缂撳瓨 10 鍒嗛挓锛?  - `/api/fetchstats` 鈥斺€?relay 缂撳瓨鐨勫懡涓?鏈懡涓?鍒锋柊璁℃暟
  - `/api/family`銆乣/api/chart` 鈥斺€?**宸蹭笅绾匡紝鍥哄畾杩斿洖绌烘暟缁?*锛堟极鍓у簱 2026-10-31 鍏抽棴锛涘彛纰戞鏀圭敱瀹㈡埛绔槧灏勫畼鏂瑰瓙姒滐級
  - `/sync` 鈥斺€?鏃х殑杩涘害/鏀惰棌鍚堝苟鎺ュ彛锛堟棭鏈熺増鏈敤锛岀幇褰瑰鎴风宸蹭笉璧帮級

## 4. 瀹㈡埛绔簮鐮佸湴鍥撅紙/opt/hongguo-redo/pad/src/main/java/com/hongguotv/pad/锛?
| 鏂囦欢 | 鑱岃矗 |
|---|---|
| `Api.kt` | 鍏ㄩ儴涓婃父鎺ュ彛锛氭悳绱?鎵惧墽/璇︽儏/姒滃崟/鍙栨祦锛沗toCard`锛堝垪琛ㄦ潯鐩啋鍗＄墖锛夛紱缁挱浣嶇疆涓婃姤 |
| `Store.kt` | 鏈湴鐘舵€侊細鍘嗗彶/鏀惰棌/杩芥洿銆佽缃€佺画鎾綅缃紙`markPos`/`posOf`锛岃惤鐩?`pos_<vid>`锛夈€乣FindMemo`锛堟壘鍓ч〉璺ㄩ〉鐘舵€侊級 |
| `PlayerScreen.kt` | 鎾斁鍣細棰勫彇涓嬩竴闆嗭紙鏈湴浠ｇ悊 StreamProxy锛夈€佹崲闆嗐€侀仴鎺у櫒閿綅銆佽繘搴︿笂鎶ワ紙30s 涓€娆★級 |
| `Playback.kt` | ExoPlayer/StreamProxy 缁嗚妭 |
| `App.kt` | 瀵艰埅鏍?+ 渚ф爮 NavRail + **TV 鐒︾偣瑙勫垯**锛堣 搂6锛?|
| `Theme.kt` | TvRoot锛圱V 瀵嗗害鍘嬬缉鍒?1280dp锛夈€乀vFocusIndication锛堢劍鐐规锛夈€乀vDialogIndication锛堝脊绐楀唴鐒︾偣妗嗭級銆乀vFocusFrameOverlay |
| `Updater.kt` | 鏇存柊寮圭獥锛氫笅杞?鏍￠獙/璋冭捣瀹夎鍣紝**寮圭獥鍐呮寜閽敤 TvDialogIndication** |
| `MiscScreens.kt` | 鎵惧墽/姒滃崟/鍘嗗彶/杩芥洿/棰勭害/璁剧疆绛夐〉闈紙FindScreen 閲屾湁 readOnly 鎼滅储妗?+ chipEntry锛?|
| `DetailScreen.kt` / `Screens.kt` / `Creator.kt` | 璇︽儏/棣栭〉/鍒涗綔鑰?|
| `Danmaku.kt` | 寮瑰箷灞?|

## 5. 鏋勫缓涓庡彂鐗?
### 5.1 鎵嬪姩鏋勫缓

```bash
ssh root@192.168.1.54
cd /opt/hongguo-redo
/opt/gradle/bin/gradle :pad:assembleTvRelease :pad:assemblePadDebug
# TV release 闇€绛惧悕锛?cd pad/build/outputs/apk/tv/release
/opt/android-sdk/build-tools/35.0.0/zipalign -f -p 4 pad-tv-release-unsigned.apk aligned.apk
/opt/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks /root/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out signed.apk aligned.apk
```

### 5.2 鍙戠増锛堟帹鑽愶細涓€鏉″懡浠わ級

```bash
/opt/hongguo-redo/release.sh "2.2.31锛?路 鍙樻洿涓€
路 鍙樻洿浜?
# 宸叉墜鍔ㄦ敼杩?build.gradle.kts 鐨勭増鏈彿鏃讹細release.sh --resume "璇存槑"
```

鑴氭湰鍋氱殑浜嬶細鐗堟湰鍙疯嚜澧烇紙versionCode+1銆乿ersionName 鏈綅+1锛夆啋 鏋勫缓涓ょ 鈫?zipalign+绛惧悕 鈫?閮ㄧ讲鍒?`/srv/hgtv-update/`锛坄app-tv-release.apk` + `hongguo-pad-<鐗堟湰>.apk`锛夆啋 鐢熸垚涓や釜娓呭崟锛堝惈 size/sha256/notes锛夈€?鈿狅笍 鑴氭湰寮€浜?`set -euo pipefail`锛?*绂佹鍐欍€屽懡浠?| head銆嶅紡绠￠亾**锛坔ead 鎻愬墠閫€鍑鸿Е鍙?SIGPIPE 浼氶潤榛樻潃鑴氭湰锛夛紝
涓€寰嬪厛鍙栬繘鍙橀噺鍐?echo銆?
### 5.3 鏋勫缓鎬ц兘锛堝凡璋冧紭锛屽埆鍥為€€锛?
- 7.3G 鍐呭瓨鐨勬満鍣細Gradle 鍫?`-Xmx2g`銆乣kotlin.daemon.jvmargs=-Xmx1g`銆乣org.gradle.caching=true`銆?  涔嬪墠 4G 鍫嗘妸鍐呭瓨鎵撴弧銆乻wap 4.6G锛屾瀯寤?2 鍒嗗崐銆?- `lint { checkReleaseBuilds = false }`锛歳elease 鐨?lintVital 鍒嗘瀽鍗?35 绉掞紝宸插叧銆?- 鍓╀綑澶уご鏄?R8锛垀57 绉掞紝鍗曠嚎绋嬶級銆?*涓嶈鍏?R8**锛氬寘 4.3MB 鈫?13.4MB锛岀敤鎴蜂笅杞芥參 3 鍊嶃€?- 瀹炴祴锛氭敼涓€琛?鈫?鍏ㄩ噺 release 鈮?60-75 绉掞紱鏋勫缓缂撳瓨鍛戒腑 鈮?鍑犵銆?- **涓嶈鍒?`pad/build/tmp/kotlin-classes/`**锛岄偅浼氬己鍒跺叏閲忛噸缂栥€?- 鈿狅笍 Kotlin 澧為噺缂栬瘧瀵?绾敞閲?绌虹櫧"鏀瑰姩鍙兘鐩存帴璺宠繃锛屾祴鏋勫缓閫熷害瑕佺敤鐪熷疄浠ｇ爜鏀瑰姩銆?
### 5.4 鏇存柊娓呭崟鏍煎紡

```json
{
 "versionCode": 150, "versionName": "2.2.30", "url": "",
 "notes": "澶氳鏇存柊璇存槑", "path": "app-tv-release.apk",
 "size": 4451483, "sha256": "鈥?
}
```

瀹㈡埛绔細鍏堣瘯灞€鍩熺綉娓呭崟锛屽け璐ラ€€澶栫綉锛泇ersionCode 澶т簬鏈湴鎵嶅脊鏇存柊妗嗐€?鍙戠増鍚庢牳瀵癸細`curl https://hgtv.fabao.xin/version-tv.json`銆?
## 6. TV 鐒︾偣浣撶郴锛堟渶瀹规槗韪╁潙鐨勯儴鍒嗭級

- **鐒︾偣妗?*鏄叏灞€鍞竴鐨勶細鎺т欢鎶?`boundsInRoot()` 鎶ョ粰 `TvFocusFrame`锛屼富绐楀彛鏁村睆 Canvas 缁樺埗銆?  鏍峰紡锛?dp 鐧芥 + 鍗婇€忔槑榛戣‖搴曘€?- **Compose Dialog 鏄嫭绔?window**锛氬脊绐楀唴鎺т欢鐨?boundsInRoot 鏄脊绐楀潗鏍囩郴锛?  鎷垮埌涓荤獥鍙ｇ敾蹇呯劧閿欎綅 鈫?寮圭獥鍐呭蹇呴』鍖?`LocalIndication provides TvDialogIndication`锛堟湰鍦扮粯鍒讹級銆?- **`Modifier.focusRequester` 蹇呴』鏀惧湪 `clickable`/`focusable` 涔嬪墠**锛屾斁鍚庨潰姘歌繙鎸備笉涓婄洰鏍?  锛坮equestFocus 涓嶆姏寮傚父浣嗛潤榛樻棤鏁堚€斺€擿requestFocus()` 杩斿洖 Unit锛?  銆屾病鎶涘紓甯搞€嶁墵銆岀劍鐐硅惤涓婁簡銆嶏紝瑕佺敤 onFocusChanged 鍥炶皟纭锛夈€?- **鏂瑰悜閿湪渚ф爮绉诲姩鐒︾偣涓嶅垏椤?*锛屽彧鏈夌‘瀹氾紙clickable锛夋墠鍒囬〉銆備晶鏍忔寜 鈫?璧?`enterContent()`锛?  鍒囬〉 + 鍊?1dp 鐒︾偣璺虫澘鎶婄劍鐐归€佽繘鍐呭绗竴涓帶浠讹紝甯﹂噸璇曪紙椤甸潰鍙兘杩樺湪鍔犺浇锛夈€?- 鍐呭鍖猴紙椤跺眰椤碉級鎸?*杩斿洖** = 鐒︾偣鍥炰晶鏍忓綋鍓嶅垎绫伙紙`railEntry.requestFocus()`锛夛紝
  鐢?onKeyEvent锛堝啋娉★級瀹炵幇锛屼笉鎶㈣緭鍏ユ/鎾斁鍣ㄧ殑杩斿洖锛涘啀鎸変竴娆℃墠鏄€€鍑恒€?- 鎵惧墽鎼滅储妗?TV 涓婇粯璁?`readOnly`锛氳仛鐒︿笉寮归敭鐩橈紝鎸夌‘瀹氭墠杩涜緭鍏ユ€侊紙`typing` 鐘舵€侊級銆?- 鐒︾偣璺虫澘锛氬唴瀹瑰尯宸︾紭 1dp 鍏ㄩ珮闅愬舰鏉★紝鈫?鏃惰浆鍙戝埌 `railEntry`锛堝嚑浣?Left 浼氳惤閿欓」锛夈€?- TV 瀵嗗害锛歍vRoot 鎶婇€昏緫瀹藉害鍘嬪埌 1280dp锛坉ensity = 瀹為檯鍍忕礌瀹?1280锛夈€?  **涓荤獥鍙ｅ拰 Dialog 鐨?density 鍙兘涓嶅悓**锛屽埆璺ㄧ獥鍙ｆ崲绠楀潗鏍囥€?
## 7. 鏁版嵁涓庢寚鏍囷紙涓婃父鎺ュ彛瀹炴祴锛?
- **鍗＄墖涓婄殑鏁板瓧 = 鐑害鍊?*锛氬彇鍊间紭鍏堢骇 鈶燻video_detail.hot_score` 鈶rec_text`锛?4993涓囩儹搴?锛?  鈶sub_title_list` 閲?`data_type=27`锛?1857涓?锛夆懀鍏滃簳 `play_cnt`銆?  鈿狅笍 `play_cnt` 鏄?*鎾斁娆℃暟**涓嶆槸鐑害锛堣鎯呴〉 4993涓囩儹搴?hot_score锛?86涓?play_cnt锛屼袱涓瓧娈碉級銆?- 鍒楄〃鎺ュ彛锛坰earch/tab/v銆乴andpage/v1/锛夌洿鎺ュ氨鏈夛細鎾斁娆℃暟銆佽瘎鍒嗐€佺儹搴︽枃鏈€佽瘎璁烘暟銆侀泦鏁般€佺煭绠€浠嬨€佸垎绫汇€?- 蹇呴』鎶撹鎯咃紙video_detail/v1/锛夋墠鏈夛細鐑害绮剧‘鏁板€笺€佽拷鍓т汉鏁?followed_cnt銆佺偣璧?digg_cnt銆?  瀹屾暣绠€浠嬨€佹紨鑱屽憳 main_creator_users銆佸垎闆嗚〃 video_list銆佸淇℃伅銆佸叧鑱斿皬璇淬€佸妗堛€?- 璇勫垎鍙兘鍑虹幇鍦?`score`锛堝垪琛級鎴?`secondary_infos`锛堣鎯咃紝澶氭暟鍓т负绌猴級鈥斺€旀寜鍐呭鍖归厤鏈€绋炽€?- 姒滃崟銆屽叏閮ㄣ€嶅垎绫?id 鏄?`all` 涓嶆槸 `*`锛涘悇澶х被鏈変笓灞炲瓙姒?id锛涙紨鍛樻杩斿洖鐨勬槸"浜?涓嶆槸鍓с€?- 鍒楄〃鍝嶅簲閲岀殑 `video_detail.video_list`锛堟暣閮ㄥ垎闆嗚〃锛夋浘鍗犲搷搴?96.6%锛屽垪琛ㄩ〉鐢ㄤ笉鍒般€?
## 8. 娴嬭瘯鏂规硶

- 妯℃嫙鍣細LDPlayer `D:\LDPlayer14`锛宍adb.exe -s emulator-5554`銆傛祴璇曞伐鍏峰湪
  `C:\Users\Ark\AppData\Local\Temp\tvfix\`锛歚audit.py`锛堟壒閲忔寜閿?鎴浘锛夈€乣grid.py`锛堟埅鍥剧綉鏍煎潗鏍囷級銆?  `locate.py`锛堝厓绱犲畾浣嶏級銆侾ad 鐗堢偣鍑诲潗鏍囨槸绾瘮渚嬫崲绠楋紙脳1.7778锛夛紱TV 鐗堟棤棰濆鍋忕Щ銆?- 鈿狅笍 **鐐瑰睆骞曠殑鍧愭爣鎹㈢畻**锛歚screencap` 鍑烘潵鐨勫浘鏄?1920脳1080锛岃€岀湅鍥炬椂鏄缉鏀捐繃鐨勶紙绾?1440脳810锛夛紝
  鎵€浠ユ寜"鐪嬪埌鐨勫儚绱?鐐逛細鍋忋€傝澶囧潗鏍?= 鐪嬪埌鐨勫潗鏍?脳 1.3333銆備晶鏍忎節椤逛粠涓婂埌涓嬪ぇ绾﹀湪
  y=153 / 261 / 343 / 424 / **500(鍘嗗彶)** / 580 / 659 / 733 / 813锛坸鈮?9锛夈€?  鐐规鐨勮〃鐜版槸"鐐逛簡娌″弽搴?锛屾帓鏌ユ椂鍏堟€€鐤戣繖閲屻€?- **鎹㈤泦榛戝抚妫€娴?*锛歚adb shell screenrecord` 褰曞睆 鈫?`adb exec-out cat /sdcard/x.mp4 > x.mp4` 鍙栧洖
  锛堚殸锔?`adb pull /sdcard/...` 鍦?Git Bash 涓嬭矾寰勪細琚敼鍐欙紝蹇呴』鐢?exec-out cat锛夆啋
  鏈嶅姟鍣?`ffmpeg -i x.mp4 -vf fps=20,scale=160:90` 鎷嗗抚 鈫?PIL 绠楁瘡甯у钩鍧囦寒搴︽壘鏆楀抚銆?- 妯℃嫙鍣ㄥ伓灏旈棯閫€/鎺夌嚎锛歚ldconsole quit --index 0` 鍚?`launch`锛岃捣鏉ュ悗 **1-2 鍒嗛挓鍐呭敖蹇祴瀹?*銆?- 妯℃嫙鍣ㄩ噸鍚悗搴旂敤鍙兘闇€瑕侀噸鏂伴厤瀵圭櫥褰曪紙鍋氳繃 `pm clear` 浼氭竻鐧诲綍鎬侊級銆?
## 9. 浜や粯鐗╀笌鏂囨。浣嶇疆

| 浣嶇疆 | 鍐呭 |
|---|---|
| `D:\寮€蹇冩灉\tv\`銆乣D:\寮€蹇冩灉\pad\` | 鍚勭増鏈?APK 瀛樻。锛堝懡鍚?`寮€蹇冩灉TV-2.2.30.apk`锛?|
| `D:\寮€蹇冩灉\宸茬煡闂.md` | 宸茬煡闂娓呭崟锛堝垎浜旂被锛屽惈宸蹭慨璁板綍锛?|
| 鏈満 `C:\Users\Ark\AppData\Local\Temp\tvfix\` | 娴嬭瘯鑴氭湰涓庢埅鍥撅紙涓存椂鐩綍锛屽埆鏀鹃噸瑕佷笢瑗匡級 |
| 鏈闈?`寮€蹇冩灉-README.md` | 鏈枃妗?|
| 鏈嶅姟鍣?`/opt/hongguo-redo/README.md` | 鏈枃妗ｇ殑鏈嶅姟鍣ㄥ壇鏈?|

## 10. 鐗堟湰鍘嗗彶閫熻锛堟埅鑷?2.2.30 / versionCode 150锛?
- 2.2.26 鍘嗗彶缁挱锛堣法閲嶅惎鎺ョ潃鐪?+ 杩涘害鍚屾鎵嬫満/Windows锛?- 2.2.27 鏇存柊寮圭獥鐒︾偣妗嗛敊浣嶄慨澶嶏紙TvDialogIndication锛? 鍗＄墖鏁板瓧鏀圭儹搴﹀€?- 2.2.29 寮圭獥鐒︾偣妗嗗渾瑙掕嚜閫傚簲锛堝皬鎺т欢涓嶅啀鍛堟き鍦嗭級
- 2.2.30 **閬ユ帶鍣ㄧ劍鐐硅鍒欓噸鍋?*锛堜晶鏍?鈫?鍒囬〉杩涘唴瀹瑰甫閲嶈瘯銆佽繑鍥炲洖渚ф爮銆佸惎鍔ㄩ粯璁ょ劍鐐广€佹壘鍓ч潰鏉块鐑級
- 鏇存棭锛氭挱鏀惧櫒閿綅锛堝乏鍙陈?s/纭畾鏆傚仠涓嶅脊鎺у埗鏉★級銆佽拷鏇撮敊璇€併€佹壘鍓?readOnly 鎼滅储妗?璺ㄩ〉淇濈暀銆佹崲闆嗘棤缂濄€佸彂鐗堣剼鏈瓑

## 11. 鐗堟湰绠＄悊锛坓it锛?
- **浠撳簱**锛歚/opt/hongguo-redo/.git`锛屼富鍒嗘敮 `main`
- **澶囦唤瑁镐粨搴?*锛歚/mnt/data/git/kaxinguo-android.git`锛堟湰浠撳簱閲岀殑 remote 鍙?`backup`锛?- **鏀瑰畬浠ｇ爜**锛?  ```bash
  cd /opt/hongguo-redo
  git add -A
  git commit -m "2.2.61锛氭敼浜嗕粈涔?
  git push backup main
  ```
- **鍙戠増鏃舵墦鏍囩**锛堟柟渚夸互鍚庢寜鐗堟湰鍥炵湅锛夛細
  ```bash
  git tag 2.2.61 && git push backup 2.2.61
  ```
- **鏀瑰潖浜嗚鍥炴粴**锛?  ```bash
  git checkout -- pad/src/main/java/com/hongguotv/pad/xxx.kt   # 鎾や竴涓枃浠?  git reset --hard HEAD~1                                      # 鎾ゆ渶鍚庝竴娆℃彁浜?  ```
- **涓嶈繘搴?*锛歚build/`銆乣.gradle/`銆乣local.properties`銆佷换浣?`*.apk` / `*.jks`锛堣 `.gitignore`锛夈€?  绛惧悕鍜屾湰鏈?SDK 璺緞灞炰簬鏈哄櫒鐜锛屼笉璺熺潃浠ｇ爜璧般€?- 棣栦釜鎻愪氦 `e9ade48` 瀵瑰簲绾夸笂 2.2.60锛坴ersionCode 180锛夌殑婧愮爜鐘舵€侊紝鍙洿鎺ュ綋鍩哄噯銆?