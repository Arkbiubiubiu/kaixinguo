# 开心果项目工作规则

## 1. 改任何功能前，先查架构世界树

权威文件：`docs/TECH_TREE.md`
可视化：`docs/tech-tree.html`（由 `docs/build_tech_tree.py` 生成；改节点/边后运行它重新生成）
线上：运行看板 `/api/admin?token=…` 顶部「架构世界树」页签，或直接 `/api/admin/tree?token=…`；它读取服务器 `/srv/hgtv-update/tech-tree.html`。更新可视化后必须 `scp docs/tech-tree.html root@192.168.1.54:/srv/hgtv-update/tech-tree.html`，否则线上还是旧树。

任何代码改动（功能、修复、UI、服务端、发版参数）都必须先：

1. 在 `docs/TECH_TREE.md` 里定位到对应节点 ID。
2. 沿 **上游消费者 / 下游依赖 / 孪生节点** 三条边各走一遍。
3. 查 §4 变更影响矩阵，把同类改动需要检查的节点列出来。
4. 再开始改代码；不要只看当前文件。

典型孪生关系（必须一起看）：

- PC `src/main/kotlin/com/hongguotv/pad/Api.kt` ↔ Android `android/core/.../Api.kt`
- PC 页面/播放器 ↔ Android Pad/TV 对应页面
- Pad 与 TV 是同一份 `android/pad` 源码，只是 flavor/包名/遥控器交互不同
- 客户端历史 ↔ 服务端 `history_merge` ↔ 官方 `read_history`
- 版本号/私有参数 ↔ 构建产物 ↔ `version-*.json` ↔ 更新器 ↔ 官网

## 2. 改完必须同步这棵树

如果改动涉及以下任一项，**同一个提交**里更新 `docs/TECH_TREE.md` 和 `docs/tech-tree.html`：

- 新增/删除/合并模块或页面
- 改模块之间的调用关系
- 改接口契约、字段、超时、重试、缓存 TTL
- 改历史/播放/更新/登录语义
- 改端口、域名、证书、发布路径
- 改版本基线（最后一节）

只改实现、不动拓扑的修复，可以不改图，但要在 `TECH_TREE.md` 的“已知不一致/风险”里确认是否新增/消除了一条。

## 3. 当前不可破坏的硬约束

### 播放

- 服务端不把 `bvc2` 直接返回客户端；先兼容接口 H.265，再转码 H.264。
- 转码/直链 URL 必须走有效证书域名；客户端遇到证书/404/5xx 只重取当前集，不连跳。
- 预取 3 集、按集号判重；8 秒卡顿看门狗最多 2 次，第一次降 720。
- 客户端只有一套普通播放器，不再引入第二套 VVC 播放器/控制条。

### 历史

- 历史 = 最近一次**真实播放**：本次进入后真播超过 3 秒才写，写当前 `episode + positionMs + at`。
- 换到低集数正常看会照实更新；只点开/1 秒退出/续播集点开即退不写。
- 合并按 `at` 最新者胜；`positionMs=0` 的旧记录不能覆盖真实播放位置。
- 所有播放入口必须传 `episode + positionMs`；新增入口先查 `TECH_TREE.md` 的入口一致性风险。

### 三端/发布

- Android 版本唯一来源：`android/pad/build.gradle.kts`；PC 版本唯一来源：根 `build.gradle.kts` 的 `pcVersion`。
- 发版必须带全套私有 `-P` 参数；私有参数、cookie、token 绝不入库/入日志。
- Android 通用包 + 三个 ABI 包 + manifest 的 `size/sha256/abis` 必须一致。
- 桌面 APK 目录只放最新 TV/Pad 两个通用包。
- 更新/发布后同步官网清单和镜像；不要手工只改 `url`（会被 mirror_watchdog 覆盖）。

### 验证

- 真机验证只用 LDPlayer `emulator-5554`，绝不装到用户的电视/平板。
- 模拟器不能还原 TV/平板的解码器/GPU/SoC；“模拟器正常”不等于真机正常。
- 改播放/历史/更新，至少跑 `docs/TECH_TREE.md` §7 对应清单。

## 4. 文档边界

- `TECH_TREE.md` 是权威清单；`tech-tree.html` 是同一份数据的可点击视图，两者节点/边必须一致。
- 文档里不写 token、cookie、私钥、真实账号密码。
- 服务端路径以 `TECH_TREE.md` 为准；改端口/服务名时同步更新 §2.7/§2.8 和 §9。
