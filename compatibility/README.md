# 日历宿主兼容性

## 0.9.7 增量验证

2026-11-17 的切页与入口修复继续覆盖三个 APK，各通过 405 项 DEX 契约、30 项资源及 415 项独立 ART 检查。实际手机日历现为 17.6.20（17006020），已完成切页、横滑、旧快捷方式和内部启动调用检查。完整范围、性能采样及限制见 [0.9.7 记录](../CHANGELOG-0.9.7.md)；机器可读记录见 [增量结果](verification-0.9.7-2026-11-17.json)。

## 0.9.6 历史记录

适配范围为用户提供的 `com.coloros.calendar` 17.3.70、17.6.20、17.8.0 APK；模块版本 0.9.6（33）。同一模块根据宿主真实接口解析入口，不要求手工选择版本。

适配前快照：`269a18266889d20f4795fe3a2418e40879d41995`。本次仅修改 CourseAlarm；保留已有课表隔离、删除／撤回、课前提醒、周滑动刷新、透明顶部和三种课程卡片效果。

## 已核实的差异

| 接口 | 17.3.70 | 17.6.20 | 17.8.0 | 模块处理 |
| --- | --- | --- | --- | --- |
| DateTabFoldLayout 切页 | `s(int)` | `t(int)` | `t(int)` | 精确匹配实例方法、参数和 void 返回值 |
| ImportCourseTableHelper 创建面板 | `G(FragmentActivity, boolean)` | `N(FragmentActivity, boolean)` | `N(FragmentActivity, boolean)` | 只接受唯一的准确静态签名，歧义时停止调用 |
| weekAdapter 类型 | `b` | `d` | `d` | 从宿主字段类型查准确六参构造器，不硬编码混淆类名 |
| ViewPager2 切周 | `m(int, boolean)` | 同左 | 同左 | 只调用公开入口，避免同签名内部 `n` 方法 |
| 宿主 Kotlin Unit | `kotlin.s.a` | 同左 | 同左 | 优先宿主单例，避免拿到系统中的另一个 `kotlin.Unit` |

课程详情编辑使用准确的 `Gson.toJson(Object)`，避免误选同参数的 `toJsonTree(Object)`。Kotlin 回调仅在 `invoke` 时执行课程操作；`equals/hashCode/toString` 不触发编辑或周次切换。

## 验证结果与边界

2026-11-17 在 `192.168.137.143:5555`（Android 17 / API 37）验证：

| 宿主版本 | versionCode | DEX 接口 | 资源 | 独立 ART 进程检查 |
| --- | --- | --- | --- | --- |
| 17.3.70 | 17003070 | 401/401 | 30/30 | 410/410 |
| 17.6.20 | 17006020 | 401/401 | 30/30 | 410/410 |
| 17.8.0 | 17008000 | 401/401 | 30/30 | 410/410 |

410 项包含 401 项类／方法／字段反射契约、4 个实际入口解析器、1 组 Kotlin 回调行为、1 组资源查询、3 组合成数据验证。合成数据覆盖：事件与所属课表 ID、重复规则、课程扩展 JSON、ORM 字段转换；30 分钟提醒的开关与节次 JSON 序列化；45/90/100 分钟及旧式／ISO 时长解析、非法时长拒绝。

三个宿主 APK 分别加载到独立 `app_process` 进程。没有安装它们，没有启动宿主 Application、查询或修改 Calendar Provider，也没有切换手机前台。手机系统日历仍是 17.3.70。临时 APK 和契约文件在验证后清理。

**这不是三版完整功能验收。** 还未逐版运行真实 UI、导入／删除／撤回事务、提醒持久化与触发、玻璃滚动和深浅色切换。接口存在及合成数据通过不能证明上述完整流程通过。旧系统、其他 ROM、其他同版本号但不同 SHA 的 APK 均不在此次运行验证范围。

`verify_host_apks.py` 遇到缺失接口、歧义入口或资源缺失返回非零；不会为了通过检查跳过失败项。检查桥接方法时使用包含返回类型的完整 DEX 描述符，避免把协变返回桥接方法误报为缺失。

原始证据位于工作区 `artifacts/calendar-compat-20261117/`，包含 `device-report.json`、各版本 `art-*.log`、APK SHA-256 和 Lint 对照。可随代码保存的精简记录见 [verification-2026-11-17.json](verification-2026-11-17.json)。

## 复现

在仓库根目录使用 Python 3.10+ 与 Android SDK aapt2：

```powershell
python CourseAlarm/tools/verify_host_apks.py `
  --apk 'C:/Users/summer/Downloads/Mobile Devices/日历_17.3.70.apk' `
  --apk 'C:/Users/summer/Downloads/Mobile Devices/日历_17.6.20.apk' `
  --apk 'C:/Users/summer/Downloads/Mobile Devices/日历-17.8.0(17008000).apk' `
  --aapt2 toolchain/android-sdk/build-tools/36.1.0/aapt2.exe `
  --output artifacts/calendar-compat-20261117/static-report.json
```

在 CourseAlarm 目录构建验证包：

```powershell
$env:JAVA_HOME='D:/lsp模块/toolchain/jdk21'
./gradlew.bat :app:assembleRelease -PcoursealarmVerification --console=plain
```

将产物另存，再在上面的 Python 命令加入以下参数运行隔离检查（需要 root；不会安装 APK）：

```powershell
  --probe '验证包的路径.apk' `
  --adb toolchain/android-sdk/platform-tools/adb.exe `
  --serial 192.168.137.143:5555
```

正式发布重新执行 `:app:assembleRelease`，不传 `-PcoursealarmVerification`。检查 DEX 不含 `DeviceVerification` 与 `HostCompatibilityProbe`，再验证签名。验证包含设备测试入口，仅作开发验证，不能作为正式包安装。

## Lint

完整 `lintRelease` 未通过。适配前快照与本次版本均有 137 条 Error（主要涉及已有的低 API 兼容等问题），此次没有新增 Error。对照报告保存在 `artifacts/calendar-compat-20261117/lint-comparison.json`。没有生成 Lint baseline 来掩盖这些问题；它们不能据本次 API 37 的结果视作已修复。
