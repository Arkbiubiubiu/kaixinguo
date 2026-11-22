# -*- coding: utf-8 -*-
import json, html, os

LAYERS = [
    ("L0", "产品入口", "#ff6b6b"),
    ("L1", "页面与交互", "#f0b35b"),
    ("L2", "客户端领域", "#64d2a0"),
    ("L3", "共享核心", "#57c7e3"),
    ("L4", "平台适配", "#7a9cff"),
    ("L5", "服务端入口", "#b58cff"),
    ("L6", "解析与上游", "#9aa4b2"),
    ("L7", "存储/安全/监控/运维", "#ff8f6b"),
]

N = [
    # L0
    dict(id="S-PC", layer="L0", name="PC 桌面端", summary="Windows 客户端，Compose Desktop + 本机 ffmpeg。三端功能基准之一。", files=["src/main/kotlin/com/kaixinguo/pc"]),
    dict(id="S-PAD", layer="L0", name="Android Pad", summary="平板触屏客户端，包名 com.hongguotv.pad。与 TV 共用同一套源码。", files=["android/pad"]),
    dict(id="S-TV", layer="L0", name="Android TV", summary="电视遥控器客户端，flavor tv，包名 com.hongguotv.nativeapp。", files=["android/pad (flavor tv)"]),
    dict(id="S-WEB", layer="L0", name="官网 / 下载页", summary="三端下载入口，读取版本清单并跳转下载节点。", files=["/srv/hgtv-update/index.html"]),
    dict(id="S-ADMIN", layer="L0", name="管理看板", summary="设备数、版本、接入端口、请求量、VVC 使用；顶部「架构世界树」页签。", files=["/api/admin?token=…", "/api/admin/tree?token=…"]),

    # L1 PC
    dict(id="P-SHELL", layer="L1", name="PC 应用壳 / 路由", summary="窗口、侧栏、页面切换、返回栈、打赏浮层。所有 PC 页面从这里进入。", files=["Main.kt: AppShell", "Main.kt: RouteHolder", "Main.kt: Route"]),
    dict(id="P-HOME", layer="L1", name="PC 首页", summary="推荐/漫剧/真人剧三 tab、hero、卡片流、换一批、继续看；tab/列表/hero/滚动存 UiMemo，详情返回保留现场。", files=["HomeScreens.kt: HomePage"]),
    dict(id="P-FIND", layer="L1", name="PC 找剧 / 搜索", summary="搜索、联想、筛选、结果列表；条件/结果/分页/滚动存 FindMemo+UiMemo，详情返回不丢。", files=["FindScreen.kt: FindPage"]),
    dict(id="P-RANK", layer="L1", name="PC 排行榜", summary="榜单分类、筛选、月榜、演员榜；大类/子榜/筛选/列表/分页/滚动全部记忆，详情返回保留。", files=["RankScreen.kt: RankPage"]),
    dict(id="P-NEW", layer="L1", name="PC 新剧 / 日历", summary="新剧列表、上线日历；视图/分类/日期/分页/滚动存 PageMemo，从详情返回保留现场。", files=["NewScreen.kt: NewPage"]),
    dict(id="P-HISTORY", layer="L1", name="PC 历史页", summary="本地/云端历史、筛选、管理、继续播。", files=["LibraryScreens.kt: HistoryPage"]),
    dict(id="P-LIBRARY", layer="L1", name="PC 追更/预约/我的", summary="追更提醒、预约、收藏/点赞入口、账号同步码。", files=["LibraryScreens.kt: FollowsPage/ReservePage/MinePage"]),
    dict(id="P-DETAIL", layer="L1", name="PC 详情页", summary="详情、选集、季、剧评、相关推荐、继续看。", files=["DetailScreen.kt"]),
    dict(id="P-PLAYER", layer="L1", name="PC 播放器", summary="起播、切集、清晰度、倍速、队列、预取、看门狗、历史、弹幕、评论。", files=["Player.kt"]),
    dict(id="P-FEED", layer="L1", name="PC 刷剧", summary="整屏连播、换剧、预览不记历史；**后 5 部第一集真实流预取（warmFeed），进页即预拉下一页**。", files=["FeedPlayerScreen.kt: FeedPlayerPage", "Player.kt: embedded"]),
    dict(id="P-LOGIN", layer="L1", name="PC 登录/配对", summary="短信 MFA、账号 cookies、匹配码同步。", files=["PcLogin.kt", "PcLoginScreen.kt"]),
    dict(id="P-DONATE", layer="L1", name="PC 打赏浮层", summary="展示打赏码，不接支付 SDK。", files=["Donate.kt"]),

    # L1 Android
    dict(id="A-SHELL", layer="L1", name="Android App / 导航", summary="初始化、路由栈、侧栏、返回、Pad/TV 适配。", files=["MainActivity.kt", "App.kt", "Screen.kt"]),
    dict(id="A-HOME", layer="L1", name="Android 首页", summary="三 tab、hero、卡片、继续看。", files=["HomeFeed.kt"]),
    dict(id="A-FEED", layer="L1", name="Android 刷剧", summary="整屏连播、上下换剧；后 5 部详情、最近 2 部完整预载（回档 2.2.203 口径）。", files=["FeedScreen.kt", "FeedPreload.kt"]),
    dict(id="A-DETAIL", layer="L1", name="Android 详情页", summary="详情、选集、季、剧评、相关推荐、继续看。", files=["DetailScreen.kt"]),
    dict(id="A-PLAYER", layer="L1", name="Android 播放页", summary="起播、队列、预取、无缝切集、看门狗、历史、进度上报。", files=["PlayerScreen.kt"]),
    dict(id="A-CHROME", layer="L1", name="Android 播放控制条", summary="进度条、播放/暂停、上下集、倍速、清晰度、选集、弹幕设置。", files=["PlayerChrome.kt"]),
    dict(id="A-MISC", layer="L1", name="Android 其余页面", summary="找剧、排行、新剧、历史、收藏/点赞、追更、预约、我的、设置、演职员；新剧页视图/分类/日期/滚动存 UiMemo，返回不丢。", files=["FindScreens.kt", "RankScreens.kt", "NewScreens.kt", "RecordsScreens.kt", "FollowScreens.kt", "AccountScreens.kt", "SettingsScreen.kt", "CreatorScreens.kt"]),
    dict(id="A-SOCIAL", layer="L1", name="Android 评论/弹幕/打赏", summary="单集评论、剧评、弹幕窗口/发送、打赏码。", files=["CommentsPanel.kt", "Danmaku.kt", "DonateDialog.kt"]),
    dict(id="A-LOGIN", layer="L1", name="Android 登录/配对", summary="短信 MFA、账号 cookies、匹配码。", files=["Login.kt", "LoginScreen.kt: 账号区"]),

    # L2 domains
    dict(id="D-CATALOG", layer="L2", name="内容目录", summary="首页/找剧/排行/新剧/日历的数据编排与缓存；新剧翻页必须带 session_id（只传 offset 上游会重复第 1 页）。", files=["HomeFeed.kt / FeedCache.kt", "Api.landpage/tab/search/rank/new"]),
    dict(id="D-DETAIL", layer="L2", name="详情/选集/季", summary="详情、集数映射、季切换、相关推荐、剧评。", files=["DetailScreen.kt 两端", "Api.ensureDetail/seasons/reviews/related"]),
    dict(id="D-PLAY", layer="L2", name="播放编排", summary="选档、取流、预取、队列、无缝切集、看门狗、清晰度/倍速；**全 VVC 集先等转码 job 再选档**；**下一集未就绪不得预排后一集，防止播完跳过 N+1**。", files=["PlayerScreen.kt / Player.kt", "PlaybackSession.kt", "PlaybackSourcePlanner.kt", "PlaybackGuard.kt", "PlayerUiState.kt", "PlaybackPrefetch.kt", "FeedPreload.kt"]),
    dict(id="D-HISTORY", layer="L2", name="历史 / 续播", summary="记录真实播放、续播秒数、跨端合并、历史页。合并策略已收口到 HistoryRepository（PC/Android 共用）。", files=["HistoryStore.kt / PcHistoryStore.kt", "HistoryRepository.kt", "Api.historySync/reportProgress"]),
    dict(id="D-ACCOUNT", layer="L2", name="账号 / 书架", summary="登录、配对、收藏、点赞、追更、预约、云端状态。", files=["Login.kt / PcLogin.kt", "Api.shelf/follow/reserve/like/pair"]),
    dict(id="D-SOCIAL", layer="L2", name="评论 / 弹幕", summary="单集评论、剧评、回复、点赞、弹幕拉取/发送/渲染。", files=["CommentsPanel.kt", "Danmaku.kt", "Player.kt 弹幕层"]),
    dict(id="D-SETTINGS", layer="L2", name="设置 / 缓存", summary="播放设置、服务配置、缓存清理、关于/更新入口。", files=["SettingsScreen.kt（PC/Android）", "Store / PcStore"]),
    dict(id="D-UPDATE", layer="L2", name="更新", summary="检查清单、下载、sha256、安装/重启。", files=["Updater.kt 两端", "UpdateProvider.kt", "ListUpdate.kt"]),
    dict(id="D-LOG", layer="L2", name="客户端日志", summary="本地日志、脱敏、上传到 /api/clientlog。", files=["ClientLog.kt", "LogUploader.kt"]),

    # L3 core
    dict(id="C-API", layer="L3", name="API / 签名 / 缓存", summary="所有上游内容接口、服务器代取、取流、历史、书架、评论、弹幕、缓存。两份物理副本，修改必须同步。", files=["android/core/src/main/kotlin/com/hongguotv/pad/Api.kt", "src/main/kotlin/com/hongguotv/pad/Api.kt"]),
    dict(id="C-SIGN", layer="L3", name="本机签名", summary="生成上游要求的签名头/设备头（Argus/Gorgon/Helios/AesV3）。", files=["LocalSign.kt", "sign/Argus.kt", "sign/Gorgon.kt", "sign/Helios.kt"]),
    dict(id="C-MODEL", layer="L3", name="模型 / 小缓存", summary="SeriesCard/SeriesDetail/Episode/PlayTier；详情 LRU 60；榜单元数据兜底。", files=["Models.kt", "DetailCache.kt", "RankMetaFallback.kt"]),
    dict(id="C-MEDIA", layer="L3", name="CENC 解密", summary="解析 MP4 样本表、派生内容密钥、AES-CTR 解密。PC/Android 逐字一致。", files=["MediaCrypto.kt"]),
    dict(id="C-PROXY", layer="L3", name="直链代理", summary="拉 1MB 头部、准备样本表、本地 HTTP 代理、Range/短读处理。", files=["Playback.kt: RemoteVideo", "Playback.kt: StreamProxy"]),
    dict(id="C-STATE", layer="L3", name="全局契约", summary="loggedIn、cookies、maxHeight、playBases、historyBase、设备身份、历史接口。", files=["App.kt 两端"]),
    dict(id="C-UI", layer="L3", name="主题 / 组件", summary="颜色、字体、卡片、按钮、焦点、布局口径。", files=["Ui.kt", "Theme.kt", "UiMemo.kt"]),

    # L4 platform
    dict(id="X-PC-STORE", layer="L4", name="PC 本地库", summary="设置、账号、历史、收藏/点赞/追更/预约、缓存统计。", files=["PcStore.kt", "PcHistoryStore.kt", "PcNoticeStore.kt", "%APPDATA%/KaixinguoPC"]),
    dict(id="X-PC-ENGINE", layer="L4", name="PC 播放引擎", summary="启动/控制 ffmpeg 进程、探测、seek、音量、倍速、暂停。", files=["VideoEngine.kt"]),
    dict(id="X-PC-PREFETCH", layer="L4", name="PC 预取", summary="预热下一集直链/头部/代理，最多按设置 N 集；**刷剧 warmFeed 预载其他剧第一集**。", files=["PlaybackPrefetch.kt"]),
    dict(id="X-PC-UPD", layer="L4", name="PC 平台服务", summary="MSI 更新、日志上传、ffmpeg 路径解析。", files=["Updater.kt", "ClientLog.kt", "Resolver.kt"]),
    dict(id="X-AN-STORE", layer="L4", name="Android 本地库", summary="SharedPreferences：设置、账号、历史、书架、缓存清理。", files=["Store.kt", "ShelfStore.kt", "HistoryStore.kt", "hongguo_pad prefs"]),
    dict(id="X-AN-EXO", layer="L4", name="Android 播放引擎", summary="Media3 ExoPlayer 1.4.1 + 播放列表、硬解/软解、首帧、错误恢复。", files=["PlayerScreen.kt", "media3-exoplayer 1.4.1"]),
    dict(id="X-AN-GO", layer="L4", name="Android 本机取流", summary="gomobile Go 绑定，本机签名/取流兜底；LocalResolverProvider。", files=["DeviceResolver.kt", "libs/openhongguo.jar"]),
    dict(id="X-AN-CACHE", layer="L4", name="Android 缓存/预载", summary="刷剧首屏磁盘缓存 24h；后 5 部只拉详情、最近 2 部完整预载（回档 2.2.203 口径）。", files=["FeedCache.kt", "FeedPreload.kt"]),
    dict(id="X-AN-UPD", layer="L4", name="Android 平台服务", summary="APK 清单/ABI 选择/下载/sha/安装、日志上传。", files=["Updater.kt", "LogUploader.kt", "UpdateProvider.kt"]),
    dict(id="X-BUILD", layer="L4", name="构建 / 签名 / 参数", summary="版本号、私有 -P 参数、flavor、ABI 拆分、MSI、签名。", files=["build.gradle.kts 两端", "android/release.sh"]),

    # L5 server
    dict(id="V-DUAL", layer="L5", name="双协议网关", summary="/api/admin* 反代 8080（静态/看板），其余 /api/* 与 /sign 反代 8082（内容 API）；静态/官网；/get/* 302 到下载节点；**静态 Range 支持 keep-alive**（播放器连续 Range 不再每条重建 TLS）。", files=["/opt/hgtv-update/dual_server.py", "8081/TLS"]),
    dict(id="V-SERVE", layer="L5", name="静态 / 看板进程", summary="ROLE=static（8080）：静态 Range、/admin、/api/admin*；其余 /api/* 反代 V-API。看板重启不影响取流。", files=["/opt/hgtv-update/serve.py (ROLE=static)", "8080", "hgtv-update.service"]),
    dict(id="V-API", layer="L5", name="内容 API 进程", summary="ROLE=api（8082）：fetch/sign/playinfo/history/heat/rankmeta/seasons/flags/transcode；不提供静态和看板。", files=["/opt/hgtv-update/serve.py (ROLE=api)", "8082", "hgtv-api.service"]),
    dict(id="V-RELAY", layer="L5", name="上游代取/缓存", summary="代签、代拉、TTL/SWR/磁盘缓存、feed-cache。", files=["/opt/hgtv-update/relay.py"]),
    dict(id="V-PLAYINFO", layer="L5", name="取流中转", summary="30min 成功缓存、15s 负缓存、快速重试；202 pending + job 轮询（异步转码）。", files=["/opt/hgtv-update/playinfo.py"]),
    dict(id="V-HEAT", layer="L5", name="热度/榜单元数据", summary="热度 6h 缓存、榜单元数据 10min、缺热度回填。", files=["heat.py", "serve.py: chart/rankmeta"]),
    dict(id="V-HIST", layer="L5", name="历史/房间/日志", summary="历史合并、客户端日志、访问流水；Phase 12 已删除 /sync 与 room-*.json。", files=["history_store.py", "serve.py: /api/history (ROLE=api)", "serve.py: clientlog"]),
    dict(id="V-STATIC", layer="L5", name="静态/发布物", summary="APK/MSI、version-*.json、官网、/transcode/*、镜像清单。", files=["/srv/hgtv-update"]),
    dict(id="V-ADMIN", layer="L5", name="看板", summary="**三个 tab：运行数据 / 运行健康 / 架构世界树**；运行数据=设备/端口/请求量；运行健康=见 V-HEALTH；架构世界树=/api/admin/tree 读 /srv/hgtv-update/tech-tree.html。", files=["admin.py", "serve.py: /api/admin (ROLE=static)", "admin.token"]),

    # L6
    dict(id="R-STREAM", layer="L6", name="签名取流服务", summary="/play 缓存 25min/400 条；只走 oroplay；bvc2-only 返回 202 pending；/transcode 代理 job 查询。", files=["/opt/research/openhongguo/cmd/streamsrv", "9110"]),
    dict(id="R-ORO", layer="L6", name="真签名解析", summary="**v5 优先**：优先调 R-V5（纯 HTTP v5 签名）取全部清晰度，失败/空/超时回 R-ORACLE；**已转码的 vid 直接发 `/transcode/*_v4.mp4`（画质版），不再打上游；旧 `_v3` 只作转码失败兜底**；bytevc1（HEVC）直接透传，VVC/AV1 先要兼容 fallback（codec_type=3/1），最后才转码；返回前强制过滤 bytevc2；旧 video_tab 仅诊断兜底；转码 job 可查询（30min/200 条）。", files=["/opt/research/oroplay.js", "9118"]),
    dict(id="R-V5", layer="L6", name="v5 纯 HTTP 签名", summary="N3urda/aston314 GPL 实现：匿名设备注册 + Android v04.10.03 Medusa/Helios/Khronos v5 签名 + gzip mobile 模型；VVC/AV1 集用模型 fallback_api 的 codec_type=3/1 取 1080P HEVC/H.265；HTTP 9119，单设备、1.2s 间隔、空响应 90s 冷却。", files=["/opt/research/v5/v5resolver.js", "playback-v5-sign.js", "playback-v5-carrier.js", "mobile-playback-v5.js", "9119", "v5resolver.service"]),
    dict(id="R-ORACLE", layer="L6", name="Android 官方 App oracle", summary="redroid Android 13 容器 + 官方红果 App + Frida 同步 Java 调用；作为 R-ORO 的**兜底**，v5 失败时按 vid 取 1080p/720p 等全部档位，HTTP 9120，容器/systemd 开机自启。", files=["/opt/research/oracle_parent.py", "resolve_one.py", "oracle_resolver.js", "oracle.service", "redroid container", "9120"]),
    dict(id="R-SIGN", layer="L6", name="签名/设备池", summary="上游签名、设备身份注册/轮换、Argus/Ladon。", files=["9100/9101/9099", "playback-sign.js", "playback-device.js"]),
    dict(id="R-TRANSCODE", layer="L6", name="VVC/AV1 转码", summary="【解码=软解(CPU，VVC 无硬解、HEVC 解码也软解)；编码=核显 VAAPI h264_vaapi 硬编(/dev/dri/renderD128, CQP23, maxrate4.5M, profile high)，失败回退 libx264 veryfast CRF23】下载加密 MP4 → Java 解密 → H.264/HEVC/AV1 直接解 MP4（AV1 裸流会音频-only），VVC 才抽 Annex-B → ffmpeg H.264；**画质对齐同行：不放大、VAAPI CQP23（x264 veryfast CRF23 兜底）、AAC 音频 copy、maxrate 4.5Mbps、接近 16:9 归一到 1920，输出 _v4.mp4；磁盘剩 <8GB 自动删最旧**；容器标 av01 但 1 秒解码失败时按 VVC 重抽；输出必须 ffprobe 有视频轨，否则删除报错。", files=["do_transcode.py", "extract_vvc.py", "DecryptMp4.java"]),
    dict(id="R-LIVE", layer="L6", name="冷启动边转边播（已停用）", summary="**2026-11-21 已回滚，KXG_HLS=1 才启用**；没有 v4 缓存的 VVC 集：**do_live.py** 下载/解密后直接写 4 秒 fMP4 分片 + EVENT 播放列表（index.m3u8），客户端几秒起播；后台跑完把分片拼回 _v4.mp4 供下次秒开；用户换集会杀掉旧的边转进程，warm 转码会让路；分片目录 3 小时清理。", files=["do_live.py", "live/<vid>/index.m3u8"]),
    dict(id="U-CONTENT", layer="L6", name="番茄内容接口", summary="首页/详情/搜索/排行/日历/追更/评论/弹幕/历史/演职员。", files=["api5-normal-lq.fqnovel.com"]),
    dict(id="U-PLAY", layer="L6", name="番茄播放模型", summary="multi_video_model、fallback_api、video_meta/codec_type。", files=["multi_video_model/v1"]),
    dict(id="U-CDN", layer="L6", name="视频/图片 CDN", summary="加密 MP4、封面、头像。", files=["字节 CDN"]),
    dict(id="U-PASSPORT", layer="L6", name="登录/MFA", summary="短信登录、MFA、cookies。", files=["番茄 passport"]),
    dict(id="I-TUNNEL", layer="L6", name="公网通道", summary="Cloudflare（hgtv/kxg.fabao.xin→8080、tc.fabao.xin→8081）、樱花 frpc（仅 dl.fabao.xin:44468 真证书）；cpolar 已删、frp-bar 自签关。小请求走樱花(dl)；**转码视频交付按 R-TRANSROUTE 的健康路由在 dl/tc 间自动切换（迟滞防抖、默认 dl）**。", files=["frpc@…", "cloudflared"]),
    dict(id="I-GITHUB", layer="L6", name="GitHub Release 更新镜像", summary="公开 kaixinguo Release 资产；axisnow/gh-proxy 加速；固定 tag 地址（/srv/hgtv-update/github-release-tag，禁用会被 CDN 缓存的 latest/download）；下载线路 axisnow→gh-proxy→dl.fabao.xin(真证书兜底)，已去 frp-bar 自签/cpolar；客户端不再信任所有证书；PC/安卓/TV 3 段。", files=["github.com/Arkbiubiubiu/kaixinguo/releases", "axisnow.gh-proxy.org", "gh-proxy.org", "dl.fabao.xin:44468"]),
    dict(id="I-STATE", layer="L6", name="服务端状态", summary="历史、房间、feed-cache、heat、clientlogs、access 流水、admin token。", files=["/var/lib/hgtv-sync"]),
    dict(id="I-SYSTEMD", layer="L6", name="守护/备份", summary="systemd units、镜像 watchdog、隧道 watchdog、备份。", files=["systemd", "mirror_watchdog.sh"]),
    # ================= 架构树补全 2026-11-22：新增节点（覆盖各领域遗漏子系统）=================
    # -- L1 PC 页面补 --
    dict(id="P-SETTINGS", layer="L1", name="PC 设置页", summary="外观/播放(自动下一集/看完删除/最小化暂停/连播预取集数/清晰度上限/默认倍速)/磁盘清缓存/服务配置(取流/签名/token/历史基址/更新源/设备身份)/关于+检查更新。设置走 PcStore 可观察镜像，改完立即生效。", files=["SettingsScreen.kt:108 SettingsPage", "SettingsScreen.kt:400 ServiceField"]),
    dict(id="P-CREATOR", layer="L1", name="PC 演职员页", summary="演职员资料+作品列表；user_id 取真名，按 tab_type=11/29 双通道搜索兜底并过滤 0 集条目。", files=["CreatorPage.kt:63 CreatorScreen"]),
    # -- L1/L2 Android 补 --
    dict(id="A-INIT", layer="L1", name="Android 启动/初始化/权限/生命周期", summary="Store.init、注入 App 全局态、FeedCache.init、LogUploader.start、后台 DeviceResolver.init(Go取流)、后台 Api.fetchFlags；冷启动先画 LaunchScreen 首帧再组 TvRoot{App()}；onStart/onStop→AppLifecycle(退后台暂停)；Android13+ 运行期请求 POST_NOTIFICATIONS + 建「追更提醒」渠道 + 调度 FollowNotifyWorker(12h/联网/KEEP)。", files=["MainActivity.kt", "FollowNotifyWorker.kt:schedule", "App.kt"]),
    dict(id="A-TV-FOCUS", layer="L1", name="TV 焦点体系 / 遥控器键位", summary="TvRoot 整屏单点焦点框(TvFocusFrame)；侧栏 NavRail 锁+1dp 焦点跳板+返回语义(压栈出栈/进板块回导航层/顶层再退出)；播放器 tvKeys/feedKeys/媒体键 KEYCODE_MEDIA_*/数字键快进；Pad 不锁、无焦点框、触屏优先。", files=["Theme.kt:TvRoot/TvFocusFrame", "App.kt:NavRail/BackHandler", "PlayerScreen.kt:tvKeys/feedKeys"]),
    dict(id="A-MEMO", layer="L2", name="浏览状态记忆 UiMemo", summary="页面按需组合、切页销毁局部 state；UiMemo 单例保留刷剧现场(feedTab/cursor/cards/page/feedReturnKeep)、各列表滚动、首页/新剧/排行/历史/收藏/追更/预约的 tab·筛选·游标·已加载；RETURN_TTL_MS=5min 内返回不重拉。", files=["UiMemo.kt"]),
    dict(id="A-PLAY-SESSION", layer="L2", name="播放页状态分层", summary="UI 状态(PlayerUiState：控制条/浮层/提示/评论/输入) 与 单集会话(PlaybackSession：preparedIndex/tiers/curDef/duration/preparing/failed/attempt/switching) 分层；UI 只订阅状态。", files=["PlaybackSession.kt", "PlayerUiState.kt"]),
    dict(id="A-PLAY-SOURCE", layer="L2", name="选档 / 预取编排", summary="ReadyEpisode/buildReady/nextup：选档上限、拉头部+样本表+本地代理、按集号判重、N+1 未就绪即停(禁预排 N+2 防跳集)、dropReady 关代理；详情页预热前 2 集档位让服务端提前转码。", files=["PlaybackSourcePlanner.kt", "PlayerScreen.kt:buildReady"]),
    # -- L3 共享核心补（shared 单源）--
    dict(id="C-HIST", layer="L3", name="历史合并(单源)", summary="shared HistoryRepository：at 最新者胜、positionMs=0 不覆盖真实进度、试看保护(低集数+<60s 不顶高进度)、空 cover/title 不覆盖、同 seriesId 一条、LIMIT1500/SCHEMA3；只合并不存储，服务端 history_store.py 同规则；HistoryShadow PC 侧影子 diff。", files=["shared/src/main/kotlin/com/hongguotv/pad/HistoryRepository.kt", "shared/src/main/kotlin/com/hongguotv/pad/HistoryShadow.kt"]),
    dict(id="C-GUARD", layer="L3", name="播放保护/看门狗(单源)", summary="shared PlaybackGuard：PlaybackErrorPolicy 错误可恢复分类 + StallWatchdog 8s 卡顿最多2次；错误恢复与卡顿共用重试预算；平台只重建自己引擎。", files=["shared/src/main/kotlin/com/hongguotv/pad/PlaybackGuard.kt"]),
    dict(id="C-DOMAIN", layer="L3", name="领域门面", summary="shared ApiDomains facade(Phase5 strangler)：PlaybackApi/HistoryApi/AccountApi/SocialApi/CatalogApi 转调 Api；领域只依赖各 facade。", files=["shared/src/main/kotlin/com/hongguotv/pad/ApiDomains.kt"]),
    dict(id="C-SHELF", layer="L3", name="书架(单源)", summary="shared ShelfRepository：收藏/点赞/追更/预约统一(PC 19 / Android 10 引用)；云端权威+本地离线镜像+订阅基线。", files=["shared/src/main/kotlin/com/hongguotv/pad/ShelfRepository.kt"]),
    dict(id="C-SEASON", layer="L3", name="季服务(单源)", summary="shared SeasonService：季清单/补季/下一季/主创校验。", files=["shared/src/main/kotlin/com/hongguotv/pad/SeasonService.kt"]),
    dict(id="C-PASSPORT", layer="L3", name="Passport 登录协议(单源)", summary="shared PassportClient：短信/MFA/passport 协议/错误码(如 1003 设备不符)。", files=["shared/src/main/kotlin/com/hongguotv/pad/PassportClient.kt"]),
    dict(id="C-UPDCORE", layer="L3", name="更新清单核心(单源)", summary="shared UpdateManifest：一份 manifest schema(packages+abis) 供两端解析。", files=["shared/src/main/kotlin/com/hongguotv/pad/UpdateManifest.kt"]),
    dict(id="C-PARITY", layer="L3", name="双副本一致性守门", summary="tools/check_api_parity.py：两份 Api.kt(阈值340)/Playback.kt(阈值20)/sign 忽略空白 diff，超阈值报红；建议收紧+白名单(LocalResolverProvider/MULTI_URL/日志级别)。", files=["tools/check_api_parity.py"]),
    # -- L4 PC 平台补 --
    dict(id="X-PC-WINDOW", layer="L4", name="PC 窗口/DPI/单实例/生命周期", summary="原生标题栏 DWM 染色；单实例 app.lock(抢不到退出)；Density 固定 1f；竖屏/小窗/真全屏由 UiMode 改窗口几何；最小化→AppLifecycle 暂停、回前台不续播。", files=["Main.kt:246 main()", "Main.kt:281 Window", "AppLifecycle.kt:11"]),
    dict(id="X-PC-CACHE", layer="L4", name="PC 封面缓存/清理", summary="封面内存 LRU + 磁盘 %APPDATA%/KaixinguoPC/covers；启动后台 autoClean(30天/3000张)，设置页可清；RemoteImage 占位/回退，HEIC 经 ffmpeg 转 PNG。", files=["Image.kt:39 Covers", "Image.kt:60 autoClean"]),
    dict(id="X-PC-LOG", layer="L4", name="PC 日志落盘/回传", summary="Log.sink→println+DiagLog 落 app.log(>5MB 滚动)；ClientLog 每 10 分钟增量(≤256KB/次、失败静默)到 /api/clientlog，标准证书校验。", files=["Main.kt:694 DiagLog", "ClientLog.kt:18 start"]),
    dict(id="X-PC-RESOLVER", layer="L4", name="PC 本机取流服务", summary="随包 streamsrv.exe 只监听 127.0.0.1:38120；注册匿名设备取直链后作废；启动最多等 4s health；用户自填取流地址时不启动。", files=["Resolver.kt:16 Resolver"]),
    # -- L4 Android 平台补 --
    dict(id="X-AN-NOTIFY", layer="L4", name="追更/预约 系统通知", summary="CoroutineWorker 周期(12h/联网/KEEP)：后台拉起先补 Store.init+App 全局态，未登录 success；调 Store.checkFollowUpdates() 有新增发「追更提醒」；Android13+ 无权限跳过；静默不崩。", files=["FollowNotifyWorker.kt"]),
    dict(id="X-AN-NET", layer="L4", name="网络安全配置(明文收窄)", summary="res/xml/network_security_config.xml：默认只 HTTPS；仅 localhost/127.0.0.1(本机代理) 与 192.168.1.54(局域网签名/取流/更新) 允许明文；Manifest networkSecurityConfig 取代全局 usesCleartextTraffic。", files=["res/xml/network_security_config.xml", "AndroidManifest.xml"]),
    # -- L4 发布/工具链补 --
    dict(id="X-RELEASE", layer="L4", name="发布流水线", summary="tools/release.py 一条命令：bump→gradle 构建(tv/pad universal+3ABI、pc MSI)→verify_dex→stage→release_manifest 清单→scp+远端 sha 校验→prune_remote(留2版)→publish_github(gh release+写 github-release-tag)→copy_to_desktop。五参校验缺失即失败；私参不打印不入库。", files=["tools/release.py"]),
    dict(id="X-MANIFEST", layer="L4", name="发布清单生成", summary="tools/release_manifest.py 由 APK 目录生成 version-tv/hgpad.json：path/size/sha256 + abis(arm64-v8a/armeabi-v7a/x86_64 各带 path/size/sha256)。", files=["tools/release_manifest.py"]),
    dict(id="X-PRIVATE-ENV", layer="L4", name="私参文件 release.env", summary="本地 KEY=VALUE(D:/kaixinguo-backup/private/release.env，不入库)：SIGN_URL/PLAY_BASES/PLAY_TOKEN/UPDATE_BASES/HISTORY_BASE/DEVICE_*；release.py 读取注入构建，不打印不入库。", files=["release.env（本地·不入库）"]),
    dict(id="X-SIGN-KEYSTORE", layer="L4", name="签名 keystore", summary="android/debug.keystore(store/key=android, alias=androiddebugkey)；release 也用此签名保证老设备覆盖升级不被拒；PC 走 Compose MSI。", files=["android/debug.keystore"]),
    dict(id="X-DESKTOP-OUT", layer="L4", name="桌面留包目录「开心果apk」", summary="~/Desktop/开心果apk：copy_to_desktop 只放最新 TV/Pad 两个通用包、旧 APK 自动清理。", files=["Desktop/开心果apk"]),
    # -- L5/L6 服务端补 --
    dict(id="V-FLAGS", layer="L5", name="特性开关", summary="GET /api/flags 返回 flags.json(history_v2/resolver_v2/player_session_v2 等)。", files=["/opt/hgtv-update/serve.py:load_flags", "/var/lib/hgtv-sync/flags.json"]),
    dict(id="V-TRANSCODEAPI", layer="L5", name="转码 job 查询代理", summary="GET /api/transcode/status?id= → playinfo.job_status → streamsrv/oroplay transcode；只回 job 不回直链；完成后客户端重取 playinfo 命中缓存。", files=["/opt/hgtv-update/playinfo.py:job_status"]),
    dict(id="V-HEALTH", layer="L5", name="运行健康聚合 / 主动告警", summary="GET /api/admin/health 聚合健康信号：各 systemd 服务(8080/8082/8081/9118/9110/9119/9120/cloudflared/frpc@) is-active、监听端口、隧道 dl/tc(读 transcode-route.json 吞吐+新鲜度)、更新镜像 tag 一致性+新鲜、转码近1h新增/卡住 .part。返回 ok/warn/down items + alerts。/admin 看板「运行健康」独立 tab(与「运行数据」「架构世界树」并列):红/黄/绿灯+总览+告警清单，仅该 tab 激活时 30s 轮询。含「日志巡检」(V-LOGSCAN)。只读采集。", files=["/opt/hgtv-update/admin.py:_health_payload", "/api/admin/health"]),
    dict(id="V-LOGSCAN", layer="L5", name="日志巡检 / 定时找问题", summary="logscan.py 每30min(开机1次)扫描最近2h(LOGSCAN_HOURS 可配)：客户端 clientlogs(取流失败/超时/连接重置/Broken pipe/short read/异常崩溃/播放失败, 每文件tail2k行, 按设备计数, 样例≤3)、服务端 journal(oroplay/streamsrv/hgtv-api/v5resolver/oracle 的 ERROR/Traceback/timeout/502/503/fallback/empty/转码失败)、访问流水 access-*.jsonl(5xx)。规则分级 ok/warn/down 写 /srv/hgtv-update/logscan.json；/admin 运行健康 tab「日志巡检」区块随健康 30s 刷新。只读、免费。", files=["/opt/hgtv-update/logscan.py", "/srv/hgtv-update/logscan.json", "logscan.timer"]),
    dict(id="R-DEVICEPOOL", layer="L6", name="签名设备池", summary="匿名设备身份注册/轮换/持久化：playback-device.js + streamsrv PROFILE_DIR=/var/lib/hgtv-sync/ohg-pool(每 vid 取址用新身份、失败换新)。", files=["/opt/research/playback-device.js", "/var/lib/hgtv-sync/ohg-pool"]),
    dict(id="R-DECRYPT", layer="L6", name="CENC 解密(服务端)", summary="Java DecryptMp4：加密 MP4 + spade_a → 派生内容密钥 → AES-CTR 出 dec.mp4；与客户端 C-MEDIA 同算法。", files=["/opt/research/transcode/DecryptMp4.java"]),
    dict(id="R-EXTRACT", layer="L6", name="VVC 抽 Annex-B", summary="extract_vvc.py：解密 MP4 抽 video.266+audio.aac+meta(fps)；仅 VVC 需要；H.264/HEVC/AV1 直接喂 MP4。", files=["/opt/research/transcode/extract_vvc.py"]),
    dict(id="I-CF", layer="L6", name="Cloudflare 隧道", summary="cloudflared(cf-start.sh)+config.yml ingress：hgtv/kxg.fabao.xin→8080、tc.fabao.xin→8081；cf-watchdog 守护。", files=["/etc/cloudflared/config.yml"]),
    dict(id="I-FRP", layer="L6", name="樱花 frpc 隧道", summary="frpc@<instance>：只暴露 dl.fabao.xin:44468(真证书)，承接小请求/更新包/转码交付；frp-bar 自签已关、cpolar 已删。", files=["frpc@<instance>.service"]),
    dict(id="I-MIRROR", layer="L6", name="更新镜像发现", summary="mirror_watchdog 每~5min 写 update-mirrors.json(axisnow→gh-proxy→dl)，读 github-release-tag 固定 tag；只发现镜像、不改 manifest 的 path/sha/abis。", files=["/opt/hgtv-update/mirror_watchdog.sh", "/srv/hgtv-update/update-mirrors.json"]),
    dict(id="R-TRANSROUTE", layer="L6", name="转码交付双隧道健康路由", summary="transcode-route.py 每 2 分钟实测 dl(樱花) 与 tc(CF) 两条隧道吞吐(各 Range 2MB)，写 /srv/hgtv-update/transcode-route.json；迟滞防抖：默认 dl，当前 base 失败或 challenger>1.8x 才切，单次抖动不切、都坏回退 dl。oroplay/do_transcode/do_live 拼转码 url 时读 json.base(读不到回退 dl)。零客户端改动(两条都到同一 8081 origin)。", files=["/opt/hgtv-update/transcode-route.py", "/srv/hgtv-update/transcode-route.json", "transcode-route.timer"]),
    # -- L7 存储/安全/监控/运维 --
    dict(id="I-SECRETS", layer="L7", name="密钥/私参/Token", summary="不入库不进日志：CF DNS token、8081 自签私钥、看板 admin token(600,query)、服务器 X-Token(playinfo TOKEN+TOKEN_OLD 轮换)、客户端构建 -P 私参。", files=["playinfo.py:TOKEN/TOKEN_OLD", "/var/lib/hgtv-sync/admin.token"]),
    dict(id="I-TLS", layer="L7", name="证书管理", summary="dual_server 按 SNI 选证书：dl/kxg.fabao.xin 用 Let's Encrypt，其余用自签 kxg-selfsigned；公网 CF 边缘另配。", files=["/etc/letsencrypt/live/dl.fabao.xin"]),
    dict(id="I-MONITOR", layer="L7", name="监控/守护", summary="streamwatch(timer~30min)+stream-status.log 巡检流；cf-watchdog(timer~5min) 守护 cloudflared；看板 /api/admin/stats 汇总设备/端口/请求量。", files=["/usr/local/bin/streamwatch", "cf-watchdog.sh"]),
    dict(id="I-CLEANUP", layer="L7", name="定时清理", summary="ark-cleanup(timer 每日04:33) 清 apt/docker/journal/tmp；转码侧 7天/200文件 + 磁盘<8GB 删最旧。", files=["/usr/local/bin/ark-cleanup.sh"]),
    dict(id="I-BACKUP", layer="L7", name="备份/回滚", summary="改前快照 /root/kxg-unify-backup-YYYYMMDD(源文件+二进制)；/var/lib/hgtv-sync 与 /srv/hgtv-update 可整体快照。", files=["/root/kxg-unify-backup-20261121"]),
    dict(id="I-ARCHIVE", layer="L7", name="归档/数据盘", summary="srv-hgtv-archive.mount 归档盘；data-redroid.mount 为 oracle redroid 容器数据盘；主盘 nvme 承载 /srv 与 /var/lib/hgtv-sync。", files=["srv-hgtv-archive.mount"]),
    # -- L7 工具链/发版保障 --
    dict(id="X-GATE-VERSIONS", layer="L7", name="版本一致性门禁", summary="tools/check_versions.py：源码↔服务器清单↔本机安装；verify_artifacts 校验 abis≥3、manifest sha256 与本地 dist 一致。", files=["tools/check_versions.py"]),
    dict(id="X-TREE", layer="L7", name="架构树生成/校验", summary="docs/build_tech_tree.py 由节点/边生成 tech-tree.html；tools/check_tree.py 校验 TECH_TREE.md 节点 id↔html tree-data↔引用文件存在；改完须重跑并 scp 到 /srv/hgtv-update/tech-tree.html。", files=["docs/build_tech_tree.py", "tools/check_tree.py"]),
    dict(id="X-DOC-DISCIPLINE", layer="L7", name="文档与发版纪律", summary="【维护铁律】每次改动软件（任何模块/参数/流程）必须同步更新本树对应节点(改 build_tech_tree.py 的 NODES/EDGES 摘要)，随后重跑 docs/build_tech_tree.py + tools/check_tree.py，并 scp tech-tree.html 到 /srv/hgtv-update/，保证树永远等于最新真相。另：AGENTS.md(发版纪律)、docs/TECH_TREE.md(权威清单·§9 版本基线·全量节点索引)、README、REMEDIATION_PLAN；改发版参数/版本须同步 §9。", files=["AGENTS.md", "docs/TECH_TREE.md"]),
]

