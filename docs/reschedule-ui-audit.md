# 调课页面内容核对（WakeUp 6.7.2 / 562）

参考 APK 的布局资源被压缩重命名。以下内容来自 APK 的 resources 与 XML 树、对应 Activity 及选择器控制器，不按截图推测。

| 页面/资源 | APK 中路径 | 内容与交互 |
| --- | --- | --- |
| `dialog_drag_course` | `res/7I.xml` | 标题“此更改应用范围？”；本周、以后全部、跨周、按天四项，末尾取消。 |
| `activity_edit_week_course` | `res/Bq.xml` | 标题“选择要调整的周次”，右上“确认”；课程名称/原周次/星期节次时间/教室/老师/备注/学分；空教室、老师、备注与非正学分隐藏。接着“已选 N 周”、清空、全选、5 列周网格和“同时调整多个周次到相同的新时间”。最后“新上课时间”下按顺序为周次、星期、开始节次、结束节次。 |
| `activity_edit_day_course` | `res/k_.xml` | 标题“按天调课”，右上“确认”；“调整的上课时间”，周次、时间两行；“当天共 N 节课”与全选；课程列表/“当天无课程”；底部“新上课时间”，周次、时间两行。 |
| `item_edit_day_course` | `res/7o.xml` | 左侧开始/结束时间，课程色条，名称，教室/老师（空值隐藏），右侧选中状态。没有时间时显示第 N 节。 |
| `custom_edit_day_course_week_day_picker` | `res/uG.xml` | “周次和星期”，关闭，周次/星期两个滚轮，“保存”。 |
| `custom_edit_day_course_date_picker` | `res/Me2.xml` | “日期”，关闭，年月日滚轮，“保存”；学期起止日期限制。 |
| `custom_weekday_picker` | `res/D5.xml` | “星期”，关闭，星期一至星期日滚轮，“保存”。 |
| `custom_start_time_picker` / `custom_end_time_picker` | `res/f-.xml` / `res/181.xml` | “开始节次”/“结束节次”，关闭，节次滚轮，“保存”；结束节次选择器从当前开始节次起。 |

## 默认状态

- 跨周页默认选中源事件实际周次；目标星期/开始节次取松手位置，结束节次保留原节数；周次可全选/清空/逐项选择。
- 按天页默认来源为展示周＋原星期，目标为展示周＋松手星期；来源日课程默认全部选中。来源日期变化后重新生成来源日列表并全选。
- “当天共 N 节课”的 N 按 WakeUp 控制器取已选课程条数；“全选”文字固定，点击切换全选/清空。
- 日期文案为“YYYY年M月D号”；周次文案为“第N周 星期X”。
- 周次/星期和日期相互转换使用开学日期所在周的星期一作为第 1 周基准；日期选择器下限仍为真实开学日期。开学前的首周星期不能作为调课目标，选择后明确提示并保留原选择，避免星期三开学的课表整页日期错后两天。
- 宿主课程没有学分字段时视为零并隐藏，不添加替代字段。按天调课保留每门课程时间，不采用松手后的节次。

## 日历原生呈现与适配边界

使用宿主 `activity_preference` 页面容器、`COUIToolbar`、`coui_preference` 设置行、`COUIButton`、`COUICheckBox`、`COUINumberPicker`、`COUIDatePicker`、`COUIBottomSheetDialog` 和 `COUIAlertDialogBuilder`。周网格使用宿主 `bg_course_day_selector` 状态背景。颜色取宿主主题和深浅色资源；全页窗口处理状态栏、导航栏及挖孔 Insets。

页面内容由 `RescheduleUi` 管理，宿主组件适配集中在 `RescheduleNativeUi`。全页编辑使用宿主页面容器的独立窗口，返回不重建主课表。页面退出或宿主 Activity 销毁时释放选择器及未提交草稿；不恢复过期拖动会话。

保存规则按用户确认的方案：四范围始终可选；所有时间冲突一律阻止；不提供 WakeUp 的“保留两者”，也不采用其目标已有同一安排时只删除来源的去重策略。事务、提醒和撤回交给统一变更协调器。

## 兼容检查所需新增契约

