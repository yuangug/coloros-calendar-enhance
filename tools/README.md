# CourseAlarm 验证工具

以下命令从 **CourseAlarm 工程根目录**运行。先根据要查的问题选脚本：拾光使用的是**模块 APK**，宿主接口检查使用的是**日历 APK**，两者不能互换。

| 要检查什么 | 入口 | 运行条件与结果 |
| --- | --- | --- |
| 拾光学校索引、网页交互、课程转换和 XIT 学期作息 | [run_shiguang_checks.py](run_shiguang_checks.py) · [导入规则](../docs/shiguang-import-ui.md) | 电脑本地，需 Python、Node.js、JDK 21、`org.json:json:20240303` 的 JAR 和由同一份源码构建的模块 APK。使用离线样例，不登录学校网站。 |
| 课程实时活动、保留时间和连堂课的课间进度 | [run_live_checks.py](run_live_checks.py) · [用例与验证范围](livecheck/README.md) | Python 和 JDK 21；检查时间计算，不向手机发通知。 |
| 并发读取课表、本周结束判断和周页面选择 | [run_table_load_checks.py](run_table_load_checks.py) | Python 和 JDK 21；使用模拟数据，不读写实际课表。 |
| 日历类、方法和资源，或实时活动签名模板 | [verify_host_apks.py](verify_host_apks.py) · [verify_live_apks.py](verify_live_apks.py) | 在电脑上读取本地日历 APK，可按版本生成报告。见[宿主兼容记录](../compatibility/README.md)。 |
| 存储后端的批量写入与回滚 | [verify_backend_transactions.py](verify_backend_transactions.py) | 需指定已连接设备；在独立进程中用内存数据测试宿主批处理，不打开真实课表数据库。 |

例如，在已设置 `JAVA_HOME` 的 PowerShell 中：

```powershell
python tools/run_live_checks.py --jdk "$env:JAVA_HOME"
python tools/run_table_load_checks.py --jdk "$env:JAVA_HOME"
```

要检查拾光，请将示例中的 JAR 换成本机路径，先用当前源码构建调试 APK：

```powershell
.\gradlew.bat :app:assembleDebug
python tools/run_shiguang_checks.py --jdk "$env:JAVA_HOME" --json-jar "C:/deps/json-20240303.jar" --apk "app/build/outputs/apk/debug/app-debug.apk"
```