EDGES = [
    # surfaces
    ("S-PC","P-SHELL","启动"), ("S-PAD","A-SHELL","启动"), ("S-TV","A-SHELL","启动"),
    ("S-WEB","V-STATIC","读清单/下载"), ("S-WEB","I-GITHUB","官网 /get 302"), ("S-ADMIN","V-ADMIN","打开看板"),

    # PC UI
    ("P-SHELL","P-HOME","页面"), ("P-SHELL","P-FIND","页面"), ("P-SHELL","P-RANK","页面"),
    ("P-SHELL","P-NEW","页面"), ("P-SHELL","P-HISTORY","页面"), ("P-SHELL","P-LIBRARY","页面"),
    ("P-SHELL","P-DETAIL","路由"), ("P-SHELL","P-PLAYER","路由"), ("P-SHELL","P-FEED","页面"),
    ("P-SHELL","P-LOGIN","路由"), ("P-SHELL","P-DONATE","浮层"),
    ("P-HOME","D-CATALOG","列表/推荐"), ("P-HOME","D-DETAIL","打开详情"), ("P-HOME","D-PLAY","继续看"),
    ("P-FIND","D-CATALOG","搜索"), ("P-RANK","D-CATALOG","榜单"), ("P-NEW","D-CATALOG","新剧"),
    ("P-HISTORY","D-HISTORY","历史"), ("P-HISTORY","D-ACCOUNT","云端"),
    ("P-LIBRARY","D-ACCOUNT","书架"), ("P-LIBRARY","D-HISTORY","继续看"),
    ("P-DETAIL","D-DETAIL","详情"), ("P-DETAIL","D-PLAY","起播"),
    ("P-PLAYER","D-PLAY","播放"), ("P-PLAYER","D-HISTORY","进度"), ("P-PLAYER","D-SOCIAL","弹幕/评论"),
    ("P-FEED","D-CATALOG","换剧"), ("P-FEED","D-PLAY","连播"),
    ("P-LOGIN","D-ACCOUNT","登录/配对"), ("P-LOGIN","C-API","passport"),
    ("P-DONATE","C-UI","码资源"),

    # Android UI
    ("A-SHELL","A-HOME","页面"), ("A-SHELL","A-FEED","页面"), ("A-SHELL","A-DETAIL","路由"),
    ("A-SHELL","A-PLAYER","路由"), ("A-SHELL","A-MISC","页面"), ("A-SHELL","A-LOGIN","路由"),
    ("A-HOME","D-CATALOG","列表/推荐"), ("A-HOME","D-DETAIL","打开详情"), ("A-HOME","D-PLAY","继续看"),
    ("A-FEED","D-CATALOG","换剧"), ("A-FEED","D-PLAY","连播"), ("A-FEED","X-AN-CACHE","预载"),
    ("A-DETAIL","D-DETAIL","详情"), ("A-DETAIL","D-PLAY","起播"),
    ("A-PLAYER","D-PLAY","播放"), ("A-PLAYER","D-HISTORY","进度"), ("A-PLAYER","D-SOCIAL","弹幕/评论"),
    ("A-PLAYER","X-AN-EXO","解码/播放"), ("A-CHROME","D-PLAY","控制"), ("A-CHROME","D-SOCIAL","弹幕设置"),
    ("A-MISC","D-CATALOG","找剧/排行/新剧"), ("A-MISC","D-HISTORY","历史"), ("A-MISC","D-ACCOUNT","书架"), ("A-MISC","D-SETTINGS","设置"),
    ("A-SOCIAL","D-SOCIAL","评论/弹幕"), ("A-LOGIN","D-ACCOUNT","登录/配对"), ("A-LOGIN","C-API","passport"),

    # domains
    ("D-CATALOG","C-API","内容接口"), ("D-CATALOG","C-MODEL","卡片模型"), ("D-CATALOG","X-AN-CACHE","刷剧缓存"),
    ("D-DETAIL","C-API","详情/季/剧评"), ("D-DETAIL","C-MODEL","详情模型"), ("D-DETAIL","D-HISTORY","续播"),
    ("D-PLAY","C-API","playinfo/档位"), ("D-PLAY","C-PROXY","直链代理"), ("D-PLAY","C-MEDIA","解密"),
    ("D-PLAY","X-PC-ENGINE","PC 播放"), ("D-PLAY","X-AN-EXO","Android 播放"),
    ("D-PLAY","X-PC-PREFETCH","PC 预取"), ("D-PLAY","X-AN-GO","本机取流"), ("D-PLAY","D-HISTORY","进度"),
    ("D-HISTORY","X-PC-STORE","PC 历史"), ("D-HISTORY","X-AN-STORE","Android 历史"),
    ("D-HISTORY","C-API","同步/上报"), ("D-HISTORY","V-HIST","合并"),
    ("D-ACCOUNT","C-API","书架/追更/预约"), ("D-ACCOUNT","C-SIGN","签名"), ("D-ACCOUNT","X-PC-STORE","账号/书架"), ("D-ACCOUNT","X-AN-STORE","账号/书架"),
    ("D-SOCIAL","C-API","评论/弹幕"),
    ("D-SETTINGS","X-PC-STORE","设置"), ("D-SETTINGS","X-AN-STORE","设置"), ("D-SETTINGS","C-STATE","服务配置"), ("D-SETTINGS","D-UPDATE","更新入口"),
    ("D-UPDATE","V-STATIC","清单/包"), ("D-UPDATE","I-GITHUB","GitHub 镜像/加速"), ("D-UPDATE","X-PC-UPD","MSI"), ("D-UPDATE","X-AN-UPD","APK"),
    ("D-LOG","V-SERVE","/api/clientlog"), ("D-LOG","X-PC-UPD","PC 上传"), ("D-LOG","X-AN-UPD","Android 上传"),

    # core
    ("C-API","C-SIGN","签名"), ("C-API","V-SERVE","/api/fetch, history"), ("C-API","V-PLAYINFO","/api/playinfo"), ("C-API","U-CONTENT","直连兜底"),
    ("C-PROXY","C-MEDIA","解密"), ("C-PROXY","U-CDN","下载"),
    ("C-SIGN","U-PASSPORT","登录签名"),
    ("C-MODEL","C-API","解析结果"),
    ("P-SHELL","C-STATE","全局状态"), ("A-SHELL","C-STATE","全局状态"),
    ("P-SHELL","C-UI","主题组件"), ("A-SHELL","C-UI","主题组件"),

    # platform
    ("X-PC-PREFETCH","C-API","档位"), ("X-PC-PREFETCH","C-PROXY","头部"),
    ("X-PC-UPD","V-STATIC","清单/包"), ("X-PC-UPD","I-GITHUB","镜像"), ("X-PC-UPD","V-SERVE","日志"),
    ("X-AN-EXO","C-PROXY","本地流"),
    ("X-AN-GO","U-PLAY","本机解析"),
    ("X-AN-UPD","V-STATIC","清单/包"), ("X-AN-UPD","I-GITHUB","镜像"), ("X-AN-UPD","V-SERVE","日志"),
    ("X-BUILD","V-STATIC","发布"), ("X-BUILD","I-GITHUB","上传 Release"),

    # server
    ("V-DUAL","V-SERVE","看板/admin"), ("V-DUAL","V-API","反代 /api"), ("V-DUAL","V-STATIC","静态/下载"),
    ("V-SERVE","V-API","反代 /api"),
    ("V-API","V-RELAY","/api/fetch"), ("V-API","V-PLAYINFO","/api/playinfo"), ("V-API","V-HEAT","热度/榜单"),
    ("V-API","V-HIST","历史/日志"), ("V-SERVE","V-STATIC","静态"), ("V-SERVE","V-ADMIN","看板"),
    ("V-RELAY","R-SIGN","代签"), ("V-RELAY","U-CONTENT","代拉"),
    ("V-PLAYINFO","R-STREAM","/play"),
    ("V-HEAT","U-CONTENT","热度"), ("V-HEAT","I-STATE","缓存"),
    ("V-HIST","I-STATE","历史/房间/日志"),
    ("V-STATIC","I-STATE","发布物"),
    ("V-ADMIN","I-STATE","统计"), ("V-ADMIN","V-STATIC","读取架构树"),

    # resolvers / upstream
    ("R-STREAM","R-ORO","优先"), ("R-STREAM","R-SIGN","SDK 兜底"), ("R-STREAM","U-PLAY","档位"),
    ("R-ORO","R-V5","主路径：纯 HTTP v5 签名"), ("R-ORO","R-ORACLE","兜底：v5 失败/空响应"), ("R-ORO","R-SIGN","兜底：签名/设备"), ("R-ORO","R-TRANSCODE","兼容 fallback 失败才转码"), ("R-ORO","U-PLAY","主/兼容接口"), ("R-ORO","U-CDN","下载源"),
    ("R-V5","U-PLAY","mobile 模型 + fallback_api"),
    ("R-ORACLE","U-PLAY","官方 App 取模型"), ("R-ORACLE","U-CDN","1080p 直链"),
    ("R-SIGN","U-PASSPORT","设备/账号"),
    ("R-TRANSCODE","C-MEDIA","同一解密算法副本"), ("R-TRANSCODE","U-CDN","下载加密 MP4"), ("R-TRANSCODE","V-STATIC","输出 v4.mp4"),
    ("R-ORO","R-LIVE","冷启动边转边播"), ("R-LIVE","C-MEDIA","解密算法副本"), ("R-LIVE","U-CDN","下载加密 MP4"), ("R-LIVE","V-STATIC","HLS 分片 + 回写 v4"),
    ("I-TUNNEL","V-DUAL","公网入口"),
    ("I-SYSTEMD","V-SERVE","守护"), ("I-SYSTEMD","V-API","守护"), ("I-SYSTEMD","V-DUAL","守护"), ("I-SYSTEMD","R-STREAM","守护"), ("I-SYSTEMD","R-ORO","守护"), ("I-SYSTEMD","R-V5","守护"), ("I-SYSTEMD","R-ORACLE","守护"),
    # 转码交付双隧道健康路由
    ("R-TRANSROUTE","R-ORO","提供 url base"), ("R-TRANSROUTE","R-TRANSCODE","提供 url base"), ("R-TRANSROUTE","I-CF","测 tc 隧道"), ("R-TRANSROUTE","I-FRP","测 dl 隧道"), ("I-SYSTEMD","R-TRANSROUTE","timer 每2min"),
    # 运行健康/告警
    ("V-ADMIN","V-HEALTH","运行健康区块"), ("V-SERVE","V-HEALTH","/api/admin/health 路由"), ("V-HEALTH","R-TRANSROUTE","读隧道健康"), ("V-HEALTH","I-SYSTEMD","读服务状态"), ("V-HEALTH","I-MIRROR","读镜像一致性"), ("V-HEALTH","V-LOGSCAN","日志巡检汇总"),
    # 日志巡检
    ("V-LOGSCAN","I-STATE","扫clientlogs/access"), ("V-LOGSCAN","V-API","扫服务日志/hgtv-api journal"), ("V-LOGSCAN","V-STATIC","写logscan.json"), ("I-SYSTEMD","V-LOGSCAN","timer 30min"),
    # ===== 架构树补全 2026-11-22：新增边 =====
    # 发布流水线/工具链
    ("X-RELEASE","X-BUILD","构建/bump版本"), ("X-RELEASE","X-PRIVATE-ENV","读私参"), ("X-RELEASE","X-MANIFEST","生成清单"),
    ("X-RELEASE","V-STATIC","上传+远端sha+prune"), ("X-RELEASE","I-GITHUB","publish 上传资产"), ("X-RELEASE","I-MIRROR","写 github-release-tag"),
    ("X-RELEASE","X-DESKTOP-OUT","桌面留包"), ("X-BUILD","X-SIGN-KEYSTORE","签名配置"),
    ("X-GATE-VERSIONS","X-BUILD","读源码版本"), ("X-GATE-VERSIONS","V-STATIC","比对清单/sha/abi"), ("X-GATE-VERSIONS","S-PC","比对本机安装"),
    ("C-PARITY","C-API","diff 守门"), ("C-PARITY","C-PROXY","diff 守门"), ("C-PARITY","C-SIGN","sign diff 守门"), ("X-BUILD","C-PARITY","发版前校验"),
    ("X-TREE","X-DOC-DISCIPLINE","校验节点/文档"), ("X-TREE","V-ADMIN","tech-tree→看板"),
    ("I-MIRROR","I-GITHUB","镜像/tag"), ("I-MIRROR","V-STATIC","读tag/写update-mirrors"), ("D-UPDATE","I-MIRROR","发现 update-mirrors"),
    # PC 页面/平台
    ("P-SHELL","P-SETTINGS","页面"), ("P-SHELL","P-CREATOR","路由"), ("P-SHELL","X-PC-WINDOW","窗口/DPI/单实例"),
    ("P-SHELL","X-PC-LOG","启动日志"), ("P-SHELL","X-PC-RESOLVER","启动本机取流"),
    ("P-SETTINGS","D-SETTINGS","设置"), ("P-SETTINGS","D-UPDATE","检查更新"), ("P-SETTINGS","X-PC-STORE","持久化"),
    ("P-SETTINGS","X-PC-CACHE","清缓存"), ("P-SETTINGS","C-STATE","applyServiceConfig"),
    ("P-CREATOR","D-CATALOG","演职员作品"), ("P-CREATOR","C-API","资料/作品"),
    ("P-DETAIL","D-HISTORY","继续看"), ("P-DETAIL","C-API","前2集档位预热"),
    ("P-PLAYER","X-PC-ENGINE","ffmpeg 解码"), ("P-PLAYER","X-PC-PREFETCH","预取接管"), ("P-PLAYER","X-PC-STORE","续播/设置"),
    ("P-PLAYER","X-PC-LOG","播放日志"), ("P-PLAYER","X-PC-WINDOW","最小化暂停"),
    ("P-FEED","P-PLAYER","embedded 复用"), ("P-FEED","D-DETAIL","下一部详情预热"), ("P-FEED","C-API","取池/档位预热"),
    ("P-HISTORY","X-PC-STORE","本地历史"), ("P-LIBRARY","X-PC-STORE","书架本地"), ("P-LOGIN","X-PC-STORE","账号持久化"), ("P-LOGIN","C-SIGN","passport 签名"),
    ("X-PC-ENGINE","C-PROXY","本地流"), ("X-PC-RESOLVER","C-STATE","playBase/token"), ("X-PC-CACHE","U-CDN","封面下载"), ("X-PC-LOG","V-SERVE","/api/clientlog"),
    # Android 启动/焦点/领域
    ("S-PAD","A-INIT","启动"), ("S-TV","A-INIT","启动"), ("A-INIT","A-SHELL","组合 App()"), ("A-INIT","X-AN-STORE","Store.init"),
    ("A-INIT","X-AN-GO","DeviceResolver.init"), ("A-INIT","X-AN-UPD","LogUploader.start"), ("A-INIT","C-API","fetchFlags"),
    ("A-INIT","X-AN-NOTIFY","建渠道+调度"), ("A-INIT","X-AN-NET","装载网络配置"),
    ("A-SHELL","A-TV-FOCUS","焦点/返回/遥控器"), ("A-SHELL","A-MEMO","状态记忆"),
    ("A-FEED","X-AN-CACHE","预载/缓存"), ("A-FEED","A-MEMO","刷剧现场"),
    ("A-DETAIL","A-PLAY-SOURCE","预热前2集"), ("A-DETAIL","A-MEMO","详情滚动/页签"),
    ("A-PLAYER","A-PLAY-SESSION","状态分层"), ("A-PLAYER","A-PLAY-SOURCE","选档/预取"), ("A-PLAYER","A-CHROME","控制条"), ("A-PLAYER","X-AN-NET","本机代理明文"),
    ("A-PLAY-SESSION","A-CHROME","读状态"), ("A-PLAY-SOURCE","C-API","playinfo/档位"), ("A-PLAY-SOURCE","C-PROXY","头部/本地代理"),
    ("X-AN-NOTIFY","D-ACCOUNT","追更更新数"), ("X-AN-NOTIFY","X-AN-STORE","基线fb_*"), ("X-AN-NOTIFY","C-API","followUpdates"),
    ("X-AN-NET","C-PROXY","放行localhost明文"), ("X-AN-NET","C-API","放行192.168.1.54明文"),
    ("A-MISC","A-MEMO","列表返回现场"), ("A-LOGIN","A-TV-FOCUS","TV配对码前置"),
    # 共享核心
    ("D-HISTORY","C-HIST","合并策略"), ("X-PC-STORE","C-HIST","merge"), ("X-AN-STORE","C-HIST","merge"), ("C-HIST","V-HIST","合并规则同源"),
    ("D-PLAY","C-GUARD","看门狗/错误分类"), ("C-GUARD","X-PC-ENGINE","重试重建"), ("C-GUARD","X-AN-EXO","重试重建"),
    ("D-CATALOG","C-DOMAIN","CatalogApi"), ("D-PLAY","C-DOMAIN","PlaybackApi"), ("D-HISTORY","C-DOMAIN","HistoryApi"), ("D-ACCOUNT","C-DOMAIN","AccountApi"), ("D-SOCIAL","C-DOMAIN","SocialApi"), ("C-DOMAIN","C-API","转调"),
    ("D-ACCOUNT","C-SHELF","收藏/追更/预约"), ("X-PC-STORE","C-SHELF","书架"), ("X-AN-STORE","C-SHELF","书架"),
    ("D-DETAIL","C-SEASON","季"), ("D-ACCOUNT","C-PASSPORT","登录协议"), ("D-UPDATE","C-UPDCORE","manifest schema"),
    # 服务端/隧道/上游/运维
    ("V-API","V-FLAGS","/api/flags"), ("V-API","V-TRANSCODEAPI","/api/transcode/status"), ("V-TRANSCODEAPI","R-STREAM","转发job查询"), ("R-STREAM","R-ORO","/transcode代理"), ("V-FLAGS","I-STATE","flags.json"),
    ("R-SIGN","R-DEVICEPOOL","设备身份"), ("R-STREAM","R-DEVICEPOOL","PROFILE_DIR设备池"),
    ("R-TRANSCODE","R-DECRYPT","解密"), ("R-DECRYPT","C-MEDIA","同一算法"), ("R-TRANSCODE","R-EXTRACT","VVC抽Annex-B"),
    ("I-TUNNEL","I-CF","CF线路"), ("I-TUNNEL","I-FRP","樱花线路"), ("I-CF","V-DUAL","tc→8081"), ("I-CF","V-SERVE","hgtv/kxg→8080"), ("I-FRP","V-DUAL","dl→8081"), ("I-FRP","V-STATIC","更新包/转码交付"),
    ("I-MONITOR","V-STATIC","stream-status.log"), ("I-MONITOR","V-ADMIN","指标来源"), ("I-CLEANUP","V-STATIC","清转码/旧包"), ("I-CLEANUP","I-STATE","清临时/日志"),
    ("I-BACKUP","I-STATE","状态快照"), ("I-BACKUP","V-STATIC","发布物备份"),
    ("I-SECRETS","V-DUAL","X-Token/私钥"), ("I-SECRETS","V-API","X-Token校验"), ("I-SECRETS","V-ADMIN","admin.token"), ("I-SECRETS","R-SIGN","签名设备私参"), ("I-SECRETS","X-BUILD","构建期-P"),
    ("I-TLS","V-DUAL","SNI证书"), ("I-ARCHIVE","V-STATIC","归档"),
    ("I-SYSTEMD","I-CF","守护cloudflared"), ("I-SYSTEMD","I-FRP","守护frpc"), ("I-SYSTEMD","I-MIRROR","守护mirror"), ("I-SYSTEMD","I-MONITOR","streamwatch/cf-watchdog"), ("I-SYSTEMD","I-CLEANUP","ark-cleanup定时"),
]

