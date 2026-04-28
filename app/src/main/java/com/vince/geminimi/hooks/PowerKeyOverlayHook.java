package com.vince.geminimi.hooks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.vince.geminimi.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 接管长按电源键，发送 ACTION_ASSIST，由系统路由到当前数字助理 (Gemini) 的 Overlay。
 * 国行 HyperOS 通常在 PhoneWindowManager 的 launchAssistAction / launchVoiceAssist
 * 或 MIUI 自定义的 launchSuperXiaoAi 上做分支。我们覆盖最常见的几个签名。
 */
public final class PowerKeyOverlayHook {

    private PowerKeyOverlayHook() {}

    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final int SHOW_WITH_ASSIST = 1;
    private static final int SHOW_WITH_SCREENSHOT = 1 << 1;
    private static final int SHOW_SOURCE_PUSH_TO_TALK = 1 << 4;
    private static final int SHOW_POWER_ASSIST_WITH_SCREENSHOT =
            SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_PUSH_TO_TALK;

    public static void apply(XC_LoadPackage.LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;

        // 兜底：HyperOS 真正干活的方法可能不在 PhoneWindowManager 里 (logcat 显示
        // MiuiInputKeyEventLog 在 launchVoiceAssistant 里直接调 startForegroundServiceAsUser)。
        // 拦截 ContextImpl.startServiceCommon / startActivityAsUser，凡是目标包是
        // com.miui.voiceassist 的就改发 ACTION_ASSIST 到 Gemini。
        installContextRedirect(cl);

        Class<?> pwm;
        try {
            pwm = XposedHelpers.findClass(PWM, cl);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " PhoneWindowManager not found: " + t);
            return;
        }

