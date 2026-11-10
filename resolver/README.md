# 本机取流服务（streamsrv）

`main.go` 是 openhongguo 项目 `cmd/streamsrv` 的修改版，编译成
`streamsrv.exe` 随 PC 安装包分发，由客户端在 **127.0.0.1** 上启动，
不对外提供服务。

## 相对上游的修改

1. 增加 `POST /api/playinfo`（与旧中转服务同形，客户端零改动接入）：
   - 请求：`{"vid":"...","want":"1080p"}`，头 `X-Token`
   - 响应：`{"code":0,"data":{definition,height,width,url,backup,spade_a,size,codec}}`
2. 增加 `GET /health`，供客户端启动时探活
3. 去掉未使用的 `/seasons` 路由（`seasons.go` 不再参与构建）

其余逻辑（匿名设备注册、Google 签名头的纯 Go 实现、取流缓存、
「一条身份只用一次」的重试策略）与上游一致。

## 构建

需要上游模块（Apache-2.0）：https://github.com/beilunyang/openhongguo

```sh
# 把本文件放到 openhongguo/cmd/kxgplay/main.go
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" \
  -o streamsrv.exe ./cmd/kxgplay
```

## 运行时

| 环境变量 | 说明 |
|---|---|
| `LISTEN` | 监听地址，默认 `127.0.0.1:9110`（客户端用 `127.0.0.1:38120`） |
| `PROFILE_DIR` | 匿名设备档案目录（客户端用 `%APPDATA%\KaixinguoPC\resolver`） |
| `TOKEN` | `/api/playinfo` 的 `X-Token`，默认 `kxg-local` |

设备档案会在每次取新剧集时注册一台新的匿名设备，只在本机使用；
不经过、也不依赖任何远程服务器。

## Android（平板 / 电视）同样内置

Android 不能起外部进程，所以用 gomobile 把同一份取流核心编成
`libgojni.so` + `classes.jar`，随 APK 打包，由 `DeviceResolver.kt`
在应用内调用（每次取流注册一台新设备，档案落在应用私有目录）。

重新生成（需要 NDK 27 + gomobile）：

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init

cd <openhongguo>/bindings/mobile/core
gomobile bind -target android -androidapi 21 -javapkg dev.openhongguo \
  -trimpath -ldflags="-s -w" -o /tmp/core.aar .

# 拆出来放进 App 工程
mkdir -p android/pad/libs android/pad/src/main/jniLibs
unzip -p /tmp/core.aar classes.jar > android/pad/libs/openhongguo.jar
unzip -o /tmp/core.aar 'jni/*' -d /tmp/aar && \
  cp -R /tmp/aar/jni/* android/pad/src/main/jniLibs/
```

`libs/` 与 `jniLibs/` 是编译产物，不提交到仓库（见 `.gitignore`）。
