# 拖动调课实机验证（2026-11-18）

模块 0.9.9（36），包名 `io.github.yuangug.coursealarm`。设备固定为 `192.168.137.143:5555`，本轮系统日历实际版本 **17.8.0（17008000）**。未安装或降级三个提供的系统日历 APK。17.3.70、17.6.20 仅做静态及隔离 ART 检查，不等同完整 UI 实测。

## 结果与证据

证据目录：工作区 `artifacts/drag-reschedule-20261118-retest/`。私有数据备份不纳入 Git；以下文件名均相对于该目录。

| 项目 | 本轮结果 | 主要证据 |
| --- | --- | --- |
| 三版接口、资源、隔离运行 | 每版 494/494、48/48、521/521，通过 | `compat-final-report.json`、`art-*.log` |
| 四种调课范围 | 本周、以后、跨周、按天保存及撤回通过；部分周拆分，全周移动保留 ID | `backend-suite-result.json`、各范围 `*-journal.json` |
| 连续撤回 | 12 次以后范围拆分／撤回均通过；事件核心字段和提醒恢复一致 | `undo-stress-result.json` |
| 按天批量及自定义时刻 | 两门不同节次整批移动并撤回；45/90/100 分钟实际时刻保持，宿主修复校验通过 | `advanced-result.json` |
| 周次边界 | 不连续周、以后范围保留原分布、跨周新增勾选周、最后一周通过 | `edge-result.json` |
| 写入保护 | 时间冲突、目标同一课程冲突、原地操作、冻结来源后编辑均不写入；事务断言失败整批回滚 | `edge-result.json`、`conflict-result.json`、`noop-result.json` |
| 撤回保护与重启 | 三秒到期后不恢复；后续编辑触发冲突，不覆盖新内容；重启不盲目重放 | `edge-result.json`、`before-restart.json`、`after-restart.json` |
| 设置隔离 | 课表 15 开关开启、原表 8 关闭，退出及重启保持；原生提醒页内容未被替换 | `after-restart-settings.json`、`settings-reminder-contact.png` |
| 课前提醒 | 专用表由 15 分钟改成 30，退出重进及模块重装后进程重启仍为 30；实际提醒行一致 | `reminder30-saved.json`、`reminder30-reopen.png`、`edge-build-state.json` |
| 删除课表撤回 | 恢复同一课表身份、三门活动课程、提醒及拖动开关；原生同步时间戳和 dirty 随恢复更新 | `delete-table-undo-journal.json`、`table-setting-restored.json` |
| 删除课程撤回 | 真实事务删除后点击撤回，记录 `UNDONE` | `delete-course-journal.json`、`delete-course-restored.json` |
| 长按、复制、粘贴、添加 | 不移动松手显示复制／删除；复制后空白菜单有粘贴／添加；粘贴新增独立事件并撤回；添加打开原生新建课程页，取消未写入 | `gestures-contact.png`、`paste-retry-journal.json`、`add-native-activity.json`、`add-contact.png` |
| 切周及普通滚动 | 拖动开关关闭时原生菜单及纵滑可用；左右切周后卡片和文字直接出现，无需额外点击 | `native-scroll-off.json`、`swipe-week-render.json`、`swipe-week-visible.png` |
| 上下边缘与取消 | 上缘滚动 268→0；下缘修复后可滚动至 470.3999，标题、底栏无效落点不提交，取消恢复正常边界 | `top-autoscroll-before.json`、`top-autoscroll-after.json`、`edge-fixed-held.json`、`header-drop-cancel.png` |
| 最后节次真实保存 | 拖入第 10—11 节并保存，点击底栏撤回；新增事件为 startNode=9/endNode=10，记录 `UNDONE` | `edge-save-undo-journal.json`、`edge-save-undo-bar.png` |
| 原生 UI | 更多菜单的课程提醒后是课表设置；设置页、调课页、选择器和按钮沿用宿主组件；修复清空／全选文字对比度 | `more-menu-native.png`、`settings-via-menu.png`、`cross-week-contrast-fixed.png`、`day-native.png` |

数据库恢复结论以恢复记录状态及对应快照为依据，截图仅证明界面表现。首次截图辅助撤回和一次粘贴测试的记录到期，没有计入成功；后续在截止前点击及自动化事务复测另有独立记录。

## 本轮发现与修复

1. 撤回构建未变化提醒的空 `ContentValues`，宿主抛出 `IllegalArgumentException: Empty values`。现在保留一致性断言，跳过无差异更新；普通事件更新也不再提交空字段。
2. 宿主 `TableView.onDraw` 每帧重算 `maxScrollY`，覆盖拖动所需的额外滚动空间。现在只在拖动绘制期间临时延长 `bottomSpace`，在 `finally` 恢复几何字段；最终节次保存及撤回已实测。
3. 跨周页“清空／全选”白字在原生按钮背景上对比度不足。启用态改用宿主主色，禁用态使用宿主禁用色，保留原生按压和圆角。
4. 三版检查最初因七项契约缺少 `feature` 描述字段而异常。补齐验证元数据，并在脚本中增加提前校验，最终三版完整重跑通过；该错误属于验证工具，不是宿主应用崩溃。

## 未确定问题及未覆盖项

一次早期 FUTURE_WEEKS 撤回记录为 `UNDO_FAILED`（令牌 `d8dc37fe-3f6f-47f1-9515-48d0b8bd7d91`）。当时没有保存异常字段，失败时的日志和即时快照不足。后续完整范围测试及 12 次连续复测通过，仍不能证明该失败已消除。保留的旧事件字段与同步标记提示宿主异步同步回写旧数据的可能性，尚未完成确定性复现。没有放宽撤回冲突保护，也没有覆盖用户后续修改。现在恢复记录会保存异常根因，供再次发生时定位。

未进行三版完整真实 UI、实际定时提醒响铃、完整帧耗时与录屏分析、RTL、全部字体缩放／多指交互、重复异常课程和完整 Lint 验收。重叠课程选择面板和非本周课程拒绝路径仅做有限观察，未覆盖全部组合。不能用本轮结果宣称所有功能和兼容场景全部通过。

## 清理与交付

测试前备份宿主私有目录及 Calendar Provider 数据；测试只写专用课表 15。结束时该表活动日历、活动课程和提醒数量均为 0；正常的同步删除标记保留给宿主处理。未创建桌面快捷方式，测试期间早课闹钟关闭。

已恢复原课表 **8（课程表测试）**、原表拖动关闭、课前提醒 **15 分钟**、模块早课闹钟开启。原表两门课程、日历配置、提醒、参与人及扩展属性与测试前完整快照 **逐字段完全一致**，见 `cleanup-verification.json`，不是仅按课程数量判断。

交付包为工作区 `dist/ColorOS日历增强-v0.9.9-release.apk`。正式构建不启用 `coursealarmVerification`；构建、签名、测试类排除、SHA-256 和最终部署结果见 `release-build.log`、`release-signature.log`、`release-package.json`、`production-smoke.json`。本轮未执行 Git commit。
