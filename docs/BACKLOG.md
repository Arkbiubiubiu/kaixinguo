# 开心果 — 待办 / 暂定清单（人工维护，勿被自动生成覆盖）

> 记录"官方决定暂时放置 / 后续再做 / 已明确不做"的事项，避免遗忘。日期按 Asia/Shanghai。
> 这份文件是人工维护的，不参与 docs/build_tech_tree.py 的自动生成。

## 暂定放置（PARKED）
- **PC 登录设备指纹"每机唯一"（2026-11-21 暂定放置，用户确认先放着）**
  - 现状：所有 PC 用户共用一套**写死**的设备标识(device_id/iid/cdid)。每机唯一身份已在客户端**生成并持久化**于 PcStore（prefs：`login_device_id`/`login_iid`/`login_cdid`），开关 `login_unique_device` **默认 0（关闭）** → 线上登录行为**零变化**。
  - 阻塞原因：passport 请求体 `device_id` 必须与**签名设备**一致；本地签名（`shared/LocalSign.kt` 的 PROFILE device_token）与远程签名（`Signer.kt`）都绑死固定 device_id。只改客户端 → 同一请求出现两个 device_id → 上游判伪造 → 登录报 **1003**。
  - 启用前置条件：签名侧支持"按客户端设备身份签名 / 做设备注册拿 device_token"。就绪后，把 prefs `login_unique_device=1` 即生效，**无需重新编译发版**。
  - 触发条件：出现"**多台 PC 同时登录导致风控/限流/登录异常**"时再处理。

## 待解决（2026-11-23 现场发现，待处理）
- **刷剧·内容类型串台：选『漫剧』第26部混入真人剧**（PC 发现；安卓待排查）
  - 现象：刷剧页选『漫剧』刷新，前 25 部正常，第 26 部为真人剧。
  - 方向：查 feed 拉取/分页/多 feedType 合并/跨天去重 是否严格按 feedType(36 漫剧 / 39 真人剧 / 16 推荐)过滤；安卓端同步排查是否同样。
- **刷剧·预取未按约定生效（应预取 5 集，实际每集仍现读）**（PC / 安卓）
  - 约定：刷剧预取=5 级；某集暂停约 5 分钟回来，后续 5 集应秒播。
  - 现象：实际每集仍需加载一下。
  - 方向：查预取是否真触发、缓存是否命中、后续集 vid/取址获取时机（预取依赖先拿到后续集 id）。

## 可选 / 待真机验证
- **追更/预约系统通知**（2.2.218 起已上线）：需用户授权通知权限，观察"到点有新集时是否真弹"。目前仅验证到：权限框弹出、通知渠道 `follow_updates(追更提醒)` 创建、启动不崩；实际弹通知需真机 + 有追更更新时才看得到。

## 用户已明确"不做"（记录，勿再提）
- TV 登录配对码前置
- PC 高 DPI 跟随系统
- PC 关窗二次确认
- PC 更新下载可取消
（均为 2026-11-21 用户决定不做）

## 最近已完成（留档）
- 2026-11-21 发版 2.2.218 / 1.2.102：续播统一、TV失败可重试、Pad刷剧退出/失败重试、失败/空分离、超长剧分批渲染、PC写盘移IO+预览不污染、追更通知、明文收窄、服务端超时预算收敛(playinfo 55s×1)、缓存版本 bump(v4/v4/v3)、工具链 check_versions补sha/abi + release.py GitHub自动发布 + check_api_parity。
- 更早：转码视频改走 Cloudflare(tc.fabao.xin) 修漫剧每秒卡；公网统一(删cpolar/kxg迁CF/去自签信任)。

## 维护铁律（长期）

- **每次改动软件后必须同步更新架构树**：改 `docs/build_tech_tree.py` 对应节点/边 → 跑 `docs/build_tech_tree.py` + `tools/check_tree.py` → scp `tech-tree.html` 到 `/srv/hgtv-update/` → 提交。树永远 = 最新真相，不得遗留旧描述。
- 注意：**不要用 PowerShell 的 `Get-Content`/`Set-Content` 读写含中文的文件**（会按 GBK 转坏 UTF-8）；改用 Python 或 `-Encoding UTF8` 同时读写。
