# GeminiMi

在国行小米 HyperOS 上，将“长按电源键唤起超级小爱”替换为 **Gemini Overlay**。

模块会自动选择 Google 数字助理、开启屏幕内容与截图辅助，并在重启后恢复被 HyperOS
重置的助手设置。无需手动修改系统设置数据库。

## 适用范围

- 国行 HyperOS 设备
- Android 11 及以上
- 已安装并完成初始化的 Google App 和 Gemini
- 已安装 LSPosed，且能够为模块授予 `android` 系统框架作用域

本模块针对 HyperOS 的系统实现编写，不建议在非小米 ROM 上启用。

## 功能

- 长按电源键显示 Gemini Overlay，而不是打开 Gemini 普通应用界面
- 禁止超级小爱重新启用“电源键唤醒”
- 将系统语音助手、语音交互服务和语音识别服务设置为 Google
- 开启助手读取屏幕文字与截图的权限开关
- 将系统长按电源键行为设置为“数字助理”
- 在开机和用户解锁时恢复被 HyperOS 重置的助手设置
- 支持 Android 多用户分别写入助手设置
- v1.0.6 起，每次唤起 Overlay 前会先清理旧的 VoiceInteraction 会话，减少 Gemini
  因网络异常或上次半启动后只在屏幕边缘闪一下的问题

## 安装

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/com.vince.geminimi/releases/latest)
   下载并安装最新 APK。
2. 在 LSPosed 中启用 **GeminiMi**。
3. 作用域只勾选 `android`（系统框架）和 `com.miui.voiceassist`（超级小爱）。
4. 确认 Google App 和 Gemini 已安装，并至少手动启动过一次。
5. 重启设备。
6. 解锁后长按电源键测试 Gemini Overlay。

更新模块时可直接覆盖安装。请勿混用不同来源或不同签名的 APK，否则 Android 会拒绝升级。

## 排障

长按电源键没有反应时，先执行：

```text
adb logcat -s "[GeminiMi]"
```

正常情况下应看到类似日志：

```text
PowerKeyOverlay hooked 1 method(s)
hooked PhoneWindowManager#...
assistant settings write success=true verified=true
```

常见情况：

- `hooked 0 method(s)`：当前 HyperOS 版本使用了未知的方法名，需要针对该 ROM 适配。
- `showSessionForActiveService failed`：当前 ROM 的隐藏系统接口签名不同，请在 Issue 中附上完整日志。
- 弹出 Gemini 普通应用而非 Overlay：确认 Google App 的数字助理已切换为 Gemini，并重启设备。
- 屏幕边缘闪一下但 Overlay 没停留：通常是 Google/Gemini 的旧 VoiceInteraction 会话卡住。
  v1.0.6 会在每次唤起前执行保守复位；升级后请重启设备，让 LSPosed 重新加载模块。
- 重启后仍是小爱：确认 LSPosed 作用域包含 `android` 和 `com.miui.voiceassist`。

反馈问题时请提供设备型号、HyperOS/Android 版本、Google App/Gemini 版本和上述日志。

## 已知限制

- HyperOS 不同版本会修改 `PhoneWindowManager` 方法名，不能保证所有设备无需适配。
- Android 16 / HyperOS 3 已适配带 attribution tag 的助手会话接口。
- Overlay 依赖 Google App 的 VoiceInteractionService；仅安装 Gemini、不安装 Google App
  无法工作。
- 模块会在用户解锁时重新写入 Google 助手设置。系统完成启动后，仍允许用户手动切换
  其他数字助理。
- 模块运行在系统框架中。启用前请确保设备具备可用的 LSPosed 救砖/安全模式方案。

## 本 Fork 的兼容性改进

本 Fork 额外扫描 HyperOS 的 `BaseMiuiPhoneWindowManager` 和
`MiuiPhoneWindowManager`，用于适配部分 HyperOS 2 国行 ROM 将长按电源键逻辑放在
小米策略子类、并直接启动超级小爱的情况。只有 Gemini Overlay 成功启动时才拦截
原方法；启动失败会继续执行系统原逻辑，避免长按电源键失效。

针对 HyperOS 2.0.15.0，模块还会精确 Hook
`ShortCutActionsUtils#launchVoiceAssistant(String, Bundle)`。仅当快捷键来源为长按电源键
（`long_press_power_key`）或长按菜单键（`long_press_menu_key`）时转交 Gemini Overlay，
不影响耳机、语音唤醒或其他小爱入口。

从 v1.0.6 起，本 Fork 在调用 `showSessionForActiveService()` 前会先调用
`hideCurrentSession()` 并等待 120ms，再显示新的 Gemini Overlay。这是保守复位方案，
用于处理无 VPN、网络异常或 Google/Gemini 半启动后残留旧助手会话的问题。代价是每次
唤起可能增加约 120ms 延迟。

本 Fork 的 Debug APK 使用本地调试证书签名，不能直接覆盖安装原作者正式版。测试前请
先卸载原版，再安装 Debug APK，并在 LSPosed 中重新启用模块、勾选 `android` 和
`com.miui.voiceassist` 后重启。不要同时启用两个不同来源的 GeminiMi 模块。

## 致谢

本项目 Fork 自 [SherlockChiang/Gemini-mi](https://github.com/SherlockChiang/Gemini-mi)。
感谢原作者 [SherlockChiang](https://github.com/SherlockChiang) 完成 GeminiMi 的原始设计、
HyperOS/LSPosed 适配与开源发布。本 Fork 仅在原项目基础上补充特定 HyperOS 2 ROM 的
电源键策略类兼容性，以及长按菜单键启动 Gemini Overlay 的适配。

## 工作原理

- `PowerKeyOverlayHook` 接管 HyperOS 的长按电源键和长按菜单键助手入口，通过系统语音
  交互服务显示 Overlay。
- `AssistantPersistHook` 设置并验证 Google 助手相关的 Secure/Global 设置。
- `XiaoAiPowerKeyDisableHook` 精确覆盖超级小爱的 `power_wakeup` 偏好。

Hook 失败时会放行原系统逻辑，避免异常继续传播到 `system_server`。

## 开发

使用 JDK 17 和 Android SDK 34：

```text
gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

未配置签名环境变量时，release 产物为：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

仓库通过 GitHub Actions 自动构建签名版本。LSPosed 官方仓库的 Release tag 使用
`VersionCode-VersionName` 格式，例如 `2-1.0.1`。

签名初始化和维护说明见：

```text
scripts/setup-release-signing.ps1
```

## 链接

- [LSPosed 模块页面](https://modules.lsposed.org/module/com.vince.geminimi/)
- [官方模块仓库](https://github.com/Xposed-Modules-Repo/com.vince.geminimi)
- [开发仓库](https://github.com/SherlockChiang/Gemini-mi)
