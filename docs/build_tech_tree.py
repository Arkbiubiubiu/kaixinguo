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
    dict(id="P-FEED", layer="L1", name="PC 刷剧", summary="整屏连播、换剧、预览不记历史。", files=["FeedPlayerScreen.kt: FeedPlayerPage", "Player.kt: embedded"]),
    dict(id="P-LOGIN", layer="L1", name="PC 登录/配对", summary="短信 MFA、账号 cookies、匹配码同步。", files=["PcLogin.kt", "PcLoginScreen.kt"]),
    dict(id="P-DONATE", layer="L1", name="PC 打赏浮层", summary="展示打赏码，不接支付 SDK。", files=["Donate.kt"]),

    # L1 Android
    dict(id="A-SHELL", layer="L1", name="Android App / 导航", summary="初始化、路由栈、侧栏、返回、Pad/TV 适配。", files=["MainActivity.kt", "App.kt", "Screen.kt"]),
    dict(id="A-HOME", layer="L1", name="Android 首页", summary="三 tab、hero、卡片、继续看。", files=["HomeFeed.kt"]),
    dict(id="A-FEED", layer="L1", name="Android 刷剧", summary="整屏连播、上下换剧、预载 5 部。", files=["FeedScreen.kt", "FeedPreload.kt"]),
    dict(id="A-DETAIL", layer="L1", name="Android 详情页", summary="详情、选集、季、剧评、相关推荐、继续看。", files=["DetailScreen.kt"]),
    dict(id="A-PLAYER", layer="L1", name="Android 播放页", summary="起播、队列、预取、无缝切集、看门狗、历史、进度上报。", files=["PlayerScreen.kt"]),
    dict(id="A-CHROME", layer="L1", name="Android 播放控制条", summary="进度条、播放/暂停、上下集、倍速、清晰度、选集、弹幕设置。", files=["PlayerChrome.kt"]),
    dict(id="A-MISC", layer="L1", name="Android 其余页面", summary="找剧、排行、新剧、历史、收藏/点赞、追更、预约、我的、设置、演职员；新剧页视图/分类/日期/滚动存 UiMemo，返回不丢。", files=["FindScreens.kt", "RankScreens.kt", "NewScreens.kt", "RecordsScreens.kt", "FollowScreens.kt", "AccountScreens.kt", "SettingsScreen.kt", "CreatorScreens.kt"]),
    dict(id="A-SOCIAL", layer="L1", name="Android 评论/弹幕/打赏", summary="单集评论、剧评、弹幕窗口/发送、打赏码。", files=["CommentsPanel.kt", "Danmaku.kt", "DonateDialog.kt"]),
    dict(id="A-LOGIN", layer="L1", name="Android 登录/配对", summary="短信 MFA、账号 cookies、匹配码。", files=["Login.kt", "LoginScreen.kt: 账号区"]),

    # L2 domains
    dict(id="D-CATALOG", layer="L2", name="内容目录", summary="首页/找剧/排行/新剧/日历的数据编排与缓存；新剧翻页必须带 session_id（只传 offset 上游会重复第 1 页）。", files=["HomeFeed.kt / FeedCache.kt", "Api.landpage/tab/search/rank/new"]),
    dict(id="D-DETAIL", layer="L2", name="详情/选集/季", summary="详情、集数映射、季切换、相关推荐、剧评。", files=["DetailScreen.kt 两端", "Api.ensureDetail/seasons/reviews/related"]),
    dict(id="D-PLAY", layer="L2", name="播放编排", summary="选档、取流、预取、队列、无缝切集、看门狗、清晰度/倍速。", files=["PlayerScreen.kt / Player.kt", "PlaybackSession.kt", "PlaybackSourcePlanner.kt", "PlaybackGuard.kt", "PlayerUiState.kt", "PlaybackPrefetch.kt", "FeedPreload.kt"]),
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
    dict(id="X-PC-PREFETCH", layer="L4", name="PC 预取", summary="预热下一集直链/头部/代理，最多按设置 N 集。", files=["PlaybackPrefetch.kt"]),
    dict(id="X-PC-UPD", layer="L4", name="PC 平台服务", summary="MSI 更新、日志上传、ffmpeg 路径解析。", files=["Updater.kt", "ClientLog.kt", "Resolver.kt"]),
    dict(id="X-AN-STORE", layer="L4", name="Android 本地库", summary="SharedPreferences：设置、账号、历史、书架、缓存清理。", files=["Store.kt", "ShelfStore.kt", "HistoryStore.kt", "hongguo_pad prefs"]),
    dict(id="X-AN-EXO", layer="L4", name="Android 播放引擎", summary="Media3 ExoPlayer 1.4.1 + 播放列表、硬解/软解、首帧、错误恢复。", files=["PlayerScreen.kt", "media3-exoplayer 1.4.1"]),
    dict(id="X-AN-GO", layer="L4", name="Android 本机取流", summary="gomobile Go 绑定，本机签名/取流兜底；LocalResolverProvider。", files=["DeviceResolver.kt", "libs/openhongguo.jar"]),
    dict(id="X-AN-CACHE", layer="L4", name="Android 缓存/预载", summary="刷剧首屏磁盘缓存 24h、预载 5 部、详情/集数头部。", files=["FeedCache.kt", "FeedPreload.kt"]),
    dict(id="X-AN-UPD", layer="L4", name="Android 平台服务", summary="APK 清单/ABI 选择/下载/sha/安装、日志上传。", files=["Updater.kt", "LogUploader.kt", "UpdateProvider.kt"]),
    dict(id="X-BUILD", layer="L4", name="构建 / 签名 / 参数", summary="版本号、私有 -P 参数、flavor、ABI 拆分、MSI、签名。", files=["build.gradle.kts 两端", "android/release.sh"]),

    # L5 server
    dict(id="V-DUAL", layer="L5", name="双协议网关", summary="/api/admin* 反代 8080（静态/看板），其余 /api/* 与 /sign 反代 8082（内容 API）；静态/官网；/get/* 302 到下载节点。", files=["/opt/hgtv-update/dual_server.py", "8081/TLS"]),
    dict(id="V-SERVE", layer="L5", name="静态 / 看板进程", summary="ROLE=static（8080）：静态 Range、/admin、/api/admin*；其余 /api/* 反代 V-API。看板重启不影响取流。", files=["/opt/hgtv-update/serve.py (ROLE=static)", "8080", "hgtv-update.service"]),
    dict(id="V-API", layer="L5", name="内容 API 进程", summary="ROLE=api（8082）：fetch/sign/playinfo/history/heat/rankmeta/seasons/flags/transcode；不提供静态和看板。", files=["/opt/hgtv-update/serve.py (ROLE=api)", "8082", "hgtv-api.service"]),
    dict(id="V-RELAY", layer="L5", name="上游代取/缓存", summary="代签、代拉、TTL/SWR/磁盘缓存、feed-cache。", files=["/opt/hgtv-update/relay.py"]),
    dict(id="V-PLAYINFO", layer="L5", name="取流中转", summary="30min 成功缓存、15s 负缓存、快速重试；202 pending + job 轮询（异步转码）。", files=["/opt/hgtv-update/playinfo.py"]),
    dict(id="V-HEAT", layer="L5", name="热度/榜单元数据", summary="热度 6h 缓存、榜单元数据 10min、缺热度回填。", files=["heat.py", "serve.py: chart/rankmeta"]),
    dict(id="V-HIST", layer="L5", name="历史/房间/日志", summary="历史合并、客户端日志、访问流水；Phase 12 已删除 /sync 与 room-*.json。", files=["history_store.py", "serve.py: /api/history (ROLE=api)", "serve.py: clientlog"]),
    dict(id="V-STATIC", layer="L5", name="静态/发布物", summary="APK/MSI、version-*.json、官网、/transcode/*、镜像清单。", files=["/srv/hgtv-update"]),
    dict(id="V-ADMIN", layer="L5", name="看板", summary="运行数据 + 架构世界树；/api/admin/tree 读取 /srv/hgtv-update/tech-tree.html。", files=["admin.py", "serve.py: /api/admin (ROLE=static)", "admin.token"]),

    # L6
    dict(id="R-STREAM", layer="L6", name="签名取流服务", summary="/play 缓存 25min/400 条；只走 oroplay；bvc2-only 返回 202 pending；/transcode 代理 job 查询。", files=["/opt/research/openhongguo/cmd/streamsrv", "9110"]),
    dict(id="R-ORO", layer="L6", name="真签名解析", summary="**v5 优先**：优先调 R-V5（纯 HTTP v5 签名）取全部清晰度，失败/空/超时回 R-ORACLE；**已转码的 vid 直接发 `/transcode/*_v3.mp4`，不再打上游**（上游限流时已看过的集照常播）；bytevc1（HEVC）直接透传，VVC/AV1 先要兼容 fallback（codec_type=3/1），最后才转码；返回前强制过滤 bytevc2；旧 video_tab 仅诊断兜底；转码 job 可查询（30min/200 条）。", files=["/opt/research/oroplay.js", "9118"]),
    dict(id="R-V5", layer="L6", name="v5 纯 HTTP 签名", summary="N3urda/aston314 GPL 实现：匿名设备注册 + Android v04.10.03 Medusa/Helios/Khronos v5 签名 + gzip mobile 模型；VVC/AV1 集用模型 fallback_api 的 codec_type=3/1 取 1080P HEVC/H.265；HTTP 9119，单设备、1.2s 间隔、空响应 90s 冷却。", files=["/opt/research/v5/v5resolver.js", "playback-v5-sign.js", "playback-v5-carrier.js", "mobile-playback-v5.js", "9119", "v5resolver.service"]),
    dict(id="R-ORACLE", layer="L6", name="Android 官方 App oracle", summary="redroid Android 13 容器 + 官方红果 App + Frida 同步 Java 调用；作为 R-ORO 的**兜底**，v5 失败时按 vid 取 1080p/720p 等全部档位，HTTP 9120，容器/systemd 开机自启。", files=["/opt/research/oracle_parent.py", "resolve_one.py", "oracle_resolver.js", "oracle.service", "redroid container", "9120"]),
    dict(id="R-SIGN", layer="L6", name="签名/设备池", summary="上游签名、设备身份注册/轮换、Argus/Ladon。", files=["9100/9101/9099", "playback-sign.js", "playback-device.js"]),
    dict(id="R-TRANSCODE", layer="L6", name="VVC/AV1 转码", summary="下载加密 MP4 → Java 解密 → H.264/HEVC/AV1 直接解 MP4（AV1 裸流会音频-only），VVC 才抽 Annex-B → ffmpeg H.264；**容器标 av01 但 1 秒解码失败时按 VVC 重抽**（bv2C 私有封装里可能是 VVC）；VAAPI 优先、x264 兜底；输出必须 ffprobe 有视频轨，否则删除报错，绝不返回音频-only。", files=["do_transcode.py", "extract_vvc.py", "DecryptMp4.java"]),
    dict(id="U-CONTENT", layer="L6", name="番茄内容接口", summary="首页/详情/搜索/排行/日历/追更/评论/弹幕/历史/演职员。", files=["api5-normal-lq.fqnovel.com"]),
    dict(id="U-PLAY", layer="L6", name="番茄播放模型", summary="multi_video_model、fallback_api、video_meta/codec_type。", files=["multi_video_model/v1"]),
    dict(id="U-CDN", layer="L6", name="视频/图片 CDN", summary="加密 MP4、封面、头像。", files=["字节 CDN"]),
    dict(id="U-PASSPORT", layer="L6", name="登录/MFA", summary="短信登录、MFA、cookies。", files=["番茄 passport"]),
    dict(id="I-TUNNEL", layer="L6", name="公网通道", summary="樱花 frpc 44468、Cloudflare、cpolar；把 8081/8080 暴露到公网。", files=["frpc@…", "cloudflared", "cpolar"]),
    dict(id="I-GITHUB", layer="L6", name="GitHub Release 更新镜像", summary="公开 kaixinguo Release 资产；axisnow/gh-proxy 加速；固定 tag 地址（/srv/hgtv-update/github-release-tag，禁用会被 CDN 缓存的 latest/download）；update-mirrors 首选，樱花兜底；PC 3 段、安卓/TV 3 段。", files=["github.com/Arkbiubiubiu/kaixinguo/releases", "axisnow.gh-proxy.org", "gh-proxy.org"]),
    dict(id="I-STATE", layer="L6", name="服务端状态", summary="历史、房间、feed-cache、heat、clientlogs、access 流水、admin token。", files=["/var/lib/hgtv-sync"]),
    dict(id="I-SYSTEMD", layer="L6", name="守护/备份", summary="systemd units、镜像 watchdog、隧道 watchdog、备份。", files=["systemd", "mirror_watchdog.sh"]),
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
    ("R-TRANSCODE","C-MEDIA","同一解密算法副本"), ("R-TRANSCODE","U-CDN","下载加密 MP4"), ("R-TRANSCODE","V-STATIC","输出 v3.mp4"),
    ("I-TUNNEL","V-DUAL","公网入口"),
    ("I-SYSTEMD","V-SERVE","守护"), ("I-SYSTEMD","V-API","守护"), ("I-SYSTEMD","V-DUAL","守护"), ("I-SYSTEMD","R-STREAM","守护"), ("I-SYSTEMD","R-ORO","守护"), ("I-SYSTEMD","R-V5","守护"), ("I-SYSTEMD","R-ORACLE","守护"),
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


write_plan_md()

out = HTML.replace("__DATA__", data_json)
dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tech-tree.html")
with open(dest, "w", encoding="utf-8") as f:
    f.write(out)
print("wrote", dest, len(out), "bytes; nodes", len(N), "edges", len(EDGES))
