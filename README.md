# 开心果（Kaixinguo）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/Arkbiubiubiu/kaixinguo)](https://github.com/Arkbiubiubiu/kaixinguo/releases)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android%20TV%20%7C%20Android-2ea44f)](#下载)

**三端短剧客户端**：同一套数据层，三个端各自有独立界面。

## 下载

| 端 | 文件 | 说明 |
|---|---|---|
| PC（Windows） | [kaixinguo-1.1.1.msi](https://github.com/Arkbiubiubiu/kaixinguo/releases/download/v1.1.1/kaixinguo-1.1.1.msi) | 安装包，内含 FFmpeg（GPLv3）与本机取流服务 |
| 平板 | [pad-pad-debug.apk](https://github.com/Arkbiubiubiu/kaixinguo/releases/download/v1.1.0/pad-pad-debug.apk) | Android 8.0+（播放需自备取流服务） |
| 电视 | [pad-tv-debug.apk](https://github.com/Arkbiubiubiu/kaixinguo/releases/download/v1.1.0/pad-tv-debug.apk) | Android TV 8.0+（播放需自备取流服务） |

| 端 | 技术 | 源码目录 | 状态 |
|---|---|---|---|
| **PC** | Kotlin + Compose Desktop | `src/`（本仓库根） | ✅ 已完成 |
| **电视** | Kotlin（Android TV 源集） | `android/pad/src/tv/` | ✅ 已编译通过 |
| **平板** | Kotlin（Android 源集） | `android/pad/src/main/` | ✅ 已编译通过 |

三个端共用 `core` 数据层（接口、签名对接、播放地址解析），界面各自独立：
PC 用 Compose Desktop，电视/平板是 Android 原生。同一版本发布时三端一起出。

> ✅ **PC 版下载即看**：内容接口内置签名，取流用随安装包分发的本机服务（只监听 127.0.0.1），
> 装好直接浏览 + 播放，不需要填任何服务器地址、不需要账号。
> 🔧 想换成自己的取流服务，在「设置 → 服务配置 → 取流服务」填地址即可；留空用内置。
> 📱 平板/电视版目前内容开箱即用，播放需自备取流服务（客户端侧的取流核心还在接）。
> 与任何内容平台的官方均无关联，仅供学习与技术研究。

## 功能

- **推荐**：Hero 半屏主推 + 海报墙，切 tab / 换一批、骨架屏与错峰进场动画
- **刷剧**：沉浸式信息流，滚轮换剧，弹幕横穿（9 秒 / 分车道 / 密度字号可调）
- **播放器**：倍速、清晰度、选集（分段+8 列）、音量（点击静音 / 悬停竖条）、
  弹幕开关与设置、评论面板（单集评论+回复）、表情、下载当前集、竖屏 / 小窗
- **详情**：选集 / 剧评（发评+五星）/ 相关推荐三 tab，本剧系列切换，备案号
- **找剧**：8 行筛选 + 搜索 + 自动翻页
- **排行榜**：大类 + 子榜两行 tab，筛选浮层，行卡评分与热度
- **新剧**：推荐流 + 上新日历（按周翻页）
- **追更 / 预约 / 历史**：双 tab、更新提醒（本地基线比对）、云端历史、位置徽标
- **我的 / 设置**：设备匹配、主题（深/浅/跟随系统）、播放偏好持久化、缓存与下载管理

## 技术栈

| 层 | 技术 |
|---|---|
| UI | Kotlin 2.0 + Compose Multiplatform 1.7（Desktop） |
| 播放 | FFmpeg（内置 exe，remux/解码）+ 本机取流服务（streamsrv，仅 127.0.0.1） |
| 数据 | Kotlin 协程 + org.json，内置本地签名（x-argus / x-gorgon / x-khronos / x-medusa） |
| 打包 | Gradle + jpackage（exe / MSI） |

## 目录结构

```
src/main/kotlin/
├── com/kaixinguo/pc/      # 桌面端界面（本项目原创）
│   ├── Main.kt            # 应用壳、各页面、设置
│   ├── Player.kt          # 播放器（控制栏、弹幕、评论面板、浮层）
│   ├── DetailScreen.kt    # 详情页
│   ├── Ui.kt              # 设计 token 与通用组件
│   ├── Downloader.kt      # 单集下载（ffmpeg remux）
│   └── PcStore.kt         # 本地设置/历史/偏好持久化
└── com/hongguotv/pad/     # 数据层（作者自有的平板/TV 端工程副本）
```

## 构建

环境：**JDK 21**、**Gradle 8.7**（或使用自带 wrapper）

```bash
# 可运行发行版（app/开心果/开心果.exe）
gradle createDistributable

# Windows 安装包
gradle packageMsi
```

产物：
- `build/compose/binaries/main/app/开心果/开心果.exe`
- `build/compose/binaries/main/msi/开心果-1.0.0.msi`

## 服务配置

PC 版**开箱即用**（内容内置签名 + 本机取流服务），不需要任何配置。可选覆盖：

| 项 | 说明 |
|---|---|
| 取流服务 | 留空 = 用内置本机取流（推荐）；填地址 = 用你自己的 `POST /api/playinfo` 服务 |
| 签名服务 | 留空 = 用内置本地签名（推荐）；填地址 = 走你自己的代签服务 |
| 取流令牌 | 自备取流服务的 `X-Token`（可留空） |
| 历史基址 | 云端历史接口基址（可留空） |

保存后立即生效，无需重启。

## 免责声明

- 本项目为第三方开源客户端，与任何内容平台官方无关联、未获授权
- 不内置、不代理、不托管任何内容，也不提供任何在线服务
- 使用者需自行准备合规服务端与账号，自行承担使用风险
- 仅用于学习与技术研究；请遵守当地法律法规与平台服务条款

## 许可证

[GPL-3.0](LICENSE)。安装包内含 FFmpeg（GPLv3 构建），
第三方组件与授权详见 [NOTICE](NOTICE)。

## 作者

- 作者：**Arkbiubiubiu**（GitHub）
- 仓库：https://github.com/Arkbiubiubiu/kaixinguo
