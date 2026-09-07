# ECARX 保电修复

用于 Android 9 车机、LSPosed API 93 的模块，作用域为 `ecarx.settings`。当前版本 **1.1.0**：车机重启后恢复上次保电开关状态，并补发保存的保电目标电量；负一屏切换智能保电时也继续补发目标。

适配依据是 XCSettings2 **3.0.0.0064（versionCode 3000064）** 的类名、方法和功能 ID。按需求未执行单元测试、设备测试或实车验证；GitHub Actions 只编译和打包 APK。

## 安装

1. 打开本仓库 [Actions](https://github.com/madokatext/ecarx_mod/actions/workflows/build-apk.yml)，选择成功的 **Build APK** 运行。
2. 下载运行页面下方的 `ecarx-mod-apk-...` artifact，解压后安装 APK。
3. 在 LSPosed 中启用 **ECARX 保电修复**，勾选 **ecarx.settings**。模块已声明这个推荐作用域，无需勾选系统框架或桌面应用。
4. 重启车机，使目标进程重新加载模块。模块没有桌面图标或独立设置页。
5. 保电目标继续在原车“动力电池”页面设置。负一屏和设置页的保电模式切换都会被记忆；重启后等待车辆服务就绪，自动恢复上次模式。

首次安装本版本尚无开关历史时，采用车辆首次返回的有效保电模式作为初始记忆，不默认强制开启。此后操作一次原车保电开关，就会记录新的状态。

禁用模块并重启目标进程即可恢复原应用行为。

Actions 默认生成 **debug 签名、可安装的 APK**，不需要配置仓库密钥。不同运行的临时 debug 签名可能不同；遇到签名不一致而不能覆盖安装时，先卸载旧的本模块再安装。原车 `ecarx.settings` 及其保电设置不属于本模块数据。

## 修复逻辑

### 重启后恢复保电状态

- 在 `ecarx.settings` 进程内，把用户通过原应用发送的保电模式请求保存到 `ecarx_soc_fix / last_requested_charge_mode`，使用同步 `commit()` 落盘。
- 记忆“智能保电开启”“电量保持”“关闭”三个互斥模式，避免把电量保持误当作智能保电开启。保存的是用户请求，不用车辆开机、熄火时的默认状态回调覆盖历史。
- Hook `App.onCreate()`、`CarFuncManager.notifyObservers(boolean)` 以及原车辆状态分发器。无需进入动力电池设置页；应用启动或车辆服务重新连接后会安排恢复。
- 保留驾驶模式约束。恢复开启状态时等待 HEV/SAVE 和舒适模式，不主动切换 EV/HEV 或驾驶模式；如果尚未满足条件，等待相应状态事件后继续。恢复关闭不要求开启条件。
- 先读取目标，再恢复模式，随后补发目标值。**即使读到的开关状态已经与记忆一致，也会补发目标电量。** 若发生模式切换，在后续模式读回匹配时再次补发，覆盖模式切换可能重置目标的时序。
- 智能保电的开启和关闭都补发目标，范围仍是 30–85%。恢复“电量保持”只恢复 HOLD 模式，不用预设目标干预其保持当前电量的含义。
- 等待启动数据最多主动轮询 10 次，之后由车辆连接/驾驶模式事件唤醒；模式请求最多发送 5 次，避免无限重发。确认模式匹配后结束本轮恢复，不持续强制锁定模式。
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
- Android SDK 35、Build Tools 34.0.0
- `minSdk=28`、`targetSdk=28`，面向 Android 9
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

核心代码：[BatterySocHook.java](app/src/main/java/io/github/madokatext/ecarxmod/BatterySocHook.java)、[BootStateRestorer.java](app/src/main/java/io/github/madokatext/ecarxmod/BootStateRestorer.java)。

接口使用参考：[Xposed API](https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API)、[LSPosed 作用域](https://github.com/LSPosed/LSPosed/wiki/Module-Scope)、[AGP 8.7 兼容性](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。
