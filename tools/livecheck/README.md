# 课程实时活动验证

以下命令从 **CourseAlarm 工程根目录**运行。若要验证拾光导入或课表读取，请改看[工具索引](../README.md)。

## 不用手机：验证时间与课间

在 PowerShell 中设置好指向 JDK 21 的 `JAVA_HOME` 后运行：

```powershell
python tools/run_live_checks.py --jdk "$env:JAVA_HOME"
```

[时间用例](CourseOccurrenceCheck.java)覆盖 45／90／100 分钟课程、跨日、夏令时和手动关闭；另检查课前提醒窗口、课中重试，以及下课后保留到期。连堂课分段覆盖不等长节次、没有课间和超出星河岛 SDK 标签上限。对每段实际课间，分别断言休息开始、期间、下一节开始的进度都等于圆点位置，随后继续前进；不分段的总时长进度仍包含休息。

## 有日历 APK：检查接口与模板

```powershell
python tools/verify_live_apks.py --apk "<17.3.70.apk>" --apk "<17.6.20.apk>" --apk "<17.8.0.apk>" --aapt2 "<aapt2.exe>" --output "build/live-template-report.json"
```

这里的输入是**宿主日历 APK**。脚本检查课程通知入口、模型与卡片身份，读取原始签名模板是否包含进度节点或免打扰绑定。Provider 的 `call` 继承自 Android 框架，单独标记为外部依赖。版本覆盖见[宿主兼容记录](../../compatibility/README.md)。

## 有设备：区分卡片构造和实际显示

[CourseShadeLayoutProbe](../../app/src/deviceTest/java/com/lcodexs/coursealarm/CourseShadeLayoutProbe.java) 通过验证包的 `shadeLayout` 操作在日历进程检查左右布局及卸载还原。WakeUp 的 E16 凝光胶囊须在真实通知栏检查：课程提醒卡上的控件由当前卡片引擎的 LevelEView 绘制。离屏测试不验证材质或息屏显示。

独立 ART 检查可用宿主自己的类加载器构造课程提醒，核对实例身份、提醒及起止时间、时区和课程账户。[AstraStageProbe](android/AstraStageProbe.java) 使用实际 SDK 构造分段卡和普通卡，但不发布通知。

以上检查不读取用户课表，不能证明通知已送达。要确认通知栏里有进度条、流体云展开后显示课间，或免打扰在下课时解除，仍需在相应系统环境中用测试课程观察。