PLAN = [
  dict(phase="0", title="观测与安全网", size="S", risk="低",
       why="没有 requestId、功能开关、黄金用例和回滚手段，后面任何重构都无法证明“没坏”。这是所有阶段的前置条件。",
       items=["客户端→/api/fetch/playinfo→streamsrv→oroplay→转码 全链路带 requestId",
              "看板增加：播放失败阶段、resolver fallback 次数、转码队列、历史合并 diff、缓存命中率",
              "加 feature flag：history_v2 / resolver_v2 / player_session_v2，可按设备/版本开关",
              "建立黄金用例：播放矩阵、历史矩阵、解析 fixture、更新 manifest 校验",
              "服务端状态备份/恢复脚本：/var/lib/hgtv-sync、转码产物、manifest"],
       affects=["日志量、看板性能；不改产品行为"],
       guard=["日志脱敏，不写 cookie/token","采样+保留期，避免磁盘爆","先在 LDPlayer 验证，再上看板"],
       rollback="关闭新增日志/面板；功能开关全部保持旧路径"),
  dict(phase="1", title="零行为死代码清理", size="S", risk="低",
       why="先删确定无调用的东西，缩小后面重构的噪声和安全面；这一步不应该改变任何用户可见行为。",
       items=["删除 android/src/main/java/.../HomeFeed.kt（未编译的 395 行旧副本）",
              "删除 RemoteVideo.saveDecrypted 和 prepare(allowVvc)（VVC 本地播放器已删）",
              "删除 playinfo.py 旧直连整段：_attempt/_pick/_pick_all/_sign/_decrypt_url 及外部签名配置",
              "删除 oroplay 的 force_fallback 生产分支（或只保留 env 调试）",
              "清理 PC FileDownload 等无用 import"],
       affects=["只影响构建和死代码；若误删会在编译期暴露"],
       guard=["grep 全仓调用点","PC/Android 双端编译","LDPlayer 冒烟：首页/详情/播放/历史/更新"],
       rollback="git revert 单个提交；不涉及数据迁移"),
  dict(phase="2", title="缓存/状态版本化", size="M", risk="中",
       why="后面要改历史字段和取流响应；如果缓存没有 schema 版本，旧结构会继续命中，表现成“代码改了线上还是旧的”。",
       items=["relay/playinfo/streamsrv/tierCache/DetailCache/FeedCache/heat 的 key 加 schemaVersion",
              "每层明确 TTL 和失效 owner；部署时 bump 版本或清对应层",
              "history/progress/room JSON 加 version 字段；写迁移脚本，保留一版旧 reader",
              "看板展示各层缓存命中率与版本分布"],
       affects=["所有内容/播放/历史；发布后会有一次冷缓存，上游请求短时上升"],
       guard=["双读/双写一版；旧 reader 保留","预热脚本；限流观察","回滚只需恢复旧 key/旧 reader"],
       rollback="恢复旧版本 key/reader；缓存可丢弃重建"),
  dict(phase="3", title="历史 owner 统一", size="M", risk="高",
       why="这是用户最痛、最容易反复坏的地方；而且历史逻辑长在播放器里，先抽出 HistoryReporter 也能直接降低播放器复杂度。",
       items=["从 Player/PlayerScreen 抽 HistoryReporter（只负责 3 秒门槛/上报/合并调用）",
              "建 HistoryRepository：本地缓存、服务端一份合并、官方 read_history 只做 adapter",
              "PC 历史页改为走同一套 HistoryRepository，不再直读 officialHistory",
              "服务端 history_merge 独立成模块；先 shadow 计算新旧结果，diff 为 0 再切",
              "旧 /sync 暂不删，只停止新写入；Phase 12 再清理"],
       affects=["历史页、继续看、详情/首页/追更/排行/新剧入口、官方同步、PC/Android"],
       guard=["历史矩阵：真播3秒/1秒退出/低集数/续播秒数/跨端/旧数据","shadow diff 日志","feature flag + 历史文件备份","先 LDPlayer，再一台用户授权设备"],
       rollback="关 history_v2，回旧合并/旧读路径；需要时恢复历史 JSON 备份"),
  dict(phase="4", title="播放器分层", size="L", risk="高",
       why="播放器是最大 god node；历史 reporter 已在 Phase 3 抽出，这里继续拆预取、会话和 UI 状态，才能避免以后改播放/历史互相踩。",
       items=["抽 PrefetchController / SourcePlanner（选档、头部、失效、预取并发）",
              "抽 PlaybackSession（源、引擎、错误分类、看门狗、切集）",
              "抽 PlayerUiState（控制条/浮层只订阅状态）",
              "拆 PlayerScreen.kt / Player.kt；控制条视觉和键位保持不变",
              "播放器只通过接口调用 HistoryReporter/Resolver，不直接读写 Store/Api"],
       affects=["播放、预取、队列、切集、历史、弹幕/评论、TV 焦点、错误提示"],
       guard=["播放矩阵：普通/VVC/兼容接口/转码/证书/404/seek/倍速/自动连播","同一套控制条截图对比","canary + feature flag","关键路径日志 requestId"],
       rollback="关 player_session_v2，回旧 PlayerScreen/Player；不移民数据"),
  dict(phase="5", title="C-API 分域 + 共享核心", size="L", risk="高",
       why="先分域再加共享模块，避免把 3741 行的 god object 整体搬两次；分域后 PC/Android 的契约测试才有落点。",
       items=["Api.kt 按 HttpCore/Catalog/Detail/Playback/History/Account/Social 拆文件，保留 Api facade",
              "所有调用方先继续用 facade，逐个切到领域 API",
              "建立 PC/Android 契约 fixture 和 diff 检查（只允许三处平台差异）",
              "分域稳定后抽 KMP/独立 jar；平台只保留取流兜底/设备默认值",
              "排行榜/搜索/详情解析的旧重复代码顺手收口"],
       affects=["所有页面和领域；构建结构变化；解析字段风险"],
       guard=["facade 保持调用点不变","解析 fixture 黄金对比","PC/Android 双端构建+冒烟","先 Android 后 PC 或反之，单端 canary"],
       rollback="保留旧 Api object 转调新实现；构建失败直接 revert 模块化提交"),
  dict(phase="6", title="取流 Resolver 收敛", size="L", risk="高",
       why="播放链路是第二大可感知风险；前面播放器已分层、缓存已版本化，才能安全改契约、超时和转码队列。",
       items=["删/闸掉 streamsrv SDK 兜底和 PC localPlay 死路；失败快速返回",
              "统一 Resolver 契约：tier 列表、错误码、超时预算（客户端/streamsrv/转码一致）",
              "转码改异步 job + 轮询/回调；当前播放优先于预取，队列可见",
              "拆 streamsrv 为 resolver/cache/http；拆 oroplay 为 identity/tiers/transcode-queue/server",
              "客户端 SourcePlanner 与 Resolver 契约对齐"],
       affects=["所有播放、VVC/兼容/转码、预取、失败提示、卡顿重试"],
       guard=["播放矩阵加：1080转码/队列满/上游502/超时/证书/404","记录每段耗时和 fallback 原因","旧 resolver 二进制/配置保留一版","canary"],
       rollback="切回旧 resolver/旧服务二进制；关闭异步转码开关；恢复旧缓存"),
  dict(phase="7", title="服务端控制面拆分", size="M", risk="中",
       why="历史/取流接口刚稳定，适合把静态、内容、历史、看板拆开；再晚会把已稳定的代码又搬一次。",
       items=["serve.py 拆 static_update / content_api / library_api / telemetry_admin",
              "admin + 世界树独立进程/模块，重启不影响历史/取流",
              "统一 router：/admin 与 /api/admin 只保留一个 canonical；静态 handler 与 dual_server 共享",
              "admin token 改 cookie/短时票据（可选，先保留 query 兼容）"],
       affects=["看板、历史、fetch/playinfo、静态/更新、路由"],
       guard=["每个路由的 contract test","zero-downtime 重启/回滚","8080/8081 双入口 smoke","访问日志对照"],
       rollback="systemd 切回旧 serve.py；路由前缀保留兼容"),
  dict(phase="8", title="领域合并", size="M", risk="中",
       why="C-API 已分域，才能把收藏/点赞/追更/预约、季发现、登录协议合并成领域服务，而不是在大对象里继续加方法。",
       items=["ShelfRepository + ShelfKind：收藏/点赞/追更/预约共用本地缓存和行模型",
              "SeasonService：seasons/nextSeason/fillMissingSeasons 共用缓存和主创校验",
              "PassportClient：PC/Android 共用短信/MFA/错误码，UI 各自保留",
              "旧 Api 方法保留一版转调，逐步删"],
       affects=["书架/追更/预约、详情季切换、登录/配对、未登录态"],
       guard=["登录/未登录 fixture","季边界：缺季/改名续集/未上线/下架","配对码测试","旧方法转调对比"],
       rollback="领域服务内部转调旧 Api；关闭新实现开关"),
  dict(phase="9", title="更新链统一", size="M", risk="中",
       why="更新链相对独立，可在核心稳定后并行；manifest/镜像/版本号是发布期最容易出错的部分，必须收成一条流水线。",
       items=["UpdaterCore：manifest 拉取/镜像/sha256/重试共享；PC MSI/Android ABI 只留安装 adapter",
              "一份 manifest schema（packages + abis）；PC/Android 清单同源生成",
              "发布流水线：版本号→构建→sha/ABI→manifest→官网→看板",
              "mirror_watchdog 只发现镜像，不再改 manifest 的 url；旧字段保留一版兼容"],
       affects=["三端更新、官网下载、旧客户端救急 URL、发布流程"],
       guard=["manifest schema 校验","ABI/MSI 全矩阵","旧版本客户端下载测试","发布前 sha 对比"],
       rollback="保留旧 manifest/旧 updater；重新发布上一版即可"),
  dict(phase="10", title="UI 大文件机械拆分", size="M", risk="中",
       why="领域/API 稳定后再拆页面文件，避免重构期间大范围 rebase；只搬代码不改行为，风险主要来自漏搬/焦点。",
       items=["PC Main.kt 拆 AppShell + 每页一文件",
              "Android MiscScreens.kt 拆 Find/Rank/New/History/Library/Account/Settings/Creator",
              "PlayerChrome.kt 拆 BaseChrome + 清晰度/倍速/选集/弹幕浮层",
              "Store/PcStore 按设置/账号/历史/书架/缓存拆存储接口（schema 共享）"],
       affects=["所有 UI 布局/焦点/滚动/空态/错误态"],
       guard=["PC/Pad/TV 截图对比","遥控器焦点路径测试","只移动不改逻辑；单文件提交便于 review","LDPlayer 全页面冒烟"],
       rollback="按页面 revert；不涉及数据/接口"),
  dict(phase="11", title="树/CI/治理", size="S", risk="低",
       why="把“改前查树、改后更新树”变成自动检查，否则树会随时间失真；这是长期防止再乱的机制。",
       items=["给边加 runtime/build/deploy/copy/data 类型",
              "CI 校验关键节点文件/入口存在；检查 TECH_TREE.md 与 tech-tree.html 节点一致",
              "每次发版记录：改了哪些节点、跑了哪些矩阵",
              "看板增加历史/播放/更新关键指标，异常时能从节点跳到日志"],
       affects=["只影响流程和文档，不影响运行时"],
       guard=["CI 先 warning 后 blocking","每季度评审一次树"],
       rollback="CI 规则可降级为 warning；文档保留历史版本"),
  dict(phase="12", title="旧兼容清理", size="S", risk="中",
       why="只有前面都稳定、并且有版本分布/访问证据后，才删旧同步后端和过时指标；否则会误伤老客户端。",
       items=["/sync + progress.json + room-*.json：确认无老版本流量后删除",
              "看板 VVC 使用卡片：改成转码/播放失败统计或移除",
              "旧 manifest url / mirror 兼容字段：按最低支持版本决定保留期",
              "删除已迁移的旧 Api/Store/Resolver 转调层"],
       affects=["老客户端、历史/收藏同步、看板指标、旧下载链接"],
       guard=["看版本分布和 /sync 访问流水","至少保留一个发布周期的兼容","删除前备份状态文件"],
       rollback="恢复旧路由/旧字段；状态文件从备份还原"),
]

