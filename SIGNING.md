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

## 播放为什么要另配取流服务

上游的取流网关（`multi_video_model`）现在的风控是：

> **一台匿名设备只放行第一次请求，之后一律 HTTP 200 + 空包。**

客户端只有一个固定身份，第二次取流就永远拿不到地址；靠客户端自己注册新设备
可以绕过，但等于让每个用户高频注册匿名设备，风险与合规成本都不可接受，
所以本项目**不在客户端内置取流**。想看视频请在
「设置 → 服务配置 → 取流服务」填写你自己的服务地址（需提供 `POST /api/playinfo`，
返回 `{code:0,data:{url, backup, spade_a, definition, height, size}}`）。

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
