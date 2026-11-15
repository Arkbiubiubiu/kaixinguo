# 开心果 · 修复顺序（2026-11-15）

> 由 `docs/build_tech_tree.py` 生成，和看板「架构世界树 → 修复顺序」同源。
> 原则：**先建安全网，再删死代码，再统一 owner，再拆 god node，最后做机械拆分。**

## 总原则

1. 一次只做一个高风险阶段；高风险阶段不叠加发布。
2. 每个阶段先有基线、开关、回滚，再改代码。
3. 先删 100% 无调用的东西，再动还在运行但无效的路径，最后动兼容层。
4. 先统一事实 owner，再拆文件；先分域，再抽共享模块。
5. 只移动代码的机械拆分放在最后，避免 refactor 期间大范围 rebase。

## 阶段总表

| 顺序 | 阶段 | 状态 | 风险 | 规模 | 为什么现在 |
|---|---|---|---|---|---|
| 0 | 观测与安全网 | 已完成（2026-11-15，2.2.172） | 低 | S | 没有 requestId、功能开关、黄金用例和回滚手段，后面任何重构都无法证明“没坏”。这是所有阶段的前置条件。 |
| 1 | 零行为死代码清理 | 已完成（2026-11-15，2.2.171） | 低 | S | 先删确定无调用的东西，缩小后面重构的噪声和安全面；这一步不应该改变任何用户可见行为。 |
| 2 | 缓存/状态版本化 | 已完成（2026-11-15，2.2.172） | 中 | M | 后面要改历史字段和取流响应；如果缓存没有 schema 版本，旧结构会继续命中，表现成“代码改了线上还是旧的”。 |
| 3 | 历史 owner 统一 | 已完成（2026-11-15，2.2.177）：PC 实机开启 history_v2；shadow diff=1（同集重看秒数），共享合并/服务端模块/Reporter 全部落地 | 高 | M | 这是用户最痛、最容易反复坏的地方；而且历史逻辑长在播放器里，先抽出 HistoryReporter 也能直接降低播放器复杂度。 |
| 4 | 播放器分层 | 已完成（2026-11-15，2.2.180）：PlaybackSourcePlanner + PlaybackGuard + PlayerUiState + PlaybackSession 状态已抽取；引擎实例保留平台适配 | 高 | L | 播放器是最大 god node；历史 reporter 已在 Phase 3 抽出，这里继续拆预取、会话和 UI 状态，才能避免以后改播放/历史互相踩。 |
| 5 | C-API 分域 + 共享核心 | 已完成（2026-11-15，2.2.182）：领域 facade + 10 个核心文件 shared 源目录；Api 内部按增量继续搬迁 | 高 | L | 先分域再加共享模块，避免把 3741 行的 god object 整体搬两次；分域后 PC/Android 的契约测试才有落点。 |
| 6 | 取流 Resolver 收敛 | 已完成（2026-11-15，2.2.190）：异步转码 job（POST/GET /transcode）+ 202 pending 轮询 + 播放优先队列；客户端预取等 job 完成 | 高 | L | 播放链路是第二大可感知风险；前面播放器已分层、缓存已版本化，才能安全改契约、超时和转码队列。 |
| 7 | 服务端控制面拆分 | 已完成（2026-11-15，2.2.190）：ROLE=static(8080)/api(8082) 两进程 + hgtv-api.service；dual_server/8080 按 canonical 路由分流 | 中 | M | 历史/取流接口刚稳定，适合把静态、内容、历史、看板拆开；再晚会把已稳定的代码又搬一次。 |
| 8 | 领域合并 | 已完成（2026-11-15，2.2.188）：SeasonService + ShelfRepository + PassportClient 边界全部落地 | 中 | M | C-API 已分域，才能把收藏/点赞/追更/预约、季发现、登录协议合并成领域服务，而不是在大对象里继续加方法。 |
| 9 | 更新链统一 | 已完成（2026-11-15，2.2.190）：tools/release.py 串起版本→构建→dex 私参校验→manifest→上传→远端 sha 校验→桌面留包 | 中 | M | 更新链相对独立，可在核心稳定后并行；manifest/镜像/版本号是发布期最容易出错的部分，必须收成一条流水线。 |
| 10 | UI 大文件机械拆分 | 已完成（2026-11-15，2.2.190）：PC Main 拆 8 文件、Android MiscScreens 拆 9 文件、Store/PcStore 拆 Shelf/History/Notice | 中 | M | 领域/API 稳定后再拆页面文件，避免重构期间大范围 rebase；只搬代码不改行为，风险主要来自漏搬/焦点。 |
| 11 | 树/CI/治理 | 已完成（2026-11-15，2.2.186）：tools/check_tree.py + GitHub tree-check workflow | 低 | S | 把“改前查树、改后更新树”变成自动检查，否则树会随时间失真；这是长期防止再乱的机制。 |
| 12 | 旧兼容清理 | 已完成（2026-11-15，2.2.190）：版本分布全部 ≥2.2.146 后删除 /sync + room/progress 状态；看板 VVC 卡片与 manifest url 已移除 | 中 | S | 只有前面都稳定、并且有版本分布/访问证据后，才删旧同步后端和过时指标；否则会误伤老客户端。 |

