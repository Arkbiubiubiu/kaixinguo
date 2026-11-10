# 签名实现（已内置）

从 v1.1.0 起，签名层随源码一起发布，不再排除：

```
src/main/kotlin/com/hongguotv/pad/sign/      # 签名算法（Argus / Gorgon / Helios / Medusa / AesV3 / Proto ...）
src/main/kotlin/com/hongguotv/pad/LocalSign.kt  # 设备档案 + 对外入口
android/core/src/main/kotlin/com/hongguotv/pad/sign/
android/core/src/main/kotlin/com/hongguotv/pad/LocalSign.kt
```

## 目的：下载即用

`LocalSign` 内置了**一套公开分发的设备档案**，直接给阅读/内容接口签名
（`x-argus` / `x-gorgon` / `x-khronos` / `x-medusa` / `x-helios` / `x-ladus` / `x-ss-stub`）。
装好就能浏览推荐、搜索、详情、评论、排行榜、历史，**不需要任何服务端**。

设备档案里没有账号 Cookie、没有作者的个人身份；它与作者自用的设备身份是
两套完全隔离的标识（私用那套只存在于作者本机的 `device.json`，不进仓库）。

## 播放：内置本机取流（PC）

上游取流网关的风控是「一台匿名设备只放行第一次请求，之后 HTTP 200 + 空包」，
固定身份永远拿不到地址。PC 版随安装包带一个 `streamsrv.exe`
（openhongguo 的修改版，见 `resolver/`），启动时只在 `127.0.0.1:38120` 上监听：

1. 客户端要播某一集 → 请求本机 `/api/playinfo`
2. 本机服务注册一台**新的匿名设备**（没有账号、不涉及作者的任何身份）
3. 用它取一次播放地址，随后这台身份作废
4. 地址回给客户端，播放器照常解密播放

每换一集就注册一台新设备，等价于官方 App 首次安装时做的事；
这些身份只存在于用户自己的机器和 IP 上，**与作者的服务器、账号、私用设备身份完全无关**。
用户也可以不用内置服务，在「设置 → 取流服务」填写自己的地址。

> 平板/电视版的取流核心还在接（gomobile），当前版本播放仍需自备取流服务。

## 源码里怎么切

| 入口 | 行为 |
|---|---|
| `LocalSign.sign(origin, path, query, body)` | 内容接口本地签名（公用档案） |
| `LocalSign.signPlay(origin, path, body)` | 取流接口本地签名（aid=1967 档案，best-effort） |
| `PcStore.signUrl` / 设置页「签名服务」 | 留空 = 本地签名；填地址 = 远程代签 |

想换一套设备档案：把 `PROFILE_JSON` 换成你自己注册的档案即可
（字段顺序由 `Profile` 保证，不要改动 `Signer` 的拼接顺序——
签名是 query + body + 时间戳的联合函数，改一个字节都会失配）。

## 声明

- 签名算法仅用于与上游接口通信，不含任何账号凭据
- 不要把真实账号 Cookie、私用设备档案、服务地址提交到仓库
  （`device.json` 与 `prefs.properties` 已在 `.gitignore` 中排除）
- 因使用本客户端产生的任何后果由使用者自行承担
