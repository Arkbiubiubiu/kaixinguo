# 签名与解密模块（未包含在仓库中）

本仓库**不包含**签名实现：`src/main/kotlin/com/hongguotv/pad/sign/` 已被
`.gitignore` 排除。这是有意为之——签名层属于逆向实现，公开会带来合规风险，
也会让作者自建的签名服务失去意义。

## 缺少后会怎样

- 仓库可以正常阅读、审计全部 UI / 播放器 / 业务代码
- 但**直接 clone 编译会报错**（`Api` 等调用方需要这些符号）
- 需要你自备实现，或改为全部走签名服务

## 需要自备的接口面

在该包里提供以下对象/方法即可（名称与签名以调用点为准）：

| 符号 | 用途 |
|---|---|
| `Signer` | 生成请求签名头 |
| `Device` | 设备参数与 `device_id` 派生 |
| `Argus` / `Gorgon` / `Helios` / `Medusa` | 签名算法族 |
| `AesV3` / `Md5Variant` | 加密与摘要变体 |
| `Proto` / `Consts` / `Profile` / `Biz` / `Json` / `Bits` / `Bytes` / `Digest` | 协议组装与工具 |

> 注意：`MediaCrypto.kt` **保留在仓库中**——播放器解码依赖它
> （`deriveKey` / `headerEnd` / `prepare` / `decrypt`），没有它连播放都跑不起来。

## 两种接入方式

1. **放回实现**：把你的 `sign/` 目录放回原路径，Gradle 直接编译通过
2. **走服务端签名**：只实现 `Signer` 的远程调用形态，转发到你的签名服务
   （应用已在「设置 → 服务配置」里支持填写签名服务地址）

## 声明

请不要把任何真实的设备指纹、账号 Cookie、签名密钥提交到仓库。
本项目的 `device.json` 与 `prefs.properties` 已在 `.gitignore` 中排除。
