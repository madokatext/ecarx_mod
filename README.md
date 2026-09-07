# ECARX 保电修复

用于 Android 9 车机、LSPosed API 93 的模块，作用域为 `ecarx.settings`。当前版本 **1.4.0**：新增“保电恢复后关闭低速行驶提示”设置开关，与开机 HEV、保电恢复共用 **5–300 秒**延时，按顺序执行。保留 API 28 编译限制、设置页兼容性修复、目标电量补发、文件控制及实际状态同步功能。

适配依据是 XCSettings2 **3.0.0.0064（versionCode 3000064）** 的类名、方法和功能 ID。按需求未执行单元测试、设备测试或实车验证；GitHub Actions 只编译和打包 APK。

1.3.0 设置页曾调用 `SeekBar` 继承的 `ProgressBar.setMinHeight(int)`，该方法从 API 29 才提供，在 Android 9 会触发 `NoSuchMethodError`。1.3.1 改用从 API 1 就支持的 `View.setMinimumHeight(int)`，并用 API 28 的 Android 框架声明编译，阻止直接引用更新版本的框架 API。[ProgressBar 官方文档](https://developer.android.com/reference/android/widget/ProgressBar#setMinHeight(int))、[View 官方文档](https://developer.android.com/reference/android/view/View#setMinimumHeight(int))。

## 安装

1. 打开本仓库 [Actions](https://github.com/madokatext/ecarx_mod/actions/workflows/build-apk.yml)，选择成功的 **Build APK** 运行。
2. 下载运行页面下方的 `ecarx-mod-apk-...` artifact，解压后安装 APK。
3. 在 LSPosed 中启用 **ECARX 保电修复**，勾选 **ecarx.settings**。模块已声明这个推荐作用域，无需勾选系统框架或桌面应用。
4. 打开桌面的 **ECARX 保电修复**，或从 LSPosed 的模块设置入口打开设置页，选择“每次开机先切换 HEV”“保电恢复后关闭低速行驶提示”和开机执行延时，点击 **保存设置**。
5. 重启车机，使目标进程重新加载模块。设置保存后从下一次车机重启生效；两个功能开关默认开启，共享的开机延时默认 5 秒。
6. 保电目标继续在原车“动力电池”页面设置。负一屏和设置页的保电模式切换都会被记忆；重启后按配置执行 HEV 与保电恢复。

首次安装本版本尚无开关历史时，采用车辆首次返回的有效保电模式作为初始记忆，不默认强制开启。此后操作一次原车保电开关，就会记录新的状态。

禁用模块并重启目标进程即可恢复原应用行为。

Actions 默认生成 **debug 签名、可安装的 APK**，不需要配置仓库密钥。不同运行的临时 debug 签名可能不同；遇到签名不一致而不能覆盖安装时，先卸载旧的本模块再安装。原车 `ecarx.settings` 及其保电设置不属于本模块数据。

## 开机顺序与共享延时设置

模块设置页包含：

| 设置 | 默认值 | 作用 |
| --- | --- | --- |
| 每次开机先切换 HEV | 开启 | 延时结束后先确认 HEV，再允许恢复保电 |
| 保电恢复后关闭低速行驶提示 | 开启 | 保电模式回读匹配、目标已补发后，关闭低速行驶提示 |
| 开机执行延时 | 5 秒 | 5–300 秒，滑块按 1 秒调节，提供 5、30、60、180、300 秒快捷按钮 |

延时从**本次开机第一次 `ecarx.settings` 启动**开始计时。开启 HEV 选项时的顺序：

```text
原车设置应用启动 → 等待配置的延时
  → 等待车辆服务和点火 ON / START / DRIVING
  → 切换 HEV，等待车辆接口回读确认
  → 按上次记忆恢复保电模式，补发保存的目标电量
  → 关闭低速行驶提示（开关启用时），等待关闭状态回读确认
  → 通过实际回读同步原设置界面和状态文件
```

- 若车辆已返回 HEV，直接进入保电恢复，不重复发送 HEV 请求。关闭 HEV 选项时，延时后按当前驾驶模式恢复保电，不主动切换 EV/HEV。
- 三个步骤**只共用一次开机延时**，保电恢复后不再等待一轮 5–300 秒。低速提示关闭在保电模式确认、目标补发请求完成后进入；如果前面的 HEV 或保电恢复尚未完成、失败或被用户取消，不提前执行低速提示关闭。
- 关闭低速提示使用原开关属性 `0x201a0100`、值 `0`，不修改提示音等级。最多发送 **2 次（首次加一次重试）**，每次下发后至少等待 1 秒回读；进入该阶段后最多等待车辆条件 60 秒。车辆已经回读关闭时直接完成，不重复发送。
- 低速提示自动关闭的开关快照、完成/取消状态和发送次数与系统开机标识一起持久化。同一次开机中，设置进程重启或服务重连不会重置其重试次数；完成后用户重新开启提示，也不会被模块反复关闭。关闭设置页中的该选项后，下次开机跳过此步骤。
- 使用系统开机计数，必要时使用内核 boot ID，识别一次真正的系统重启。执行状态、计时起点和 HEV 请求次数在原应用中落盘；同一开机期间的进程重启或服务重连不重复强制切换 HEV，也不重新从零计时或重置 HEV 重试次数。
- HEV 请求发送后至少等待 1 秒确认，最多发送 **2 次（首次加一次重试）**。延时结束后最多主动等待车辆条件 60 秒；HEV 未确认、条件超时或配置无法读取时，停止这一轮自动恢复，不抢先下发保电请求。
- 保电开启仍要求 HEV/SAVE 和舒适模式；不改动驾驶模式。保电开启和关闭都补发原应用保存的 30–85% 目标；HOLD 保持原有含义。
- 手动切换 EV/HEV、保电或调节保电目标，以及有效的 EV/HEV、保电文件指令，会取消待执行的开机流程。手动或文件控制低速提示，只取消本次开机的低速提示自动关闭，不中断前面的 HEV/保电恢复。模块自己的恢复请求不会被当作手动操作。
- HEV、保电和低速提示回读通过原 `CarFuncManager.mWatcher` 分发，刷新原设置页、Kanzi 和已有 Widget 观察者。文件状态同步仍每秒运行；未确认的请求值不会被用于伪造界面状态。低速提示实际状态继续反映在 `/sdcard/ecarx_mod/low_speed_warning_state.txt`。
- 两个功能开关和共享延时保存在本模块的设备保护存储，通过只读 ContentProvider 提供给注入进程；不依赖六个控制/状态文件。设置页只保存配置，保存动作不立即切换车辆。卸载本模块会清除这些配置，下次安装恢复默认值。

## 文件控制与状态

模块随 `ecarx.settings` 启动，在车机 **`/sdcard/ecarx_mod/`** 下创建六个 `.txt` 文件，不需要打开设置页面。文件操作使用原车设置应用的身份；适配的原应用已声明外部存储写权限。存储尚不可用时会等待并重试访问。

| 控制文件：用户写入 | 实际状态文件：模块更新 | `0` | `1` |
| --- | --- | --- | --- |
| `ev_hev.txt` | `ev_hev_state.txt` | EV | HEV |
| `smart_charge.txt` | `smart_charge_state.txt` | 智能保电关闭 | 智能保电开启 |
| `low_speed_warning.txt` | `low_speed_warning_state.txt` | 低速行驶提示关闭 | 低速行驶提示开启 |

### 控制文件

- 缺失的控制文件在模块启动初始化时创建为空。现有内容只作为启动基线，不因启动或服务重连而重新执行；模块运行后写入才触发指令。
- 每 **500 ms** 检查一次内容和文件属性。检测到有效 `0` 或 `1` 后，在同一次处理内**立即清空文件，再安排车辆指令**。后续再次写入相同的值，也算一条新指令。模块清空造成的变化不会被当成用户写入。
- 支持 **GBK、UTF-8、带 BOM 的 UTF-8**，允许字符前后的空格、回车和换行。只接受单个 `0` 或 `1`；空文件、其他文字、多位数字和超过 128 字节的内容不执行，也不自动清空。
- 清空表示指令已读取，不表示车辆已执行成功。模块不会向控制文件写回 `0` 或 `1`；实际结果请看对应的 `_state.txt`。
- 新写入或原设置应用对同一功能的操作会取消旧指令及其剩余重试。删除控制文件也会取消；运行中不自动重建被删除的控制文件，用户可自行重新创建。
- 同一文件逐次写入，看到清空后再写下一条。它是单个指令入口，不是保存多条指令的队列；轮询间隔内的连续覆盖可能只读取最后一次内容。

例如在车机 shell 中写入：

```sh
echo 1 > /sdcard/ecarx_mod/ev_hev.txt
echo 1 > /sdcard/ecarx_mod/smart_charge.txt
echo 0 > /sdcard/ecarx_mod/low_speed_warning.txt
```

上述内容分别请求 HEV、开启智能保电、关闭低速行驶提示。也可以直接用文本编辑器修改对应文件并保存。

### 下发、确认与重试

- EV/HEV 使用 `0x22040d00`，写入 EV `0x22040d02` 或 HEV `0x22040d01`。低速提示使用开关属性 **`0x201a0100`**，不改动提示音等级。
- 开启或关闭智能保电时，均读取原应用 `share_car_setting / TARGET_BATTERY_KEY`，随模式指令补发当前保存的目标电量，仍限制在 30–85%。
- 保留原功能的车辆条件：EV/HEV 等待点火 ON/START/DRIVING；智能保电开启等待 HEV/SAVE 和舒适模式；低速提示在 OFF/ACC 或未知点火状态不下发。最多等待 15 秒，条件不满足就结束，需再次写入发起新请求。
- 每次请求后约 1 秒读取车辆接口确认。**每条指令最多发送两次，即首次发送加一次重试**；智能保电的模式和附带目标各最多两次，不会因文件仍为空、状态不匹配或服务重连而持续重发。
- 原 `setFunctionValue()` 包装方法固定返回 `false`，不能用它判断失败。模块使用 `getFunctionValue()` 确认；智能保电同时核对模式与目标电量。目标不支持回读时记录“未确认”，最多重试一次后结束，不假定成功。
- 根据有效车辆回读，调用原应用 `CarFuncManager.mWatcher.onFunctionValueChanged()` 分发，更新设置页、Kanzi、负一屏等已有观察者和它们的内部状态。不会把失败的请求值伪装成车辆状态。
- 文件发出的智能保电请求，只有有效回读模式才进入重启记忆。接收有效 EV/HEV 或智能保电文件指令后，本次进程的旧开机 HEV/保电恢复任务及服务重连恢复会让出控制，防止额外补发；再次手动操作原保电开关或下次启动后沿用正常恢复机制。

### 实际状态文件

- 每 **1 秒**主动读取三个车辆属性；车辆状态发生变化时更新对应文件，因此原设置页、负一屏等来源的操作也会反映出来。只在内容需要变化、文件丢失或被外部修改时写入。
- 有效状态为单字节 `0` 或 `1`，无 BOM、无换行，GBK 与 UTF-8 均可读取。未知值、读取失败或车辆服务断开时写为空，避免继续展示过期状态。
- 智能保电处于 **HOLD／电量保持** 模式时，`smart_charge_state.txt` 为 `0`，与原设置页“智能保电”开关关闭一致；此文件不表示 HOLD 开关状态。
- EV/HEV 返回 **SAVE** 等不能映射为 EV 或 HEV 的模式时，`ev_hev_state.txt` 留空，不冒充 HEV。
- 状态文件只展示车辆接口实际回读，不用于下发指令。手动修改它们不会控制车辆，下一轮同步会恢复回读值。
- 同步依赖 `ecarx.settings` 进程运行；进程退出或车机断电后，磁盘上可能保留最后一次内容，下次进程启动后重新读取更新。文件不是车辆控制器的独立实时接口。

## 修复逻辑

### 重启后恢复保电状态

- 在 `ecarx.settings` 进程内，把用户通过原应用发送的保电模式请求保存到 `ecarx_soc_fix / last_requested_charge_mode`，使用同步 `commit()` 落盘。
- 记忆“智能保电开启”“电量保持”“关闭”三个互斥模式，避免把电量保持误当作智能保电开启。保存的是用户请求，不用车辆开机、熄火时的默认状态回调覆盖历史。
- Hook `App.onCreate()`、`CarFuncManager.notifyObservers(boolean)` 以及原车辆状态分发器。无需进入动力电池设置页；应用启动或车辆服务重新连接后会安排恢复，并通过本次开机的延时/HEV 阶段进行排序。
- 保留驾驶模式约束。启用开机 HEV 选项时由前置阶段切换 HEV；后续保电恢复开启状态仍等待 HEV/SAVE 和舒适模式，不主动改变驾驶模式。如果尚未满足条件，等待相应状态事件后继续。恢复关闭不要求开启条件。
- 先读取目标，再恢复模式，随后补发目标值。**即使读到的开关状态已经与记忆一致，也会补发目标电量。** 若发生模式切换，在后续模式读回匹配时再次补发，覆盖模式切换可能重置目标的时序。
- 智能保电的开启和关闭都补发目标，范围仍是 30–85%。恢复“电量保持”只恢复 HOLD 模式，不用预设目标干预其保持当前电量的含义。
- 等待启动数据最多主动轮询 10 次，之后由车辆连接/驾驶模式事件唤醒；每轮恢复的模式及目标请求各最多发送 2 次，避免无限重发。确认模式匹配后结束本轮恢复，不持续强制锁定模式。
- 用户在恢复期间重新操作保电开关时，取消旧恢复及其后续重试，保存新选择。模块自己的恢复请求不会反过来被记录为新的用户操作。
- 状态保存在原车应用的数据目录中，不依赖模块自身的界面或存储；卸载本模块不会清除这份记录，清除原车应用数据才会清除。

模式读回匹配仅表示车辆接口读到的模式一致；目标下发仍按原接口发出请求，不宣称已确认控制器内部目标或实车效果。

### 负一屏补发目标

原程序的负一屏入口是：

```text
NegativeOneScreenWidgetManager.changeFunctionValue(Context, NegativeOneScreenWidgetAttr)
  → CarFuncManager.setFunctionValue(保电模式, ACTIVE 或 OFF)
```

原入口已经检查 EV / HEV、驾驶模式等可用条件，但不会补发目标电量。模块保留这个入口和检查，只增加下面一条请求：

```text
原模式请求正常返回
  → 读取 share_car_setting / TARGET_BATTERY_KEY
  → CarFuncManager.setFunctionValue(0x24030100, 保存的目标百分数)
```

- 同时核对 Widget 的组件名、功能 ID 和开启值，限定为 `PowerBatteryChargeWidget`。
- 通过线程内点击上下文关联实际的模式下发；原程序未下发模式时，不补发目标。
- 开启和关闭智能保电都补发一次目标。“电量保持”Widget（SOC_HOLD）不触发补发。
- 直接在目标进程读取原应用 SharedPreferences，无跨应用读文件权限，无额外目标配置。
- 没有保存值时沿用原应用默认 **30%**；发送范围限制在原界面的 **30–85%**，不改写保存值。
- 负一屏的原车模式请求在先，补发目标在后，不为点击本身创建延迟任务。重启恢复使用独立的有界重试。
- 保存值类型异常、车辆服务未连接、类或方法不兼容时记录日志；不会替换原方法的结果或吞掉原方法异常。
- 原 `CarFuncManager.setFunctionValue` 封装始终返回 false，所以模块不把该布尔值当作失败，也不全局修改它。

日志中的 requested 只表示应用请求已发出，不表示车辆控制器已确认或实车保电效果已经验证。

| 属性 | 值 |
| --- | --- |
| 保电模式 | `0x24150600` / `605357568` |
| 智能保电开启 | `0x24150601` / `605357569` |
| 电量保持 | `0x24150602` / `605357570` |
| 关闭 | `0x24150603` / `605357571` |
| 目标 SOC | `0x24030100` / `604176640` |
| 目标单位 | 整数百分数，例如 60；不是当前电量传感器的十分之一单位 |

## 构建

工程包含 Gradle Wrapper，构建依赖：

- JDK 17
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Android SDK 28、Build Tools 34.0.0（Build Tools 是编译工具版本，不是车机运行时 API）
- `compileSdk=28`、`minSdk=28`、`targetSdk=28`，以 Android 9 的框架 API 编译
- 传统 Xposed Java API `de.robv.android.xposed:api:82`，仅使用 `compileOnly`，由 LSPosed API 93 在运行时提供实现
- Manifest 中 `xposedminversion=93`，入口为 `assets/xposed_init`

“API 82”是编译使用的传统 Java 接口依赖版本，不要求把车机 LSPosed 降级，也不使用 modern libxposed API 100/101。

本地仅编译 APK：

```shell
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。输出为 `app/build/outputs/apk/debug/app-debug.apk`。

推送 `main`、推送 `v*` 标签、提交 PR 或手动点击 **Run workflow** 会运行 Actions。流程只执行 `:app:assembleDebug`，不会执行测试或 lint，并上传 APK 及 SHA-256 文件。

## 日志

LSPosed 日志中的 `EcarxSocFix: installed for ...` 表示 Hook 已安装。常规操作日志位于 logcat：

```shell
adb logcat -s EcarxSocFix:I
```

示例：

```text
Widget mode=0x24150601; requested target SOC=60%
Remembered charge mode=0x24150601
Restart restore mode=0x24150601; requested target SOC=60%
Restart mode readback matched: 0x24150601
```

核心代码：[BatterySocHook.java](app/src/main/java/io/github/madokatext/ecarxmod/BatterySocHook.java)、[BootHevGate.java](app/src/main/java/io/github/madokatext/ecarxmod/BootHevGate.java)、[BootStateRestorer.java](app/src/main/java/io/github/madokatext/ecarxmod/BootStateRestorer.java)、[BootLowSpeedAction.java](app/src/main/java/io/github/madokatext/ecarxmod/BootLowSpeedAction.java)、[ModuleSettingsActivity.java](app/src/main/java/io/github/madokatext/ecarxmod/ModuleSettingsActivity.java)、[BootSettingsProvider.java](app/src/main/java/io/github/madokatext/ecarxmod/BootSettingsProvider.java)、[FileCommandController.java](app/src/main/java/io/github/madokatext/ecarxmod/FileCommandController.java)、[ControlFileMonitor.java](app/src/main/java/io/github/madokatext/ecarxmod/ControlFileMonitor.java)。

接口使用参考：[Xposed API](https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API)、[LSPosed 作用域](https://github.com/LSPosed/LSPosed/wiki/Module-Scope)、[AGP 8.7 兼容性](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。