MERGE = [
  dict(title="历史：三份存储/两套合并 → 一个 HistoryRepository", nodes=["D-HISTORY","X-PC-STORE","X-AN-STORE","C-API","V-HIST","U-CONTENT"],
       why="本地、自建桶、官方三份；PC/Android 读法不同。",
       action="本地只做缓存；服务端一份合并；官方只做同步适配器；两端统一读路径。"),
  dict(title="PC/Android 共享核心 → 真共享模块", nodes=["C-API","C-MEDIA","C-PROXY","C-STATE"],
       why="Api.kt/Playback.kt 是两份物理副本，靠人工逐字同步。",
       action="KMP 或独立 jar；平台只保留取流兜底/设备默认值/播放器适配。"),
  dict(title="取流解析链 → 一个 Resolver 契约", nodes=["C-API","V-PLAYINFO","R-STREAM","R-ORO","X-AN-GO"],
       why="本机直连、SDK、oroplay、兼容接口、转码各自重试/缓存，超时预算不一致。",
       action="统一返回 Tier 列表和错误码；删死路径；转码异步 job；当前播放优先。"),
  dict(title="收藏/点赞/追更/预约 → 一个 ShelfRepository", nodes=["D-ACCOUNT","C-API","X-PC-STORE","X-AN-STORE"],
       why="四类书架行为各自一套 set/API/页面，解析和缓存重复。",
       action="统一 ShelfKind + ShelfRow + 本地缓存；上游差异放 adapter。"),
  dict(title="季发现：seasons / nextSeason / fillMissing → 一个 SeasonService", nodes=["D-DETAIL","C-API"],
       why="三套逻辑都在找季，缓存和校验各写一遍。",
       action="一个 SeasonService 管清单、补季、下一季、主创校验和缓存。"),
  dict(title="PC/Android 预取 → 共享 SourcePlanner", nodes=["D-PLAY","X-PC-PREFETCH","X-AN-CACHE","X-AN-EXO"],
       why="两端都在做「选档 + 拉头部 + 准备代理」，只是播放器不同。",
       action="共享选档/头部/失效逻辑；平台只保留队列和播放器适配。"),
  dict(title="更新检查/下载 → 共享 UpdaterCore", nodes=["D-UPDATE","X-PC-UPD","X-AN-UPD","V-STATIC"],
       why="manifest 拉取、sha256、镜像重试两端重复，manifest schema 也分叉。",
       action="共享 check/download/verify；PC MSI/Android ABI 安装各自 adapter；一份 manifest schema。"),
  dict(title="登录协议 → 共享 PassportClient", nodes=["D-ACCOUNT","C-SIGN","C-API","U-PASSPORT"],
       why="PC/Android 各写一套短信/MFA/passport 协议。",
       action="共享协议/错误码/重试；两端只保留登录 UI。"),
  dict(title="静态/后台路由 → 一个 canonical router", nodes=["V-DUAL","V-SERVE","V-ADMIN","V-STATIC"],
       why="8080/8081 都服务静态；/admin 与 /api/admin 两套入口。",
       action="统一前缀和反代规则；静态 handler 共享；admin 只保留一个 canonical URL。"),
]

