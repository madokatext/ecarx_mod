# ECARX 保电修复

用于 Android 9 车机、LSPosed API 93 的模块，作用域为 `ecarx.settings`。负一屏“智能保电”按钮切换开启或关闭时，原程序下发模式请求之后，模块额外发送车机当前保存的保电目标电量。

适配依据是 XCSettings2 **3.0.0.0064（versionCode 3000064）** 的类名、方法和功能 ID。按需求未执行单元测试、设备测试或实车验证；GitHub Actions 只编译和打包 APK。

## 安装

1. 打开本仓库 [Actions](https://github.com/madokatext/ecarx_mod/actions/workflows/build-apk.yml)，选择成功的 **Build APK** 运行。
2. 下载运行页面下方的 `ecarx-mod-apk-...` artifact，解压后安装 APK。
3. 在 LSPosed 中启用 **ECARX 保电修复**，勾选 **ecarx.settings**。模块已声明这个推荐作用域，无需勾选系统框架或桌面应用。
4. 重启车机，使目标进程重新加载模块。模块没有桌面图标或独立设置页。
5. 保电目标继续在原车“动力电池”页面设置；负一屏切换智能保电时会补发该值。

禁用模块并重启目标进程即可恢复原应用行为。

Actions 默认生成 **debug 签名、可安装的 APK**，不需要配置仓库密钥。不同运行的临时 debug 签名可能不同；遇到签名不一致而不能覆盖安装时，先卸载旧的本模块再安装。原车 `ecarx.settings` 及其保电设置不属于本模块数据。

## 修复逻辑

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
- 原车模式请求在先，补发目标在后；不创建延迟任务或后台循环。
- 保存值类型异常、车辆服务未连接、类或方法不兼容时记录日志；不会替换原方法的结果或吞掉原方法异常。
- 原 `CarFuncManager.setFunctionValue` 封装始终返回 false，所以模块不把该布尔值当作失败，也不全局修改它。

这里修复的是快捷入口遗漏目标下发的问题。日志中的 requested 只表示应用请求已发出，不表示车辆控制器已确认或实车保电效果已经验证。

| 属性 | 值 |
| --- | --- |
| 保电模式 | `0x24150600` / `605357568` |
| 智能保电开启 | `0x24150601` / `605357569` |
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
```

核心代码：[BatterySocHook.java](app/src/main/java/io/github/madokatext/ecarxmod/BatterySocHook.java)。

接口使用参考：[Xposed API](https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API)、[LSPosed 作用域](https://github.com/LSPosed/LSPosed/wiki/Module-Scope)、[AGP 8.7 兼容性](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。