        // 不再枚举固定名字。HyperOS 各版本把方法叫 launchSuperXiaoAi /
        // launchXiaoAiOnPowerLong / launchXiaoAiByLongPressPower / launchAiKey ...
        // 都不一样。统一用名字模式扫描：含 XiaoAi / Assist / Voice / AiKey 的全接管。
        int hooked = 0;
        for (java.lang.reflect.Method m : pwm.getDeclaredMethods()) {
            String n = m.getName();
            boolean match =
                    n.contains("XiaoAi")
                 || n.contains("xiaoAi")
                 || n.contains("AiKey")
                 || n.equals("launchAssistAction")
                 || n.equals("launchAssistantAction")
                 || n.equals("launchVoiceAssist")
                 || n.equals("launchVoiceAssistant")          // HyperOS 实际名字
                 || n.equals("launchVoiceAssistWithWakeLock");
            if (!match) continue;
            hookOne(pwm, m);
            hooked++;
        }
        XposedBridge.log(Constants.TAG + " PowerKeyOverlay hooked " + hooked + " method(s)");
    }

    private static void hookOne(Class<?> clazz, java.lang.reflect.Method m) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Context ctx = (Context) XposedHelpers
                        .getObjectField(param.thisObject, "mContext");
                if (ctx == null) return;
                if (sendAssist(ctx)) {
                    param.setResult(null);
                }
            }
        });
        XposedBridge.log(Constants.TAG + " hooked " + clazz.getSimpleName()
                + "#" + m.getName() + " " + m);
    }

    private static void installContextRedirect(ClassLoader cl) {
        try {
            Class<?> ctxImpl = XposedHelpers.findClass("android.app.ContextImpl", cl);
            // 任何 startService / startForegroundService / startActivity 系列调用，只要
            // intent 的目标包是小爱，就改成 ACTION_ASSIST。
            for (java.lang.reflect.Method m : ctxImpl.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.startsWith("startService") && !n.startsWith("startForegroundService")
                        && !n.startsWith("startActivity")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0 || pt[0] != Intent.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent in = (Intent) param.args[0];
                        if (in == null) return;
                        String pkg = in.getPackage();
                        String cmp = in.getComponent() != null
                                ? in.getComponent().getPackageName() : null;
                        boolean toXiaoAi =
                                Constants.XIAOAI_PKG.equals(pkg)
                             || Constants.XIAOAI_PKG.equals(cmp);
                        if (!toXiaoAi) return;
                        Context ctx = (Context) param.thisObject;
                        XposedBridge.log(Constants.TAG + " redirected " + n
                                + " -> Gemini (was " + (cmp != null ? cmp : pkg) + ")");
                        if (sendAssist(ctx)) {
                            param.setResult(redirectResult(m, in));
                        }
                    }
                });
            }
            XposedBridge.log(Constants.TAG + " ContextImpl redirect installed");
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " ContextImpl redirect failed: " + t);
        }
    }

    private static Object redirectResult(java.lang.reflect.Method m, Intent original) {
        Class<?> rt = m.getReturnType();
        if (rt == Void.TYPE) return null;
        if (ComponentName.class.isAssignableFrom(rt)) {
            ComponentName originalComponent = original.getComponent();
            if (originalComponent != null) return originalComponent;
            return new ComponentName(Constants.XIAOAI_PKG, "redirected.to.Gemini");
        }
        if (rt == Boolean.TYPE) return true;
        if (rt == Integer.TYPE) return 0;
        return null;
    }

    private static boolean sendAssist(Context ctx) {
        // Bard 自身没声明 VoiceInteractionService，唯一弹 overlay 的路径是
        // Pixel 那条："framework → GSB VIS → GSB 把请求 forward 给 Bard overlay"。
        // 前提：voice_interaction_service 必须是 GSB 那个组件。MIUI 在开机后会
        // 把它回写成小爱 VIS，所以这里每次触发都现场改一次（system_server 自身
        // put 不受 WRITE_SECURE_SETTINGS 权限限制）。
        ensureGoogleVis(ctx);

        // 真·overlay：让当前 VoiceInteractionService 直接显示 session UI。
        if (tryShowSession()) return true;

        // SystemUI AssistManager (国行 HyperOS 大概率没接，留作兜底)。
        if (tryStatusBarStartAssist()) return true;

        XposedBridge.log(Constants.TAG
                + " overlay launch failed; skip SearchManager.launchAssist() to avoid Gemini app");
        return false;
    }

    private static void ensureGoogleVis(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr,
                    Constants.SECURE_VOICE_INTERACT);
            if (Constants.GSB_ASSIST_SERVICE.equals(cur)) return;
            android.provider.Settings.Secure.putString(cr,
                    Constants.SECURE_VOICE_INTERACT, Constants.GSB_ASSIST_SERVICE);
            XposedBridge.log(Constants.TAG + " runtime forced voice_interaction_service "
                    + cur + " -> " + Constants.GSB_ASSIST_SERVICE);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " ensureGoogleVis failed: " + t);
        }
    }

    private static boolean tryShowSession() {
        try {
            Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", null);
            android.os.IBinder binder = (android.os.IBinder) XposedHelpers.callStaticMethod(
                    sm, "getService", "voiceinteraction");
            if (binder == null) return false;
            Class<?> stub = XposedHelpers.findClass(
                    "com.android.internal.app.IVoiceInteractionManagerService$Stub", null);
            Object svc = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
            if (svc == null) return false;
            invokeBest(svc, "showSessionForActiveService");
            XposedBridge.log(Constants.TAG
                    + " launched via showSessionForActiveService() (overlay path)");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " showSessionForActiveService failed: " + t);
            return false;
        }
    }

    private static android.os.Bundle assistArgs() {
        android.os.Bundle args = new android.os.Bundle();
        args.putInt("invocation_type", 7);              // power-long-press
        args.putString("invocation_source", "POWER_LONG_PRESS");
        args.putBoolean("request_assist_structure", true);
        args.putBoolean("request_assist_screenshot", true);
        return args;
    }

    private static boolean tryStatusBarStartAssist() {
        try {
            Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", null);
            Object binder = XposedHelpers.callStaticMethod(serviceManager, "getService", "statusbar");
            if (binder == null) return false;
            Class<?> stub = XposedHelpers.findClass(
                    "com.android.internal.statusbar.IStatusBarService$Stub", null);
            Object statusBar = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
            if (statusBar == null) return false;
            invokeBest(statusBar, "startAssist");
            XposedBridge.log(Constants.TAG
                    + " launched via IStatusBarService.startAssist() (overlay path)");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " statusbar startAssist failed: " + t);
            return false;
        }
    }

    private static boolean tryLaunchAssist(Context ctx) {
        try {
            Object sm = ctx.getSystemService("search");
            if (sm == null) return false;
            XposedHelpers.callMethod(sm, "launchAssist", assistArgs());
            XposedBridge.log(Constants.TAG
                    + " launched via SearchManager.launchAssist() (overlay path)");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " launchAssist failed: " + t);
            return false;
        }
    }

    private static Object invokeBest(Object target, String name) throws Throwable {
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (canBuildArgs(m.getParameterTypes())) {
                best = m;
                break;
            }
        }
        if (best == null) {
            for (java.lang.reflect.Method m : target.getClass().getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (canBuildArgs(m.getParameterTypes())) {
                    best = m;
                    break;
                }
            }
        }
        if (best == null) {
            logCandidateMethods(target, name);
            throw new NoSuchMethodError(target.getClass().getName() + "#" + name);
        }
        best.setAccessible(true);
        Object[] args = buildArgs(best.getParameterTypes());
        XposedBridge.log(Constants.TAG + " invoking " + methodSig(best));
        return best.invoke(target, args);
    }

    private static boolean canBuildArgs(Class<?>[] pts) {
        for (Class<?> pt : pts) {
            if (pt == android.os.Bundle.class) continue;
            if (pt == int.class || pt == Integer.class) continue;
            if (pt == boolean.class || pt == Boolean.class) continue;
            if (pt == android.os.IBinder.class) continue;
            if (!pt.isPrimitive()) continue;
            return false;
        }
        return true;
    }

    private static Object[] buildArgs(Class<?>[] pts) {
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Class<?> pt = pts[i];
            if (pt == android.os.Bundle.class) {
                args[i] = assistArgs();
            } else if (pt == int.class || pt == Integer.class) {
                args[i] = SHOW_POWER_ASSIST_WITH_SCREENSHOT;
            } else if (pt == boolean.class || pt == Boolean.class) {
                args[i] = true;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static void logCandidateMethods(Object target, String name) {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (name.equals(m.getName())) {
                XposedBridge.log(Constants.TAG + " candidate " + methodSig(m));
            }
        }
        for (java.lang.reflect.Method m : target.getClass().getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                XposedBridge.log(Constants.TAG + " candidate " + methodSig(m));
            }
        }
    }

    private static String methodSig(java.lang.reflect.Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append("#").append(m.getName()).append("(");
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(pts[i].getName());
        }
        sb.append(")");
        return sb.toString();
    }
}