SPLIT = [
  dict(title="C-API 3741 行 → 按领域拆", nodes=["C-API","D-CATALOG","D-DETAIL","D-PLAY","D-HISTORY","D-ACCOUNT","D-SOCIAL"],
       why="内容/详情/取流/历史/书架/评论/登录/弹幕全在一个 object。",
       action="HttpCore + CatalogApi + DetailApi + PlaybackApi + HistoryApi + AccountApi + SocialApi；平台副本只保留薄适配。"),
  dict(title="播放器 1800~2400 行 → 会话/预取/历史/UI 分层", nodes=["P-PLAYER","A-PLAYER","A-CHROME"],
       why="播放、队列、预取、历史、弹幕、评论、错误恢复全在一个节点。",
       action="PlaybackSession / PrefetchController / HistoryReporter / PlayerUiState / Chrome；UI 只订阅状态。"),
  dict(title="PC Main.kt 3349 行 → 壳 + 页面", nodes=["P-SHELL","P-HOME","P-FIND","P-RANK","P-NEW","P-HISTORY","P-LIBRARY"],
       why="路由、首页、搜索、排行、历史、追更、设置全在一个文件。",
       action="AppShell + 每个页面一个文件；公共卡片/空态放 UI 组件。"),
  dict(title="Android MiscScreens 3765 行 → 每屏一文件", nodes=["A-MISC"],
       why="找剧/排行/新剧/历史/追更/预约/我的/登录/设置/演职员全在一个文件。",
       action="FindScreen.kt、RankScreen.kt、NewScreen.kt、HistoryScreen.kt、LibraryScreen.kt、AccountScreen.kt、SettingsScreen.kt。"),
  dict(title="serve.py 837 行 → 数据面/控制面拆开", nodes=["V-SERVE","V-HIST","V-ADMIN","V-STATIC"],
       why="静态、取流、历史、日志、看板、世界树同进程；重启互相影响。",
       action="static_update / content_api / library_api / telemetry_admin 四个模块；admin 独立进程。"),
  dict(title="oroplay.js 462 行 → 身份/解析/转码/HTTP 拆", nodes=["R-ORO","R-SIGN","R-TRANSCODE"],
       why="注册身份、主/兼容接口、档位选择、转码队列、HTTP 缓存混在一个文件。",
       action="identity.js / tiers.js / transcode-queue.js / server.js；每块可单独测。"),
  dict(title="streamsrv 320 行 → 解析/缓存拆，删 SDK 兜底", nodes=["R-STREAM","R-ORO","R-SIGN"],
       why="缓存、oroplay 调用、SDK 兜底、HTTP handler 混在一起；SDK 链路已失效。",
       action="resolver / cache / http 三层；删 SDK 或 env 开关。"),
  dict(title="Store/PcStore 448~538 行 → 设置/账号/历史/书架/缓存", nodes=["X-PC-STORE","X-AN-STORE"],
       why="五类数据一个类，迁移/清理/并发锁互相影响。",
       action="按领域拆存储接口；共享 schema/迁移，平台只保留 prefs 实现。"),
  dict(title="PlayerChrome 1088 行 → 控制条 + 浮层", nodes=["A-CHROME"],
       why="进度/播放/倍速/清晰度/选集/弹幕设置/焦点导航都在一个文件。",
       action="BaseChrome + QualityMenu + SpeedMenu + EpisodePicker + DanmakuMenu；焦点策略单独。"),
]