## 详细阶段

### 0. 观测与安全网

**状态**：已完成（2026-11-15，2.2.172）

**风险/规模**：低 / S

**为什么现在**：没有 requestId、功能开关、黄金用例和回滚手段，后面任何重构都无法证明“没坏”。这是所有阶段的前置条件。

**本阶段事项**：
- 客户端→/api/fetch/playinfo→streamsrv→oroplay→转码 全链路带 requestId
- 看板增加：播放失败阶段、resolver fallback 次数、转码队列、历史合并 diff、缓存命中率
- 加 feature flag：history_v2 / resolver_v2 / player_session_v2，可按设备/版本开关
- 建立黄金用例：播放矩阵、历史矩阵、解析 fixture、更新 manifest 校验
- 服务端状态备份/恢复脚本：/var/lib/hgtv-sync、转码产物、manifest

**可能波及**：
- 日志量、看板性能；不改产品行为

**怎么保证不坏**：
- 日志脱敏，不写 cookie/token
- 采样+保留期，避免磁盘爆
- 先在 LDPlayer 验证，再上看板

**回滚**：关闭新增日志/面板；功能开关全部保持旧路径

### 1. 零行为死代码清理

**状态**：已完成（2026-11-15，2.2.171）

**风险/规模**：低 / S

**为什么现在**：先删确定无调用的东西，缩小后面重构的噪声和安全面；这一步不应该改变任何用户可见行为。

**本阶段事项**：
- 删除 android/src/main/java/.../HomeFeed.kt（未编译的 395 行旧副本）
- 删除 RemoteVideo.saveDecrypted 和 prepare(allowVvc)（VVC 本地播放器已删）
- 删除 playinfo.py 旧直连整段：_attempt/_pick/_pick_all/_sign/_decrypt_url 及外部签名配置
- 删除 oroplay 的 force_fallback 生产分支（或只保留 env 调试）
- 清理 PC FileDownload 等无用 import

**可能波及**：
- 只影响构建和死代码；若误删会在编译期暴露

**怎么保证不坏**：
- grep 全仓调用点
- PC/Android 双端编译
- LDPlayer 冒烟：首页/详情/播放/历史/更新

**回滚**：git revert 单个提交；不涉及数据迁移

### 2. 缓存/状态版本化

**状态**：已完成（2026-11-15，2.2.172）

**风险/规模**：中 / M

**为什么现在**：后面要改历史字段和取流响应；如果缓存没有 schema 版本，旧结构会继续命中，表现成“代码改了线上还是旧的”。

**本阶段事项**：
- relay/playinfo/streamsrv/tierCache/DetailCache/FeedCache/heat 的 key 加 schemaVersion
- 每层明确 TTL 和失效 owner；部署时 bump 版本或清对应层
- history/progress/room JSON 加 version 字段；写迁移脚本，保留一版旧 reader
- 看板展示各层缓存命中率与版本分布

**可能波及**：
- 所有内容/播放/历史；发布后会有一次冷缓存，上游请求短时上升

**怎么保证不坏**：
- 双读/双写一版；旧 reader 保留
- 预热脚本；限流观察
- 回滚只需恢复旧 key/旧 reader

**回滚**：恢复旧版本 key/reader；缓存可丢弃重建

### 3. 历史 owner 统一

**状态**：已完成（2026-11-15，2.2.177）：PC 实机开启 history_v2；shadow diff=1（同集重看秒数），共享合并/服务端模块/Reporter 全部落地

**风险/规模**：高 / M

**为什么现在**：这是用户最痛、最容易反复坏的地方；而且历史逻辑长在播放器里，先抽出 HistoryReporter 也能直接降低播放器复杂度。

