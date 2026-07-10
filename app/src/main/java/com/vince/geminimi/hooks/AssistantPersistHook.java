package com.vince.geminimi.hooks;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;

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
    private static boolean sUserReceiverRegistered;

    public static void applySystemServer(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> sysServer = XposedHelpers.findClass(
                    "com.android.server.SystemServer", lpp.classLoader);
            XposedBridge.hookAllMethods(sysServer, "startOtherServices",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = getSystemContext();
                            writeAll(context);
                            registerUserReceiver(context);
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
                                param.args[2] = Constants.GSB_ASSIST_SERVICE;
                                XposedBridge.log(Constants.TAG
                                        + " blocked rewrite voice_interaction_service="
                                        + val + " -> " + Constants.GSB_ASSIST_SERVICE);
                            } else if (Constants.SECURE_ASSISTANT.equals(key)) {
                                param.args[2] = Constants.GSB_ASSIST_SERVICE;
                                XposedBridge.log(Constants.TAG
                                        + " blocked rewrite assistant="
                                        + val + " -> " + Constants.GSB_ASSIST_SERVICE);
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
                                case Constants.SECURE_VOICE_INTERACT:
                                    param.setResult(Constants.GSB_ASSIST_SERVICE);
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

    private static synchronized void registerUserReceiver(Context context) {
        if (context == null || sUserReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    try {
                        Context userContext = receiverContext.createContextAsUser(
                                android.os.UserHandle.of(userId), 0);
                        writeAll(userContext);
                    } catch (Throwable t) {
                        XposedBridge.log(Constants.TAG + " user " + userId
                                + " settings write failed: " + t);
                    }
                }
            }, filter);
            sUserReceiverRegistered = true;
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " user receiver registration failed: " + t);
        }
    }

    private static void writeAll(Context ctx) {
        if (ctx == null) return;
        ContentResolver cr = ctx.getContentResolver();
        try {
            boolean success = true;
            success &= Settings.Secure.putString(cr, Constants.SECURE_ASSISTANT,
                    Constants.GSB_ASSIST_SERVICE);
            success &= Settings.Secure.putString(cr, Constants.SECURE_VOICE_INTERACT,
                    Constants.GSB_ASSIST_SERVICE);
            success &= Settings.Secure.putString(cr, Constants.SECURE_VOICE_RECOG,
                    Constants.GSB_RECOG_SERVICE);
            success &= Settings.Secure.putInt(cr, Constants.SECURE_ASSIST_STRUCTURE, 1);
            success &= Settings.Secure.putInt(cr, Constants.SECURE_ASSIST_SCREENSHOT, 1);
            success &= Settings.Global.putInt(cr, Constants.GLOBAL_POWER_LONG_PRESS,
                    Constants.LONG_PRESS_POWER_ASSIST);

            boolean verified = Constants.GSB_ASSIST_SERVICE.equals(Settings.Secure.getString(
                    cr, Constants.SECURE_ASSISTANT))
                    && Constants.GSB_ASSIST_SERVICE.equals(Settings.Secure.getString(
                    cr, Constants.SECURE_VOICE_INTERACT))
                    && Settings.Global.getInt(cr, Constants.GLOBAL_POWER_LONG_PRESS, -1)
                    == Constants.LONG_PRESS_POWER_ASSIST;
            XposedBridge.log(Constants.TAG + " assistant settings write success=" + success
                    + " verified=" + verified);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " writeAll failed: " + t);
        }
    }
}