REVIEW = [
  dict(level="P0", title="历史是三写两读", nodes=["D-HISTORY","X-PC-STORE","X-AN-STORE","C-API","V-HIST","U-CONTENT"],
       why="本地、自建桶、官方三份；三处写、两套合并；PC 历史页读官方，Android 读本地+合并。同一事实没有唯一 owner。",
       fix="唯一 HistoryRepository：本地只做缓存，服务端只做一份合并，官方只做同步适配器；PC/Android 走同一读路径。"),
  dict(level="P0", title="取流链路过长，超时预算不一致", nodes=["C-API","V-PLAYINFO","R-STREAM","R-ORO","R-TRANSCODE"],
       why="客户端 70s、streamsrv 120s、转码 300s；客户端超时后服务器还在跑；预取和当前播放共用 FIFO 转码队列。",
       fix="统一解析契约和超时预算；转码改异步 job + 轮询/回调；当前播放优先；fallback 收敛成显式状态机。"),
  dict(level="P0", title="共享核心是两份物理副本", nodes=["C-API","C-MEDIA","C-PROXY","C-STATE"],
       why="PC/Android 各一份 Api.kt/Playback.kt，靠人工逐字同步；改接口漏一边就是一端修好另一端还在。",
       fix="抽真正的共享模块（KMP/独立 jar），至少加 CI diff 检查和共享契约测试。"),
  dict(level="P1", title="serve.py 单体把控制面和数据面绑一起", nodes=["V-SERVE","V-HIST","V-ADMIN","V-STATIC"],
       why="首页/历史/取流/日志/看板/世界树同进程；改看板或重启会碰历史/取流；admin token 放在 query 里。",
       fix="admin/世界树/静态控制面拆独立只读服务；数据面只暴露稳定 API；admin 改 cookie/短时票据。"),
  dict(level="P1", title="播放器和 UI 是 god file", nodes=["P-PLAYER","A-PLAYER","A-CHROME","P-HOME","A-MISC"],
       why="PlayerScreen 1800+ 行、Player 2300+ 行、Main 3300+ 行、MiscScreens 3700+ 行；播放/预取/队列/历史/弹幕/评论/错误恢复全在一个节点。",
       fix="抽 PlaybackSession / PrefetchController / HistoryReporter / PlayerUiState；UI 只订阅状态。"),
  dict(level="P1", title="缓存层太多且没有版本", nodes=["V-RELAY","V-PLAYINFO","R-STREAM","C-API","DetailCache","FeedCache","V-HEAT"],
       why="至少 7 层缓存、TTL 各不相同；改字段/schema 后旧缓存仍可能命中；没有统一失效入口。",
       fix="缓存 key 加 schema 版本；每层明确 TTL/失效 owner；改契约时 bump 版本并清对应层。"),
  dict(level="P1", title="更新/发布链碎片化", nodes=["X-BUILD","V-STATIC","D-UPDATE","S-WEB","I-SYSTEMD"],
       why="服务器 release.sh 与本机构建两套；PC/Android 两个版本源；manifest 手工生成；mirror_watchdog 还会改 url。",
       fix="一条发布流水线：版本号→构建→sha/ABI→manifest→官网→看板；禁止后置改 manifest。"),
  dict(level="P2", title="认证/签名栈归属不清", nodes=["C-SIGN","R-SIGN","X-AN-GO","U-PASSPORT"],
       why="客户端签名、服务器签名、播放签名、设备池、gomobile 混在一起；出问题难判断哪层签错/身份错。",
       fix="画清“谁签什么”的契约表；设备身份只保留一个池；播放签名和登录签名彻底分开。"),
  dict(level="P2", title="公网路由多入口", nodes=["V-DUAL","V-SERVE","I-TUNNEL"],
       why="/admin 不在 dual_server 反代范围，才需要把世界树挂到 /api/admin/tree；8080/8081/CF/cpolar/frpc 多套入口。",
       fix="统一 router：后台/静态/API 走同一前缀；减少“这个地址只在某个端口可用”的特例。"),
  dict(level="P2", title="树本身混合边类型，且靠人工维护", nodes=["全部"],
       why="一条边可能是运行时调用、构建产物、部署关系、代码副本、数据流；混在一起让上下游遍历产生噪声；代码变了不会自动报警。",
       fix="给边加 runtime/build/deploy/copy/data 类型；关键节点加 CI 校验；树只保留稳定边界。"),
  dict(level="P1", title="存在整条重复/死掉的旧路径", nodes=["V-PLAYINFO","R-STREAM","C-API","V-HIST"],
       why="playinfo 旧直连/stale HomeFeed/saveDecrypted/allowVvc/force_fallback 已于 2026-11-15 清理；streamsrv SDK 兜底、PC localPlay、/sync、VVC 统计卡仍待处理。",
       fix="下一步：处理还在跑但无效的 streamsrv SDK/PC localPlay；最后确认老客户端后再删 /sync 与 VVC 指标。详见 TECH_TREE.md §5.3。"),
]

# 校验引用
ids = {n["id"] for n in N}
assert len(ids) == len(N), "duplicate node id"
for a,b,_ in EDGES:
    assert a in ids, "missing edge from " + a
    assert b in ids, "missing edge to " + b

# 计算上下游
down = {i: [] for i in ids}
up = {i: [] for i in ids}
for a,b,label in EDGES:
    down[a].append({"id": b, "label": label})
    up[b].append({"id": a, "label": label})

for n in N:
    n["up"] = up[n["id"]]
    n["down"] = down[n["id"]]
    n["layerName"] = next(x[1] for x in LAYERS if x[0] == n["layer"])
    n["color"] = next(x[2] for x in LAYERS if x[0] == n["layer"])

STATUS = {
    "0": "已完成（2026-11-15，2.2.172）",
    "1": "已完成（2026-11-15，2.2.171）"
    ,"2": "已完成（2026-11-15，2.2.172）"
    ,"3": "已完成（2026-11-15，2.2.177）：PC 实机开启 history_v2；shadow diff=1（同集重看秒数），共享合并/服务端模块/Reporter 全部落地"
    ,"4": "已完成（2026-11-15，2.2.180）：PlaybackSourcePlanner + PlaybackGuard + PlayerUiState + PlaybackSession 状态已抽取；引擎实例保留平台适配"
    ,"5": "已完成（2026-11-15，2.2.182）：领域 facade + 10 个核心文件 shared 源目录；Api 内部按增量继续搬迁"
    ,"6": "已完成（2026-11-15，2.2.190）：异步转码 job（POST/GET /transcode）+ 202 pending 轮询 + 播放优先队列；客户端预取等 job 完成"
    ,"7": "已完成（2026-11-15，2.2.190）：ROLE=static(8080)/api(8082) 两进程 + hgtv-api.service；dual_server/8080 按 canonical 路由分流"
    ,"8": "已完成（2026-11-15，2.2.188）：SeasonService + ShelfRepository + PassportClient 边界全部落地"
    ,"9": "已完成（2026-11-15，2.2.190）：tools/release.py 串起版本→构建→dex 私参校验→manifest→上传→远端 sha 校验→桌面留包"
    ,"11": "已完成（2026-11-15，2.2.186）：tools/check_tree.py + GitHub tree-check workflow"
    ,"10": "已完成（2026-11-15，2.2.190）：PC Main 拆 8 文件、Android MiscScreens 拆 9 文件、Store/PcStore 拆 Shelf/History/Notice"
    ,"12": "已完成（2026-11-15，2.2.190）：版本分布全部 ≥2.2.146 后删除 /sync + room/progress 状态；看板 VVC 卡片与 manifest url 已移除"
}
for _p in PLAN:
    _p["status"] = STATUS.get(_p["phase"], "待开始")

DATA = {"layers": [{"id": a, "name": b, "color": c} for a,b,c in LAYERS], "nodes": N, "edgeCount": len(EDGES), "review": REVIEW, "merge": MERGE, "split": SPLIT, "plan": PLAN}
data_json = json.dumps(DATA, ensure_ascii=False, separators=(",", ":"))

