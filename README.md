# 课程早晨闹钟（CourseAlarm）

modern libxposed **API 102** 模块 · 注入目标：ColorOS 日历 `com.coloros.calendar`（+ 时钟 `com.coloros.alarmclock` 兜底）· logcat tag `COURSEALARM`

在 ColorOS 日历的课表页注入「导入课表 / 导出课表」，并把「次日第一节课」变成时钟 App 里的**一次性闹钟**。

## 功能

| 能力 | 说明 |
|---|---|
| 导入课表 | Excel(.xlsx) / ICS(.ics) / 教务系统课表页(HTML 或 jwglxt 的 `kbList` JSON) / 「下载」目录直选；**格式按文件内容判定**，不看扩展名 |
| 教务解析 | 正方 jsxsd（`kbcontent` + `font title`）、强智/URP 表格、正方新版 JSON；同格多课（`------` 分隔）、rowspan/colspan、数字节次行头、单双周都能拆 |
| 写入 | 替换 / 追加 / **新建一张课表后导入**（走宿主 `CreateCourseTableViewModel.saveCoursesAndTable`，失败退宿主建表页，绝不静默写回旧表） |
| 导出 | `.xlsx`（教务系统同版式，双 sheet：课表网格 + 课程明细）、`.ics`（带 `X-LSPX-WEEKS/NODES/DAY/COLOR` 可无损回灌） |
| 闹钟 | 每晚 21:00 依据次日课程在时钟 App 建一次性闹钟（`AiSupportContentProvider.addAlarm`），幂等清理只删自己设的 |

## 构建

需要 **JDK 21** + **Android SDK 36**（`build-tools 36.x`）。在仓库根建 `local.properties`：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

装机前确认元数据齐全（缺一个框架就不加载）：

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep META-INF/xposed
#   META-INF/xposed/module.prop        (minApiVersion / targetApiVersion = 102)
#   META-INF/xposed/java_init.list     (com.lcodexs.coursealarm.CourseAlarmEntry)
#   assets/xposed_init                 (同一个入口类)
# 且不应出现 io/github/libxposed/*   （compileOnly，打进包会类冲突）
```

## 安装

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

在 LSPosed / Vector 管理器里：

1. 启用模块（`scope.list` 已声明 `com.coloros.calendar`、`com.coloros.alarmclock`）
2. 目标 App **强制停止**后重开（旧 dex 还在跑的话 hook 不会生效）

## 使用

课表页右上角「⋮ → 导入课表」：

```
导入课表
├─ 从教务系统导入（网页登录）  内嵌 WebView 自己登录，点到「学期理论课表」→ 确定
├─ 从文件导入（.ics / .xlsx）  系统文件选择器
├─ 从「下载」目录直接选        不依赖系统 Picker（推荐）
└─ 粘贴课表 HTML / 表单直连    高级：直接贴 HTML 或 kbList JSON
```

导出：`⋮ → 导出课表`，落到系统「下载」目录（同名覆盖，不再产生 `(1)(2)` 副本）。

## 排错判据

```bash
adb logcat -s COURSEALARM
```

| 日志 | 含义 |
|---|---|
| `injection ready pkg=com.coloros.calendar` | 模块进宿主了（没有就是作用域 / 重启问题） |
| `import format=Excel(.xlsx) (2)` / `ICS 日历 (1)` / `HTML 课表页 (3)` / `教务 JSON 课表` | 格式判定结果 |
| `xlsx parsed courses=N` / `jw parseHtml zf=… gen=… final=… perDay=[…]` | 解析结果（`perDay` 是周一到周日门数） |
| `host saveCoursesAndTable ok: name=… courses=N` | 新建课表成功 |
| `insert done: N/M` | 真正写进数据库的门数 |

`unscheduled=[…]` 表示页面备注行里「没有节次」的课（例如「劳动教育 1-20周」）——这类课在网格里没有位置，排不进课表，只做提示。

## 解析器回归（不需要手机）

`tools/parsecheck` 把解析从 Android 里剥出来，JDK 直接跑：

```bash
J=/path/to/jdk21/bin
S=app/src/main/java/com/lcodexs/coursealarm
"$J/javac" -encoding UTF-8 -d out "$S/JwParser.java" "$S/Course.java" "$S/ImportFormat.java" \
  tools/parsecheck/ParseCheck.java
"$J/java" -cp out com.lcodexs.coursealarm.ParseCheck tools/parsecheck/fixtures
# 期望 ALL PASS
```

`fixtures/` 覆盖 5 类页面：正方 jsxsd、同格多课、强智/URP 表格、正方新版 JSON、以及数字行头 / 单引号 class / rowspan / colspan / 裸文本课名这类变体。

单个页面诊断（列出「页面里应有」vs「实际解析出」）：

```bash
"$J/java" -cp out com.lcodexs.coursealarm.Diag /path/to/timetable.html
```

## 工程结构

```
app/src/main/java/com/lcodexs/coursealarm/
├── CourseAlarmEntry.java   API 102 入口（onModuleLoaded / onPackageLoaded / Application.attach）
├── Hooks.java              所有 hook 点（反射取 Method，不 import 宿主类）
├── CurriculumIo.java       导入/导出流程、格式判定入口、新建课表三条路径
├── ImportFormat.java       内容 magic → 扩展名 → MIME（纯 java，可桌面测）
├── JwParser.java           教务课表 HTML/JSON → Course（纯 java，可桌面测）
├── JwClient.java           教务系统取页面（登录 POST → GET 课表页）
├── JwWebView.java          内嵌 WebView 登录 + 抓 DOM（含同源 iframe）
├── XlsxCodec.java / TemplateXlsx.java   xlsx 读（sharedStrings）/ 写（照抄模板包）
├── IcsCodec.java           ICS 读写
├── HostTableCreator.java   用宿主建表 API 新建课表（含 courseTableTime 补齐）
├── TableCtx.java           从宿主库反推节次时间 / 开学日 / 周数 / 当前课表
├── CourseWriter.java       写 content://com.android.calendar/events
├── Scheduler.java / NightJob.java / AlarmBridge.java / Notifier.java   21:00 定时与闹钟
└── ReminderUiInjector.java 课程提醒页注入卡片

tools/parsecheck/   解析器桌面回归（ParseCheck / Diag + fixtures）
```

## 已知限制

- 只覆盖 HTML 表格 / JSON 两类课表；图片、PDF、纯 JS 动态渲染（DOM 里没有数据）不解析
- 备注行里无节次的课不生成课程条目（只提示）
- 新建课表后宿主不会自动把「当前表」切过去，需在「课表管理」里手动切换
- 宿主被 R8 改名时，`CreateCourseTableViewModel` 的反射路径会退到「打开宿主建表页预置课程」
- 时钟侧兜底依赖 `com.oplus.alarmclock.ai` Provider 存在（ColorOS 特有）

## 卸载 / 回滚

```bash
adb uninstall com.lcodexs.coursealarm
```

或在 LSPosed 管理器取消勾选。已写入的课表数据在宿主里，用「课表管理」删表即可（模块不额外存数据）。
