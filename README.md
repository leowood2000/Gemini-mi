# GeminiMi

国行 HyperOS 下用 LSPosed 把"长按电源键唤起小爱"换成"长按电源键唤起 Gemini Overlay"。
对标国际版 Mi 设置里的"数字助理 → Google"路径，自动化教程里所有手动步骤，并修复重启
后数字助理被重置的问题。

## 等价的手动步骤（本模块自动完成）

| 教程步骤 | 本模块对应 |
|---------|----------|
| 关闭"超级小爱 → 电源键唤醒" | `XiaoAiPowerKeyDisableHook` |
| 默认应用 → 语音助手选 Google | `AssistantPersistHook#applySettings` |
| 数字助理选 Google | 同上 |
| 开启"使用屏幕上的文字内容 / 使用屏幕截图" | `AssistantPersistHook` 写 `Secure.assist_structure_enabled` / `Secure.assist_screenshot_enabled` |
| 语音输入 / 语音识别选 Google | 同上 |
| 电源键唤起 Google 助手 | `PowerKeyOverlayHook`（直接接管按键事件） |
| 创建快捷方式 → 电源菜单 → 数字助理 | `AssistantPersistHook` 写 `Global.power_button_long_press = 5` |
| 重启后重选 | `AssistantPersistHook#applySystemServer`（开机回写） |

## 编译

```
gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk` —— 自签后在 LSPosed
管理器里启用，作用域勾选 `android` / `com.android.settings` / `com.miui.securitycenter`
/ `com.miui.voiceassist`，重启生效。

## 已知限制

- `PowerKeyOverlayHook` 覆盖了 5 个常见方法名，不同 HyperOS 版本可能命名不同；如果
  长按无反应，抓 `logcat -s "[GeminiMi]"` 看是否打印了 `hooked PhoneWindowManager#…`。
  没打印就反编译当前 ROM 的 `services.jar` 找实际方法名补进 `PowerKeyOverlayHook`。
- Gemini 包名以 `com.google.android.apps.bard` 为准；老版本 Gemini 仍走
  `com.google.android.googlequicksearchbox`，本模块两个都注册。