- 布局：`activity_preference`、`coui_preference`。
- 图像/样式：`bg_course_day_selector`、`coui_back_arrow`、`icon_cloose`、`COUIFitContentBottomSheetDialog`。
- COUI 控件的 `Context` 构造器，以及底部面板的 `(Context, int)` 构造器。
- 工具栏：`setTitle(CharSequence)`、`setTitleTextColor(int)`、`setNavigationIcon(int)`、`setNavigationContentDescription(CharSequence)`、`setNavigationOnClickListener(View.OnClickListener)`、`getMenu()`。
- 滚轮：`setMinValue(int)`、`setMaxValue(int)`、`setDisplayedValues(String[])`、`setValue(int)`、`getValue()`、`setWrapSelectorWheel(boolean)`。
- 日期：`setMinDate(long)`、`setMaxDate(long)`、`init(int,int,int,OnDateChangedListener)`、`getYear()`、`getMonth()`、`getDayOfMonth()`。
- 勾选：`setChecked(boolean)`；原生提醒页不参与这些调课页的保存链路。

## 实机样式修正

- `COUIButton(Context)` 会沿用 `AllInOneActivity` 的 AppCompat `buttonStyle`，实机呈现为深灰矩形。现改为 `(Context, AttributeSet, int)` 构造，指定宿主 `couiButtonColorfulWhiteStyle` 用于面板底部按钮、`couiTinyTranslateButtonColorStyle` 用于清空/全选；保留原生圆角、按压及深浅色/禁用状态。
- 课程颜色字段是宿主色盘索引，不是 ARGB。课程详情与按天列表的色条均通过 `TodoColor.createByIndex(...).getColor()` 解析，避免 0–11 被当成透明色。
- 上述两个按钮属性在用户提供的 17.3.70、17.6.20、17.8.0 资源表中均存在。新增方法契约：`COUIButton(Context, AttributeSet, int)`、`TodoColor.getColor(): int`。

## 统一原生设置页样式

跨周页、按天页及选择面板统一使用 `AppNoTitleTheme.PreferenceFragment` 上下文。这是三个版本中 `CourseReminderActivity` 和 `CreateCourseActivity` 的实际 Activity 主题。全页窗口也显式使用该非浮动主题，防止默认 Dialog 的浮动窗口把底层课表背景露在状态栏和导航栏下。

- 工具栏使用 `COUIToolbar`、`textAppearanceSecondTitle` 与原生 `toolbar_height`；选择面板也复用工具栏及菜单关闭按钮。
- 卡片改用课程编辑页同款 `bg_shape_white_round_20`，由 `couiColorCardBackground` 与 `coui_card_radius_os17` 决定颜色、圆角；背景和正文颜色改取宿主主题属性，避免读取固定浅色资源。
- 设置行沿用 `coui_preference`，卡片内左右留白对齐课程编辑页的 16dp，最小高度取 `support_preference_min_height`；标题和说明使用原生 Preference 文字样式。
- 保留 WakeUp 五列周次布局，但每个原生选中背景保持 44dp 正圆，在等分列中居中，避免横向拉伸成大椭圆。课程摘要保持所有原内容和顺序，缩小无意义的纵向空白。
- 按天页统一单一滚动区域，来源设置、课程列表和目标设置都可滚动，避免小屏/大字体时固定区挤掉课程列表。没有新增、删除或调整字段顺序。
- 数字滚轮使用宿主 `coui_numberpicker_height`，日期控件按原生测量高度布局，确认/取消仍使用宿主 COUI 按钮完整状态样式。

2026-11-18 恢复实机测试后，在 `192.168.137.143:5555` 实际安装的日历 17.8.0（17008000）上检查了设置页、范围面板、跨周页、按天页及提醒页，截图保存在 `artifacts/drag-reschedule-20261118-retest/`。检查发现“清空／全选”的原生小按钮前景过浅，现保留原生背景和按压状态，使用宿主主题主色及禁用色修正文字对比度；对应证据为 `cross-week-contrast-fixed.png`。没有修改 WakeUp 内容或字段顺序。

真实长按拖动能显示范围面板，原地长按松手仍显示复制菜单，空白菜单保留添加／粘贴；横滑切周无需额外点击便显示课程。按天页面完成实际保存并点击撤回；底部第 10–11 节落点也完成保存及点击撤回。底部自动滚动曾被宿主 `onDraw` 再次钳回原上限，已在拖动期间临时扩展边界，实测滚动可由原上限约 268 到约 470，结束后恢复原上限。具体像素来自该设备布局，不能推广为所有屏幕固定值。

三版 APK 的最终接口／资源与隔离 ART 检查分别为 494／48／521 项通过；真实 UI 本轮仅在 17.8.0 检查，未逐版安装或降级日历。尚未完成 RTL、大字体、多指、完整性能与所有深浅色／材质组合验收。一次初期“以后全部”撤回失败的原因仍未确认，后续用例和 12 次重复撤回通过不等于消除了全部恢复风险；完整验证边界及最终清理状态见 [0.9.9 变更说明](../CHANGELOG-0.9.9.md)。