**本阶段事项**：
- 从 Player/PlayerScreen 抽 HistoryReporter（只负责 3 秒门槛/上报/合并调用）
- 建 HistoryRepository：本地缓存、服务端一份合并、官方 read_history 只做 adapter
- PC 历史页改为走同一套 HistoryRepository，不再直读 officialHistory
- 服务端 history_merge 独立成模块；先 shadow 计算新旧结果，diff 为 0 再切
- 旧 /sync 暂不删，只停止新写入；Phase 12 再清理

**可能波及**：
- 历史页、继续看、详情/首页/追更/排行/新剧入口、官方同步、PC/Android

**怎么保证不坏**：
- 历史矩阵：真播3秒/1秒退出/低集数/续播秒数/跨端/旧数据
- shadow diff 日志
- feature flag + 历史文件备份
- 先 LDPlayer，再一台用户授权设备

**回滚**：关 history_v2，回旧合并/旧读路径；需要时恢复历史 JSON 备份

### 4. 播放器分层

**状态**：已完成（2026-11-15，2.2.180）：PlaybackSourcePlanner + PlaybackGuard + PlayerUiState + PlaybackSession 状态已抽取；引擎实例保留平台适配

**风险/规模**：高 / L

**为什么现在**：播放器是最大 god node；历史 reporter 已在 Phase 3 抽出，这里继续拆预取、会话和 UI 状态，才能避免以后改播放/历史互相踩。

**本阶段事项**：
- 抽 PrefetchController / SourcePlanner（选档、头部、失效、预取并发）
- 抽 PlaybackSession（源、引擎、错误分类、看门狗、切集）
- 抽 PlayerUiState（控制条/浮层只订阅状态）
- 拆 PlayerScreen.kt / Player.kt；控制条视觉和键位保持不变
- 播放器只通过接口调用 HistoryReporter/Resolver，不直接读写 Store/Api

**可能波及**：
- 播放、预取、队列、切集、历史、弹幕/评论、TV 焦点、错误提示

**怎么保证不坏**：
- 播放矩阵：普通/VVC/兼容接口/转码/证书/404/seek/倍速/自动连播
- 同一套控制条截图对比
- canary + feature flag
- 关键路径日志 requestId

**回滚**：关 player_session_v2，回旧 PlayerScreen/Player；不移民数据

### 5. C-API 分域 + 共享核心

**状态**：已完成（2026-11-15，2.2.182）：领域 facade + 10 个核心文件 shared 源目录；Api 内部按增量继续搬迁

**风险/规模**：高 / L

**为什么现在**：先分域再加共享模块，避免把 3741 行的 god object 整体搬两次；分域后 PC/Android 的契约测试才有落点。

**本阶段事项**：
- Api.kt 按 HttpCore/Catalog/Detail/Playback/History/Account/Social 拆文件，保留 Api facade
- 所有调用方先继续用 facade，逐个切到领域 API
- 建立 PC/Android 契约 fixture 和 diff 检查（只允许三处平台差异）
- 分域稳定后抽 KMP/独立 jar；平台只保留取流兜底/设备默认值
- 排行榜/搜索/详情解析的旧重复代码顺手收口

**可能波及**：
- 所有页面和领域；构建结构变化；解析字段风险

**怎么保证不坏**：
- facade 保持调用点不变
- 解析 fixture 黄金对比
- PC/Android 双端构建+冒烟
- 先 Android 后 PC 或反之，单端 canary

**回滚**：保留旧 Api object 转调新实现；构建失败直接 revert 模块化提交

### 6. 取流 Resolver 收敛

**状态**：已完成（2026-11-15，2.2.190）：异步转码 job（POST/GET /transcode）+ 202 pending 轮询 + 播放优先队列；客户端预取等 job 完成

**风险/规模**：高 / L

**为什么现在**：播放链路是第二大可感知风险；前面播放器已分层、缓存已版本化，才能安全改契约、超时和转码队列。

**本阶段事项**：
- 删/闸掉 streamsrv SDK 兜底和 PC localPlay 死路；失败快速返回
- 统一 Resolver 契约：tier 列表、错误码、超时预算（客户端/streamsrv/转码一致）
- 转码改异步 job + 轮询/回调；当前播放优先于预取，队列可见
- 拆 streamsrv 为 resolver/cache/http；拆 oroplay 为 identity/tiers/transcode-queue/server
- 客户端 SourcePlanner 与 Resolver 契约对齐

**可能波及**：
- 所有播放、VVC/兼容/转码、预取、失败提示、卡顿重试

