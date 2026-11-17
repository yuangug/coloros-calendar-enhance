# ColorOS 日历增强 0.9.6（33）

2026-11-17。适配用户提供的日历 17.3.70、17.6.20、17.8.0，同一个模块 APK 自动选择宿主接口。

适配前已提交快照 `269a18266889d20f4795fe3a2418e40879d41995`，分支为 `codex/calendar-multi-version`。本次没有修改 FQExport，也没有覆盖已有分析材料与实机证据。

## 修改

- 底栏切换兼容 `DateTabFoldLayout.s(int)` 与新版 `t(int)`。
- 原生创建课表面板按唯一的准确静态签名解析，覆盖旧版 `G` 与新版 `N`。
- 周课表适配器从宿主 `weekAdapter` 字段解析，核对准确六参构造器，覆盖 `b`／`d` 混淆差异。
- 所有主动跳周统一调用 ViewPager2 的公开 `m(int, boolean)` 入口，避免误选同签名的内部方法。
- 编辑课程准确调用 `Gson.toJson(Object)`，避免按参数任意选择 `toJsonTree(Object)`。详情时间解析也改为明确的 `fromJson` 方法。
- Kotlin 回调返回宿主 `kotlin.s` 的单例。系统自身也可能提供 `kotlin.Unit`，因此优先解析宿主类型。代理的对象方法不触发编辑、添加或选周动作。
- 新增可重复执行的 APK 契约检查与仅验证包包含的 ART 探针。保留删除／撤回、课前提醒、玻璃课程与顶部透明方案现有实现。

## 验证与交付

- 三个 APK 均通过 401 项 DEX 接口和 30 项资源检查。
- `192.168.137.143:5555` 上分别运行三个独立 ART 进程，每版 410 项检查全部通过。包含实际兼容解析器、Kotlin 代理、事件 ID／课表 ID／重复规则／扩展 JSON、ORM 映射、30 分钟提醒配置与 45/90/100 分钟节次序列化、合法及非法时长。
- 正式 `assembleRelease` 通过，v2 签名验证通过，DEX 不包含 `DeviceVerification` 和 `HostCompatibilityProbe`。
- 完整 Lint 未通过：与适配前快照相比，Error 均为 137 条，没有新增 Error。本次 73 条 Warning，快照 72 条；唯一额外 Warning 是资源密度目录提示，资源文件未修改。未将既有错误隐藏为基线。
- 正式模块已安装到指定设备：`io.github.yuangug.coursealarm`，versionCode=33，versionName=0.9.6。系统日历保持 17.3.70；未强制停止或打开宿主，重新启动日历进程后加载新模块。

没有逐版安装系统日历做完整 UI、数据库增删改与撤回、提醒触发、材质滚动回归。隔离接口及合成数据检查不能替代这些验收，详见 [兼容报告](compatibility/README.md) 和 [机器可读结果](compatibility/verification-2026-11-17.json)。

交付 APK：`D:/lsp模块/dist/ColorOS日历增强-v0.9.6-release.apk`

APK SHA-256：`3aeb8331fc0b5484c6760967ae5e3a223e7deabff656d6a4af2dd8734b79c699`

证据目录：`D:/lsp模块/artifacts/calendar-compat-20261117/`。安装结果见 `install.txt`、`installed-version.txt`，签名见 `signature.txt`，构建见 `release-build.log`，Lint 对照见 `lint-comparison.json`。
