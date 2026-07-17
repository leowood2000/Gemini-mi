package com.vince.geminimi.hooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class HookPolicy {
    static final String POWER_WAKEUP_KEY = "power_wakeup";
    static final String POWER_LONG_PRESS_SHORTCUT = "long_press_power_key";
    static final String MENU_LONG_PRESS_SHORTCUT = "long_press_menu_key";
    static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    static final int TYPE_VOICE_INTERACTION = 2031;
    static final int PIXEL_FORMAT_TRANSPARENT = -2;

    private HookPolicy() {}

    static boolean shouldHookPowerMethod(Method method) {
        String name = method.getName();
        boolean knownName = name.contains("XiaoAi")
                || name.contains("xiaoAi")
                || name.contains("AiKey")
                || name.equals("launchAssistAction")
                || name.equals("launchAssistantAction")
                || name.equals("launchVoiceAssist")
                || name.equals("launchVoiceAssistant")
                || name.equals("launchVoiceAssistWithWakeLock");
        if (!knownName || Modifier.isStatic(method.getModifiers())) return false;
        Class<?> returnType = method.getReturnType();
        return returnType == Void.TYPE
                || returnType == Boolean.TYPE
                || returnType == Integer.TYPE;
    }

    static boolean isPowerWakeupKey(String key) {
        return POWER_WAKEUP_KEY.equals(key);
    }

    static boolean isAssistantLongPressShortcut(String shortcut) {
        return POWER_LONG_PRESS_SHORTCUT.equals(shortcut)
                || MENU_LONG_PRESS_SHORTCUT.equals(shortcut);
    }

    static boolean shouldBypassVoiceInteractionPolicy(
            int type, int format, String packageName) {
        return type == TYPE_VOICE_INTERACTION
                && format == PIXEL_FORMAT_TRANSPARENT
                && GOOGLE_APP.equals(packageName);
    }
}