**怎么保证不坏**：
- 播放矩阵加：1080转码/队列满/上游502/超时/证书/404
- 记录每段耗时和 fallback 原因
- 旧 resolver 二进制/配置保留一版
- canary

**回滚**：切回旧 resolver/旧服务二进制；关闭异步转码开关；恢复旧缓存

### 7. 服务端控制面拆分

**状态**：已完成（2026-11-15，2.2.190）：ROLE=static(8080)/api(8082) 两进程 + hgtv-api.service；dual_server/8080 按 canonical 路由分流

**风险/规模**：中 / M

**为什么现在**：历史/取流接口刚稳定，适合把静态、内容、历史、看板拆开；再晚会把已稳定的代码又搬一次。

**本阶段事项**：
- serve.py 拆 static_update / content_api / library_api / telemetry_admin
- admin + 世界树独立进程/模块，重启不影响历史/取流
- 统一 router：/admin 与 /api/admin 只保留一个 canonical；静态 handler 与 dual_server 共享
- admin token 改 cookie/短时票据（可选，先保留 query 兼容）

**可能波及**：
- 看板、历史、fetch/playinfo、静态/更新、路由

**怎么保证不坏**：
- 每个路由的 contract test
- zero-downtime 重启/回滚
- 8080/8081 双入口 smoke
- 访问日志对照

**回滚**：systemd 切回旧 serve.py；路由前缀保留兼容

### 8. 领域合并

**状态**：已完成（2026-11-15，2.2.188）：SeasonService + ShelfRepository + PassportClient 边界全部落地

**风险/规模**：中 / M

**为什么现在**：C-API 已分域，才能把收藏/点赞/追更/预约、季发现、登录协议合并成领域服务，而不是在大对象里继续加方法。

**本阶段事项**：
- ShelfRepository + ShelfKind：收藏/点赞/追更/预约共用本地缓存和行模型
- SeasonService：seasons/nextSeason/fillMissingSeasons 共用缓存和主创校验
- PassportClient：PC/Android 共用短信/MFA/错误码，UI 各自保留
- 旧 Api 方法保留一版转调，逐步删

**可能波及**：
- 书架/追更/预约、详情季切换、登录/配对、未登录态

**怎么保证不坏**：
- 登录/未登录 fixture
- 季边界：缺季/改名续集/未上线/下架
- 配对码测试
- 旧方法转调对比

**回滚**：领域服务内部转调旧 Api；关闭新实现开关

### 9. 更新链统一

**状态**：已完成（2026-11-15，2.2.190）：tools/release.py 串起版本→构建→dex 私参校验→manifest→上传→远端 sha 校验→桌面留包

**风险/规模**：中 / M

**为什么现在**：更新链相对独立，可在核心稳定后并行；manifest/镜像/版本号是发布期最容易出错的部分，必须收成一条流水线。

**本阶段事项**：
- UpdaterCore：manifest 拉取/镜像/sha256/重试共享；PC MSI/Android ABI 只留安装 adapter
- 一份 manifest schema（packages + abis）；PC/Android 清单同源生成
- 发布流水线：版本号→构建→sha/ABI→manifest→官网→看板
- mirror_watchdog 只发现镜像，不再改 manifest 的 url；旧字段保留一版兼容

**可能波及**：
- 三端更新、官网下载、旧客户端救急 URL、发布流程

**怎么保证不坏**：
- manifest schema 校验
- ABI/MSI 全矩阵
- 旧版本客户端下载测试
- 发布前 sha 对比

**回滚**：保留旧 manifest/旧 updater；重新发布上一版即可

### 10. UI 大文件机械拆分

**状态**：已完成（2026-11-15，2.2.190）：PC Main 拆 8 文件、Android MiscScreens 拆 9 文件、Store/PcStore 拆 Shelf/History/Notice

**风险/规模**：中 / M

**为什么现在**：领域/API 稳定后再拆页面文件，避免重构期间大范围 rebase；只搬代码不改行为，风险主要来自漏搬/焦点。

**本阶段事项**：
- PC Main.kt 拆 AppShell + 每页一文件
- Android MiscScreens.kt 拆 Find/Rank/New/History/Library/Account/Settings/Creator
- PlayerChrome.kt 拆 BaseChrome + 清晰度/倍速/选集/弹幕浮层
- Store/PcStore 按设置/账号/历史/书架/缓存拆存储接口（schema 共享）

