package com.vince.geminimi.hooks;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;

import java.util.List;

import com.vince.geminimi.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 解决教程里"重启后需要重新选中数字助理"的问题，并替代「创建快捷方式 app →
 * 电源菜单 → 数字助理」那一步：
 *  - 在 system_server 启动时把 Secure.assistant / voice_interaction_service /
 *    voice_recognition_service 写成 Google，并打开 assist_structure_enabled /
 *    assist_screenshot_enabled，同时把 Global.power_button_long_press 写成 5
 *    (LONG_PRESS_POWER_ASSISTANT)。后者就是隐藏 Activity 选择"数字助理"时实际
 *    写入的 key，写好之后框架原生的长按路径会直接走到 Gemini。
 *  - 在 Settings/Security 读这些 key 时拦截返回正确值，避免界面把它显示成
 *    "未选择"导致 MIUI 内部的归零逻辑触发。
 */
public final class AssistantPersistHook {

    private AssistantPersistHook() {}

    /** 运行时发现的 Bard VoiceInteractionService。null 表示走 GSB 兜底。 */
    private static volatile String sGeminiVis;
    /** 真正写到 Settings.Secure.assistant 的值（VIS 优先，其次 Activity）。 */
    private static volatile String sAssistantValue = Constants.GEMINI_ASSISTANT_COMP;
    /** 真正写到 Settings.Secure.voice_interaction_service 的值。 */
    private static volatile String sVisValue = Constants.GSB_ASSIST_SERVICE;

    private static void resolveGeminiVis(Context ctx) {
        if (ctx == null) return;
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent probe = new Intent("android.service.voice.VoiceInteractionService")
                    .setPackage(Constants.GEMINI_PKG);
            List<ResolveInfo> infos = pm.queryIntentServices(probe,
                    PackageManager.GET_META_DATA);
            if (infos != null && !infos.isEmpty()) {
                ServiceInfo si = infos.get(0).serviceInfo;
                sGeminiVis = si.packageName + "/" + si.name;
                sVisValue = sGeminiVis;
                sAssistantValue = sGeminiVis;     // assistant 也要指向 VIS 才能弹 overlay
                XposedBridge.log(Constants.TAG + " discovered Bard VIS: " + sGeminiVis);
            } else {
                XposedBridge.log(Constants.TAG
                        + " Bard exposes no VoiceInteractionService; falling back to GSB");
            }
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " resolveGeminiVis failed: " + t);
        }
    }

    public static void applySystemServer(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> sysServer = XposedHelpers.findClass(
                    "com.android.server.SystemServer", lpp.classLoader);
            XposedBridge.hookAllMethods(sysServer, "startOtherServices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            writeAll(getSystemContext());
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " AssistantPersist sysServer hook failed: " + t);
        }

        // 拦写：MIUI 在开机后会主动把 voice_interaction_service / assistant 写回小爱。
        // hook 所有 putStringForUser 重载，凡是目标 key 是这两条且 value 含 "voiceassist"
        // (小爱包名 com.miui.voiceassist) 的，强制改成我们的目标值。
        try {
            XposedBridge.hookAllMethods(Settings.Secure.class, "putStringForUser",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 3) return;
                            Object keyObj = param.args[1];
                            Object valObj = param.args[2];
                            if (!(keyObj instanceof String) || !(valObj instanceof String)) return;
                            String key = (String) keyObj;
                            String val = (String) valObj;
                            boolean wantsXiaoAi = val.contains("voiceassist")
                                    || val.contains("xiaomi.voiceassistant");
                            if (!wantsXiaoAi) return;
                            if (Constants.SECURE_VOICE_INTERACT.equals(key)) {
                                param.args[2] = sVisValue;
                                XposedBridge.log(Constants.TAG
                                        + " blocked rewrite voice_interaction_service="
                                        + val + " -> " + sVisValue);
                            } else if (Constants.SECURE_ASSISTANT.equals(key)) {
                                param.args[2] = sAssistantValue;
                                XposedBridge.log(Constants.TAG
                                        + " blocked rewrite assistant="
                                        + val + " -> " + sAssistantValue);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " putStringForUser hook failed: " + t);
        }
    }

    public static void applySettings(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getStringForUser",
                    ContentResolver.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[1];
                            if (key == null) return;
                            switch (key) {
                                case Constants.SECURE_ASSISTANT:
                                    ensureResolved(param.args[0]);
                                    param.setResult(sAssistantValue);
                                    break;
                                case Constants.SECURE_VOICE_INTERACT:
                                    ensureResolved(param.args[0]);
                                    param.setResult(sVisValue);
                                    break;
                                case Constants.SECURE_VOICE_RECOG:
                                    param.setResult(Constants.GSB_RECOG_SERVICE);
                                    break;
                                default:
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " AssistantPersist settings hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Settings.Secure.class, "getInt",
                    ContentResolver.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[1];
                            if (Constants.SECURE_ASSIST_STRUCTURE.equals(key)
                                    || Constants.SECURE_ASSIST_SCREENSHOT.equals(key)) {
                                param.setResult(1);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " AssistantPersist secure int hook failed: " + t);
        }

        // Settings.Global.getIntForUser 在 HyperOS 上 4-参签名缺失，直接 hook
        // 公共的 3-参 getInt(cr, name, def)。framework 内部最终都会落到这条。
        try {
            XposedHelpers.findAndHookMethod(Settings.Global.class, "getInt",
                    ContentResolver.class, String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[1];
                            if (Constants.GLOBAL_POWER_LONG_PRESS.equals(key)) {
                                param.setResult(Constants.LONG_PRESS_POWER_ASSIST);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " AssistantPersist global hook failed: " + t);
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            return (Context) XposedHelpers.callMethod(thread, "getSystemContext");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeAll(Context ctx) {
        if (ctx == null) return;
        resolveGeminiVis(ctx);
        ContentResolver cr = ctx.getContentResolver();
        try {
            Settings.Secure.putString(cr, Constants.SECURE_ASSISTANT, sAssistantValue);
            Settings.Secure.putString(cr, Constants.SECURE_VOICE_INTERACT, sVisValue);
            Settings.Secure.putString(cr, Constants.SECURE_VOICE_RECOG,
                    Constants.GSB_RECOG_SERVICE);
            Settings.Secure.putInt(cr, Constants.SECURE_ASSIST_STRUCTURE, 1);
            Settings.Secure.putInt(cr, Constants.SECURE_ASSIST_SCREENSHOT, 1);
            Settings.Global.putInt(cr, Constants.GLOBAL_POWER_LONG_PRESS,
                    Constants.LONG_PRESS_POWER_ASSIST);
            XposedBridge.log(Constants.TAG + " wrote assistant=" + sAssistantValue
                    + " vis=" + sVisValue);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " writeAll failed: " + t);
        }
    }

    private static void ensureResolved(Object cr) {
        if (sGeminiVis != null) return;
        try {
            Context ctx = (Context) XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null),
                            "currentActivityThread"),
                    "getSystemContext");
            resolveGeminiVis(ctx);
        } catch (Throwable ignored) { }
    }
}