HTML = r'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>开心果 · 架构世界树</title>
<style>
:root{
  --bg:#0f0d11; --bg2:#151217; --panel:#1b171e; --panel2:#221d25;
  --line:#332b38; --line2:#453a4b; --text:#f3eef5; --dim:#a99eac; --faint:#746a7a;
  --accent:#ff5a67; --accent2:#ffb36b; --mono:"Cascadia Code","SF Mono","Consolas",monospace;
}
*{box-sizing:border-box}
html,body{margin:0;height:100%;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
body{display:flex;flex-direction:column;height:100vh;overflow:hidden}
header{display:flex;align-items:center;gap:18px;padding:14px 20px;border-bottom:1px solid var(--line);
  background:linear-gradient(180deg,#171319,#0f0d11);flex:0 0 auto}
.brand{display:flex;align-items:baseline;gap:10px;white-space:nowrap}
.brand b{font-size:18px;letter-spacing:.5px}
.brand span{font-size:12px;color:var(--faint)}
.meta{margin-left:auto;display:flex;gap:16px;color:var(--faint);font-size:12px;font-family:var(--mono);white-space:nowrap}
.meta b{color:var(--dim);font-weight:500}
#q{width:260px;max-width:30vw;background:var(--panel);border:1px solid var(--line);border-radius:8px;
  color:var(--text);padding:8px 11px;font-size:13px;outline:none}
#q:focus{border-color:var(--accent);box-shadow:0 0 0 2px rgba(255,90,103,.15)}
.tabs{display:flex;gap:4px;background:var(--bg2);border:1px solid var(--line);border-radius:8px;padding:3px}
.tabs button{background:transparent;border:0;color:var(--dim);padding:6px 12px;border-radius:6px;font-size:12px;cursor:pointer}
.tabs button.on{background:var(--panel2);color:var(--text);box-shadow:inset 0 0 0 1px var(--line)}
main{display:flex;flex:1;min-height:0}
aside{width:310px;flex:0 0 auto;border-right:1px solid var(--line);display:flex;flex-direction:column;min-height:0;background:var(--bg2)}
.layer-filter{display:flex;flex-wrap:wrap;gap:5px;padding:10px 12px;border-bottom:1px solid var(--line)}
.lf{font-size:11px;color:var(--dim);border:1px solid var(--line);background:transparent;border-radius:20px;
  padding:4px 8px;cursor:pointer;white-space:nowrap}
.lf.on{color:#111;border-color:transparent;font-weight:600}
#list{overflow:auto;padding:8px 8px 20px;flex:1}
.layer-head{display:flex;align-items:center;gap:8px;padding:12px 8px 6px;color:var(--faint);
  font-size:11px;letter-spacing:1px;font-family:var(--mono)}
.layer-head i{width:8px;height:8px;border-radius:2px;display:block}
.node-item{display:flex;align-items:center;gap:8px;padding:7px 9px;border-radius:7px;cursor:pointer;
  border:1px solid transparent;margin:1px 0}
.node-item:hover{background:var(--panel)}
.node-item.sel{background:var(--panel2);border-color:var(--line2)}
.node-item .dot{width:6px;height:6px;border-radius:50%;flex:0 0 auto}
.node-item .name{font-size:13px;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.node-item .id{font-size:10px;color:var(--faint);font-family:var(--mono);white-space:nowrap}
.node-item .name mark{background:rgba(255,90,103,.25);color:var(--text);border-radius:2px}
#main{flex:1;min-width:0;overflow:auto;padding:22px 26px 60px}
.wrap{max-width:1180px;margin:0 auto}
.hero{display:flex;align-items:flex-start;gap:18px;margin-bottom:18px}
.hero .badge{font-family:var(--mono);font-size:11px;border-radius:5px;padding:4px 7px;color:#111;font-weight:700;white-space:nowrap}
.hero h1{margin:0 0 6px;font-size:25px;letter-spacing:.3px}
.hero p{margin:0;color:var(--dim);font-size:13px;line-height:1.75;max-width:900px}
.section{margin-top:18px}
.section h2{font-size:12px;color:var(--faint);letter-spacing:1.4px;font-family:var(--mono);
  font-weight:600;margin:0 0 9px;text-transform:uppercase}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
.files{display:flex;flex-wrap:wrap;gap:6px}
.file{font-family:var(--mono);font-size:11px;color:#c9bfce;background:var(--bg2);border:1px solid var(--line);
  padding:5px 7px;border-radius:5px;word-break:break-all}
.path{display:grid;grid-template-columns:1fr 1.15fr 1fr;gap:14px;align-items:start}
.path .side{display:flex;flex-direction:column;gap:6px}
.path .side .h{font-size:10px;color:var(--faint);letter-spacing:1px;font-family:var(--mono);margin-bottom:2px}
.chip{display:flex;flex-direction:column;gap:2px;background:var(--panel2);border:1px solid var(--line);
  border-radius:8px;padding:7px 9px;cursor:pointer;text-decoration:none;color:inherit;transition:.12s}
.chip:hover{border-color:var(--line2);transform:translateY(-1px)}
.chip b{font-size:12px;font-weight:600;line-height:1.35}
.chip small{font-size:10px;color:var(--faint);font-family:var(--mono)}
.center{align-self:stretch;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;
  background:linear-gradient(180deg,rgba(255,90,103,.06),rgba(255,90,103,.01));
  border:1px solid var(--line2);border-radius:12px;padding:18px 12px;text-align:center}
.center .id{font-family:var(--mono);font-size:10px;color:var(--faint)}
.center b{font-size:15px}
.center .arr{color:var(--faint);font-size:17px;line-height:1}
.checks{display:flex;flex-direction:column;gap:6px}
.checks div{font-size:12.5px;color:var(--dim);line-height:1.65;padding-left:18px;position:relative}
.checks div:before{content:"→";position:absolute;left:0;color:var(--accent)}
.board{display:grid;grid-template-columns:repeat(7,minmax(180px,1fr));gap:12px;align-items:start}
.lane{background:var(--bg2);border:1px solid var(--line);border-radius:10px;padding:8px;min-height:120px}
.lane h3{margin:2px 4px 8px;font-size:11px;letter-spacing:.8px;color:var(--faint);font-family:var(--mono);
  display:flex;align-items:center;gap:6px}
.lane h3 i{width:7px;height:7px;border-radius:2px;display:block}
.mini{font-size:11.5px;padding:6px 7px;border-radius:6px;margin:3px 0;cursor:pointer;border:1px solid transparent;
  color:var(--dim);background:var(--panel);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mini:hover,.mini.sel{border-color:var(--line2);color:var(--text)}
.empty{color:var(--faint);font-size:12px;padding:30px;text-align:center}
.hidden{display:none!important}
.kv{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
.kv span{font-size:10px;color:var(--faint);font-family:var(--mono);border:1px solid var(--line);border-radius:4px;padding:3px 6px}
.review-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:12px}
.refgrid{display:grid;grid-template-columns:1fr 1fr;gap:16px;align-items:start}
@media(max-width:1100px){.refgrid{grid-template-columns:1fr}}
.refcol h2{font-size:12px;color:var(--faint);letter-spacing:1px;font-family:var(--mono);margin:0 0 8px}
.refcard{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:11px 13px;margin-bottom:9px}
.refcard.merge{border-left:3px solid #64d2a0}.refcard.split{border-left:3px solid #ff9a62}
.refcard h3{margin:0 0 5px;font-size:13px}
.refcard p{margin:4px 0;color:var(--dim);font-size:12px;line-height:1.65}
.refcard .act{color:#c9d8ff}
.plan-list{display:flex;flex-direction:column;gap:10px}
.plan{background:var(--panel);border:1px solid var(--line);border-radius:11px;overflow:hidden}
.plan[open]{border-color:var(--line2)}
.plan summary{cursor:pointer;list-style:none;display:flex;align-items:center;gap:10px;padding:12px 14px}
.plan summary::-webkit-details-marker{display:none}
.plan summary:before{content:"▸";color:var(--faint);font-size:12px;width:12px}
.plan[open] summary:before{content:"▾"}
.plan .pno{font-family:var(--mono);font-size:11px;color:#111;border-radius:4px;padding:3px 7px;font-weight:700;background:#f3eef5}
.plan .ptitle{font-size:14px;font-weight:600}
.plan .pwhy{color:var(--faint);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
.plan .chipm{font-size:10px;font-family:var(--mono);border:1px solid var(--line);border-radius:4px;padding:3px 6px;color:var(--dim);white-space:nowrap}
.plan .pbody{padding:0 16px 14px 38px;border-top:1px solid var(--line)}
.plan .pbody>p{color:var(--dim);font-size:12.5px;line-height:1.7;margin:10px 0}
.plan-grid{display:grid;grid-template-columns:1.25fr 1fr 1fr;gap:12px;margin-top:10px}
.plan-grid h4{margin:0 0 5px;font-size:11px;color:var(--faint);font-family:var(--mono);letter-spacing:.5px}
.plan-grid ul{margin:0;padding-left:16px;color:var(--dim);font-size:12px;line-height:1.7}
.plan-grid li{margin:2px 0}
@media(max-width:1100px){.plan-grid{grid-template-columns:1fr}.plan .pwhy{display:none}}
.rv{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
.rv.p0{border-left:3px solid #ff6b6b}.rv.p1{border-left:3px solid #f0b35b}.rv.p2{border-left:3px solid #57c7e3}
.rv h3{margin:0 0 7px;font-size:14px;display:flex;align-items:center;gap:8px}
.rv .lvl{font-family:var(--mono);font-size:10px;color:#111;border-radius:4px;padding:3px 6px;font-weight:700}
.rv p{margin:6px 0;color:var(--dim);font-size:12.5px;line-height:1.7}
.rv .fix{color:#b9d9c9}
.rv .nodes{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}
.rvnode{font-family:var(--mono);font-size:10px;color:#c9bfce;background:var(--bg2);border:1px solid var(--line);border-radius:4px;padding:3px 6px;text-decoration:none}
.rvnode:hover{border-color:var(--accent);color:var(--text)}
@media(max-width:1100px){.board{grid-template-columns:repeat(3,1fr)}.path{grid-template-columns:1fr}.center{order:-1}}
</style>
</head>
<body>
<header>
  <div class="brand"><b>开心果 · 架构世界树</b><span>改前查上下游 · 改后同步这棵树</span></div>
  <input id="q" placeholder="搜索节点 / ID / 文件…  （按 / 聚焦）">
  <div class="tabs"><button id="tabExplore" class="on">探索</button><button id="tabBoard">全景</button><button id="tabReview">评审</button><button id="tabRefactor">合并/拆分</button><button id="tabPlan">修复顺序</button></div>
  <div class="meta"><span><b id="nCount">0</b> 节点</span><span><b id="eCount">0</b> 条边</span><span><b>PC 1.2.80</b> / <b>Android 2.2.170</b></span></div>
</header>
<main>
  <aside>
    <div class="layer-filter" id="layerFilter"></div>
    <div id="list"></div>
  </aside>
  <div id="main">
    <div class="wrap" id="explore"></div>
    <div class="wrap hidden" id="boardWrap"></div>
    <div class="wrap hidden" id="reviewWrap"></div>
    <div class="wrap hidden" id="refactorWrap"></div>
    <div class="wrap hidden" id="planWrap"></div>
  </div>
</main>
<script id="tree-data" type="application/json">__DATA__</script>
<script>
const T=JSON.parse(document.getElementById('tree-data').textContent);
const byId=Object.fromEntries(T.nodes.map(n=>[n.id,n]));
const layerMap=Object.fromEntries(T.layers.map(l=>[l.id,l]));
let selected=location.hash.slice(1)||'D-HISTORY';
if(!byId[selected]) selected='D-HISTORY';
let layerFilter=new Set();
let mode='explore';

const $=s=>document.querySelector(s);
document.getElementById('nCount').textContent=T.nodes.length;
document.getElementById('eCount').textContent=T.edgeCount;

function esc(s){return String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function highlight(text,q){
  if(!q) return esc(text);
  const i=text.toLowerCase().indexOf(q.toLowerCase());
  if(i<0) return esc(text);
  return esc(text.slice(0,i))+'<mark>'+esc(text.slice(i,i+q.length))+'</mark>'+esc(text.slice(i+q.length));
}
function chip(id,label){
  const n=byId[id]; if(!n) return '';
  return `<a class="chip" href="#${id}" data-id="${id}"><b>${esc(n.name)}</b><small>${esc(id)}${label?' · '+esc(label):''}</small></a>`;
}
function renderFilter(){
  $('#layerFilter').innerHTML=T.layers.map(l=>
    `<button class="lf ${layerFilter.has(l.id)?'on':''}" data-layer="${l.id}" style="${layerFilter.has(l.id)?'background:'+l.color:''}">${l.id} ${l.name}</button>`
  ).join('')+`<button class="lf ${layerFilter.size===0?'on':''}" data-layer="" style="${layerFilter.size===0?'background:#f3eef5':''}">全部</button>`;
  $('#layerFilter').querySelectorAll('.lf').forEach(b=>b.onclick=()=>{
    const id=b.dataset.layer;
    if(!id){layerFilter.clear()}else{if(layerFilter.has(id))layerFilter.delete(id);else layerFilter.add(id)}
    renderFilter();renderList();
  });
}
function renderList(){
  const q=$('#q').value.trim().toLowerCase();
  const groups={};
  T.nodes.forEach(n=>{
    if(layerFilter.size && !layerFilter.has(n.layer)) return;
    const hay=(n.id+' '+n.name+' '+n.summary+' '+n.files.join(' ')).toLowerCase();
    if(q && !hay.includes(q)) return;
    (groups[n.layer]||=[]).push(n);
  });
  let out='';
  for(const l of T.layers){
    const arr=groups[l.id]||[];
    if(!arr.length) continue;
    out+=`<div class="layer-head"><i style="background:${l.color}"></i>${l.id} · ${esc(l.name)} <span style="margin-left:auto">${arr.length}</span></div>`;
    for(const n of arr){
      out+=`<div class="node-item ${n.id===selected?'sel':''}" data-id="${n.id}">
        <span class="dot" style="background:${n.color}"></span>
        <span class="name">${highlight(n.name,q)}</span><span class="id">${esc(n.id)}</span></div>`;
    }
  }
  $('#list').innerHTML=out||'<div class="empty">没有匹配节点</div>';
  $('#list').querySelectorAll('.node-item').forEach(el=>el.onclick=()=>select(el.dataset.id));
}
function checksFor(n){
  const c=[];
  if(n.up.length) c.push(`改输出前先确认 ${n.up.length} 个上游消费者仍能解析：${n.up.map(x=>x.id).join('、')}`);
  if(n.down.length) c.push(`改输入/超时/缓存前先确认下游：${n.down.map(x=>x.id).join('、')}`);
  if(['D-PLAY','C-PROXY','C-MEDIA','R-STREAM','R-ORO','R-TRANSCODE'].includes(n.id)) c.push('播放链路要同时验证：普通集、兼容接口、转码、证书/404、切集、预取、历史。');
  if(['D-HISTORY','X-PC-STORE','X-AN-STORE','V-HIST'].includes(n.id)) c.push('历史链路要同时验证：真播 3 秒、1 秒退出、低集数、续播秒数、跨端。');
  if(n.id==='D-HISTORY') c.push('入口一致性：Android 排行/新剧仍写死第 1 集；PC 详情/playSeries 不带秒数。改历史先看 TECH_TREE.md §5.1。');
  if(['C-API','C-SIGN','C-STATE'].includes(n.id)) c.push('PC 与 Android 两份核心副本要一起改，逐字差异只允许平台兜底/设备默认值。');
  if(['X-BUILD','X-PC-UPD','X-AN-UPD','V-STATIC'].includes(n.id)) c.push('发版要验证：版本号、私有 -P 参数、sha256、ABI、签名、旧客户端救急 URL。');
  if(n.layer==='L1') c.push('页面改动要同时看 Pad/TV（同一份源码）和 PC；空态、加载态、失败态都要在同一轮检查。');
  if(!c.length) c.push('沿上游/下游各走一层，确认契约、缓存和失败态没有被破坏。');
  return c;
}
function renderExplore(){
  const n=byId[selected];
  const ups=n.up.length?n.up.map(x=>chip(x.id,x.label)).join(''):`<div class="empty" style="padding:12px">没有上游（树根/外部入口）</div>`;
  const downs=n.down.length?n.down.map(x=>chip(x.id,x.label)).join(''):`<div class="empty" style="padding:12px">没有下游（终端节点）</div>`;
  $('#explore').innerHTML=`
    <div class="hero">
      <span class="badge" style="background:${n.color}">${esc(n.layer)}</span>
      <div><h1>${esc(n.name)}</h1><p>${esc(n.summary)}</p>
        <div class="kv"><span>${esc(n.id)}</span><span>${esc(n.layerName)}</span><span>${n.up.length} 上游</span><span>${n.down.length} 下游</span></div>
      </div>
    </div>
    <div class="section"><h2>关键文件 / 位置</h2><div class="card files">${n.files.map(f=>`<span class="file">${esc(f)}</span>`).join('')}</div></div>
    <div class="section"><h2>依赖路径</h2><div class="path">
      <div class="side"><div class="h">上游 · 谁依赖它</div>${ups}</div>
      <div class="center"><div class="id">${esc(n.id)}</div><b>${esc(n.name)}</b><div class="arr">↑ 上游　　↓ 下游</div><div class="id">选中节点</div></div>
      <div class="side"><div class="h">下游 · 它依赖谁</div>${downs}</div>
    </div></div>
    <div class="section"><h2>改动检查</h2><div class="card checks">${checksFor(n).map(x=>`<div>${esc(x)}</div>`).join('')}</div></div>`;
  $('#explore').querySelectorAll('.chip').forEach(el=>el.onclick=e=>{e.preventDefault();select(el.dataset.id)});
}
function renderBoard(){
  let out='<div class="section"><h2>全景 · 按层</h2><div class="board">';
  for(const l of T.layers){
    out+=`<div class="lane"><h3><i style="background:${l.color}"></i>${esc(l.id)} ${esc(l.name)}</h3>`;
    for(const n of T.nodes.filter(x=>x.layer===l.id)){
      out+=`<div class="mini ${n.id===selected?'sel':''}" data-id="${n.id}" title="${esc(n.summary)}">${esc(n.name)}</div>`;
    }
    out+='</div>';
  }
  out+='</div></div>';
  $('#boardWrap').innerHTML=out;
  $('#boardWrap').querySelectorAll('.mini').forEach(el=>el.onclick=()=>select(el.dataset.id));
}
function renderReview(){
  const order={P0:0,P1:1,P2:2};
  const items=[...T.review].sort((a,b)=>(order[a.level]-order[b.level]));
  $('#reviewWrap').innerHTML=`<div class="section"><h2>结构性不合理 · 评审结论</h2>
    <div class="review-grid">`+items.map(r=>{
      const cls=r.level.toLowerCase();
      const nodes=r.nodes.map(x=>byId[x]?`<a class="rvnode" href="#${x}" data-id="${x}">${esc(x)}</a>`:`<span class="rvnode">${esc(x)}</span>`).join('');
      return `<div class="rv ${cls}"><h3><span class="lvl" style="background:${r.level==='P0'?'#ff6b6b':r.level==='P1'?'#f0b35b':'#57c7e3'}">${r.level}</span>${esc(r.title)}</h3>
        <p>${esc(r.why)}</p><p class="fix"><b>方向：</b>${esc(r.fix)}</p><div class="nodes">${nodes}</div></div>`;
    }).join('')+`</div></div>`;
  $('#reviewWrap').querySelectorAll('.rvnode[data-id]').forEach(a=>a.onclick=e=>{e.preventDefault();setMode('explore');select(a.dataset.id)});
}
function renderRefactor(){
  const col=(title,kind,items)=>`<div class="refcol"><h2>${title}</h2>`+items.map(r=>{
    const nodes=r.nodes.map(x=>byId[x]?`<a class="rvnode" href="#${x}" data-id="${x}">${esc(x)}</a>`:`<span class="rvnode">${esc(x)}</span>`).join('');
    return `<div class="refcard ${kind}"><h3>${esc(r.title)}</h3><p>${esc(r.why)}</p><p class="act"><b>方向：</b>${esc(r.action)}</p><div class="nodes">${nodes}</div></div>`;
  }).join('')+'</div>';
  $('#refactorWrap').innerHTML=`<div class="section"><h2>重构清单 · 哪些该合并，哪些该拆</h2><div class="refgrid">`+
    col('可以合并','merge',T.merge)+col('需要拆分','split',T.split)+`</div></div>`;
  $('#refactorWrap').querySelectorAll('.rvnode[data-id]').forEach(a=>a.onclick=e=>{e.preventDefault();setMode('explore');select(a.dataset.id)});
}
function renderPlan(){
  const riskColor={高:'#ff6b6b',中:'#f0b35b',低:'#64d2a0'};
  $('#planWrap').innerHTML=`<div class="section"><h2>修复顺序 · 先做什么，后做什么，为什么</h2>
    <div class="plan-list">`+T.plan.map((p,i)=>`<details class="plan" ${i===0?'open':''}>
      <summary><span class="pno">${esc(p.phase)}</span><span class="ptitle">${esc(p.title)}</span>
        <span class="pwhy">${esc(p.why)}</span>
        <span class="chipm" style="color:${riskColor[p.risk]||'#a99eac'}">风险 ${esc(p.risk)}</span><span class="chipm">规模 ${esc(p.size)}</span>
        <span class="chipm" style="color:${p.status.startsWith('已完成')?'#64d2a0':p.status.startsWith('进行中')?'#f0b35b':'#a99eac'}">${esc(p.status)}</span>
      </summary>
      <div class="pbody"><p><b>为什么现在：</b>${esc(p.why)}</p>
        <div class="plan-grid">
          <div><h4>本阶段事项</h4><ul>${p.items.map(x=>`<li>${esc(x)}</li>`).join('')}</ul></div>
          <div><h4>可能波及</h4><ul>${p.affects.map(x=>`<li>${esc(x)}</li>`).join('')}</ul>
            <h4 style="margin-top:8px">回滚</h4><ul><li>${esc(p.rollback)}</li></ul></div>
          <div><h4>怎么保证不坏</h4><ul>${p.guard.map(x=>`<li>${esc(x)}</li>`).join('')}</ul></div>
        </div>
      </div></details>`).join('')+`</div></div>`;
}
function render(){
  renderList();
  if(mode==='explore')renderExplore();
  else if(mode==='board')renderBoard();
  else if(mode==='refactor')renderRefactor();
  else if(mode==='plan')renderPlan();
  else renderReview();
}
function select(id){if(!byId[id])return;selected=id;location.hash=id;render();$('#main').scrollTop=0}
function setMode(m){
  mode=m;
  $('#tabExplore').classList.toggle('on',m==='explore');
  $('#tabBoard').classList.toggle('on',m==='board');
  $('#tabReview').classList.toggle('on',m==='review');
  $('#tabRefactor').classList.toggle('on',m==='refactor');
  $('#tabPlan').classList.toggle('on',m==='plan');
  $('#explore').classList.toggle('hidden',m!=='explore');
  $('#boardWrap').classList.toggle('hidden',m!=='board');
  $('#reviewWrap').classList.toggle('hidden',m!=='review');
  $('#refactorWrap').classList.toggle('hidden',m!=='refactor');
  $('#planWrap').classList.toggle('hidden',m!=='plan');
  render();
}
$('#q').addEventListener('input',renderList);
document.addEventListener('keydown',e=>{
  if(e.key==='/' && document.activeElement!==$('#q')){e.preventDefault();$('#q').focus()}
  if(e.key==='Escape'){$('#q').value='';$('#q').blur();renderList()}
});
$('#tabExplore').onclick=()=>setMode('explore');
$('#tabBoard').onclick=()=>setMode('board');
$('#tabReview').onclick=()=>setMode('review');
$('#tabRefactor').onclick=()=>setMode('refactor');
$('#tabPlan').onclick=()=>setMode('plan');
window.addEventListener('hashchange',()=>{const id=location.hash.slice(1);if(byId[id]&&id!==selected){selected=id;render()}});
renderFilter();render();
if(new URLSearchParams(location.search).get('board')==='1') $('#tabBoard').click();
if(new URLSearchParams(location.search).get('review')==='1') $('#tabReview').click();
if(new URLSearchParams(location.search).get('refactor')==='1') $('#tabRefactor').click();
if(new URLSearchParams(location.search).get('plan')==='1') $('#tabPlan').click();
</script>
</body>
</html>'''

def write_plan_md():
    lines = [
        "# 开心果 · 修复顺序（2026-11-15）",
        "",
        "> 由 `docs/build_tech_tree.py` 生成，和看板「架构世界树 → 修复顺序」同源。",
        "> 原则：**先建安全网，再删死代码，再统一 owner，再拆 god node，最后做机械拆分。**",
        "",
        "## 总原则",
        "",
        "1. 一次只做一个高风险阶段；高风险阶段不叠加发布。",
        "2. 每个阶段先有基线、开关、回滚，再改代码。",
        "3. 先删 100% 无调用的东西，再动还在运行但无效的路径，最后动兼容层。",
        "4. 先统一事实 owner，再拆文件；先分域，再抽共享模块。",
        "5. 只移动代码的机械拆分放在最后，避免 refactor 期间大范围 rebase。",
        "",
        "## 阶段总表",
        "",
        "| 顺序 | 阶段 | 状态 | 风险 | 规模 | 为什么现在 |",
        "|---|---|---|---|---|---|",
    ]
    for x in PLAN:
        lines.append(f"| {x['phase']} | {x['title']} | {x['status']} | {x['risk']} | {x['size']} | {x['why']} |")
    lines += ["", "## 详细阶段", ""]
    for x in PLAN:
        lines.append(f"### {x['phase']}. {x['title']}")
        lines.append("")
        lines.append(f"**状态**：{x['status']}")
        lines.append("")
        lines.append(f"**风险/规模**：{x['risk']} / {x['size']}")
        lines.append("")
        lines.append(f"**为什么现在**：{x['why']}")
        lines.append("")
        lines.append("**本阶段事项**：")
        for i in x["items"]:
            lines.append(f"- {i}")
        lines.append("")
        lines.append("**可能波及**：")
        for i in x["affects"]:
            lines.append(f"- {i}")
        lines.append("")
        lines.append("**怎么保证不坏**：")
        for i in x["guard"]:
            lines.append(f"- {i}")
        lines.append("")
        lines.append(f"**回滚**：{x['rollback']}")
        lines.append("")
    lines += [
        "## 通用安全网（每个阶段都适用）",
        "",
        "1. **基线快照**：改前保存 API 响应 fixture、历史 JSON、播放矩阵结果、PC/Pad/TV 截图。",
        "2. **黄金用例**：播放矩阵（普通/VVC/兼容/转码/证书/404/seek/倍速/连播）、历史矩阵（3秒/1秒/低集数/续播/跨端）、解析 fixture、manifest 校验。",
        "3. **功能开关**：`history_v2` / `resolver_v2` / `player_session_v2`，按设备/版本开关；默认旧路径。",
        "4. **影子模式**：历史合并、解析结果先新旧双算，只记 diff 不切换；diff 为 0 再切。",
        "5. **canary**：先 LDPlayer，再一台用户授权设备；不直接全量。",
        "6. **可观测**：requestId 贯穿客户端→API→resolver→转码；看板按阶段看失败率/耗时/fallback。",
        "7. **备份/回滚**：服务端状态、manifest、旧二进制保留一版；数据迁移必须可逆。",
        "8. **树同步**：每个阶段结束更新 TECH_TREE.md / tech-tree.html，并记录改了哪些节点、跑了哪些矩阵。",
        "",
        "## 出问题后的闭环",
        "",
        "1. **定位**：用 requestId 找到失败阶段（客户端/API/resolver/转码/CDN），再用 feature flag 判断是否新路径。",
        "2. **止损**：关开关；服务端切回旧二进制/旧路由；数据问题恢复备份。",
        "3. **复现**：把失败样本加入黄金用例，确认旧路径正常、新路径能复现。",
        "4. **修因**：在对应树节点上改，不在 UI 层打补丁；同时检查上下游和孪生节点。",
        "5. **再上**：小流量/单设备 canary，diff 清零后扩大。",
        "6. **沉淀**：更新树的“已知风险/评审”，把这次 failure 变成回归用例。",
        "",
        "## 不要做的事",
        "",
        "- 不要一次同时做历史 + 播放器 + Resolver 三个高风险重构。",
        "- 不要为了“统一”合并 PC/Android UI、ffmpeg/ExoPlayer、服务端代取/本机兜底。",
        "- 不要在没有观测/回滚的情况下改历史合并或取流契约。",
        "- 不要先抽共享模块再分域，否则会把 god object 搬两次。",
        "- 不要在没有版本分布证据时删旧兼容（/sync、旧 manifest、旧字段）。",
        "",
    ]
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "REMEDIATION_PLAN.md")
    with open(dest, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("wrote", dest, len(lines), "lines")


import re as _re

def sync_md_index():
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "TECH_TREE.md")
    try:
        txt = open(p, encoding="utf-8").read()
    except OSError:
        return
    rows = ["<!-- NODEINDEX:START -->", "## 全量节点索引（自动生成·以此为准）", "",
            "| 层 | id | 名称 | 摘要 |", "|---|---|---|---|"]
    for n in N:
        s = (n.get("summary") or "").replace("|", "/").replace("\n", " ")
        rows.append("| %s | `%s` | %s | %s |" % (n.get("layer", ""), n["id"], n.get("name", ""), s))
    rows.append("<!-- NODEINDEX:END -->")
    block = "\n".join(rows)
    if "<!-- NODEINDEX:START -->" in txt:
        txt = _re.sub(r"<!-- NODEINDEX:START -->.*?<!-- NODEINDEX:END -->", lambda m: block, txt, flags=_re.S)
    else:
        txt = txt.rstrip() + "\n\n" + block + "\n"
    open(p, "w", encoding="utf-8").write(txt)
    print("synced node index into TECH_TREE.md:", len(N))


sync_md_index()
write_plan_md()

out = HTML.replace("__DATA__", data_json)
dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tech-tree.html")
with open(dest, "w", encoding="utf-8") as f:
    f.write(out)
print("wrote", dest, len(out), "bytes; nodes", len(N), "edges", len(EDGES))