**可能波及**：
- 所有 UI 布局/焦点/滚动/空态/错误态

**怎么保证不坏**：
- PC/Pad/TV 截图对比
- 遥控器焦点路径测试
- 只移动不改逻辑；单文件提交便于 review
- LDPlayer 全页面冒烟

**回滚**：按页面 revert；不涉及数据/接口

### 11. 树/CI/治理

**状态**：已完成（2026-11-15，2.2.186）：tools/check_tree.py + GitHub tree-check workflow

**风险/规模**：低 / S

**为什么现在**：把“改前查树、改后更新树”变成自动检查，否则树会随时间失真；这是长期防止再乱的机制。

**本阶段事项**：
- 给边加 runtime/build/deploy/copy/data 类型
- CI 校验关键节点文件/入口存在；检查 TECH_TREE.md 与 tech-tree.html 节点一致
- 每次发版记录：改了哪些节点、跑了哪些矩阵
- 看板增加历史/播放/更新关键指标，异常时能从节点跳到日志

**可能波及**：
- 只影响流程和文档，不影响运行时

**怎么保证不坏**：
- CI 先 warning 后 blocking
- 每季度评审一次树

**回滚**：CI 规则可降级为 warning；文档保留历史版本

### 12. 旧兼容清理

**状态**：已完成（2026-11-15，2.2.190）：版本分布全部 ≥2.2.146 后删除 /sync + room/progress 状态；看板 VVC 卡片与 manifest url 已移除

**风险/规模**：中 / S

**为什么现在**：只有前面都稳定、并且有版本分布/访问证据后，才删旧同步后端和过时指标；否则会误伤老客户端。

**本阶段事项**：
- /sync + progress.json + room-*.json：确认无老版本流量后删除
- 看板 VVC 使用卡片：改成转码/播放失败统计或移除
- 旧 manifest url / mirror 兼容字段：按最低支持版本决定保留期
- 删除已迁移的旧 Api/Store/Resolver 转调层

**可能波及**：
- 老客户端、历史/收藏同步、看板指标、旧下载链接

**怎么保证不坏**：
- 看版本分布和 /sync 访问流水
- 至少保留一个发布周期的兼容
- 删除前备份状态文件

**回滚**：恢复旧路由/旧字段；状态文件从备份还原

## 通用安全网（每个阶段都适用）

1. **基线快照**：改前保存 API 响应 fixture、历史 JSON、播放矩阵结果、PC/Pad/TV 截图。
2. **黄金用例**：播放矩阵（普通/VVC/兼容/转码/证书/404/seek/倍速/连播）、历史矩阵（3秒/1秒/低集数/续播/跨端）、解析 fixture、manifest 校验。
3. **功能开关**：`history_v2` / `resolver_v2` / `player_session_v2`，按设备/版本开关；默认旧路径。
4. **影子模式**：历史合并、解析结果先新旧双算，只记 diff 不切换；diff 为 0 再切。
5. **canary**：先 LDPlayer，再一台用户授权设备；不直接全量。
6. **可观测**：requestId 贯穿客户端→API→resolver→转码；看板按阶段看失败率/耗时/fallback。
7. **备份/回滚**：服务端状态、manifest、旧二进制保留一版；数据迁移必须可逆。
8. **树同步**：每个阶段结束更新 TECH_TREE.md / tech-tree.html，并记录改了哪些节点、跑了哪些矩阵。

## 出问题后的闭环

1. **定位**：用 requestId 找到失败阶段（客户端/API/resolver/转码/CDN），再用 feature flag 判断是否新路径。
2. **止损**：关开关；服务端切回旧二进制/旧路由；数据问题恢复备份。
3. **复现**：把失败样本加入黄金用例，确认旧路径正常、新路径能复现。
4. **修因**：在对应树节点上改，不在 UI 层打补丁；同时检查上下游和孪生节点。
5. **再上**：小流量/单设备 canary，diff 清零后扩大。
6. **沉淀**：更新树的“已知风险/评审”，把这次 failure 变成回归用例。

## 不要做的事

- 不要一次同时做历史 + 播放器 + Resolver 三个高风险重构。
- 不要为了“统一”合并 PC/Android UI、ffmpeg/ExoPlayer、服务端代取/本机兜底。
- 不要在没有观测/回滚的情况下改历史合并或取流契约。
- 不要先抽共享模块再分域，否则会把 god object 搬两次。
- 不要在没有版本分布证据时删旧兼容（/sync、旧 manifest、旧字段）。
