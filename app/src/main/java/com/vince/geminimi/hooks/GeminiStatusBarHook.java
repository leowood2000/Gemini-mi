package com.vince.geminimi.hooks;

import android.view.WindowManager;

import com.vince.geminimi.Constants;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps a transparent Gemini VoiceInteractionSession from replacing the underlying app's
 * system-bar appearance on HyperOS. Expanded Live remains controlled by FloatyActivity.
 */
public final class GeminiStatusBarHook {

    private static final String DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";
    private static final AtomicBoolean FIRST_BYPASS_LOGGED = new AtomicBoolean();

    private GeminiStatusBarHook() {}

    public static void apply(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> policyClass = XposedHelpers.findClass(DISPLAY_POLICY, lpp.classLoader);
            int hooked = 0;
            for (Method method : policyClass.getDeclaredMethods()) {
                if (!"applyPostLayoutPolicyLw".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 4 || params[1] != WindowManager.LayoutParams.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            WindowManager.LayoutParams attrs =
                                    (WindowManager.LayoutParams) param.args[1];
                            if (!HookPolicy.shouldBypassVoiceInteractionPolicy(
                                    attrs.type, attrs.format, attrs.packageName)) return;

                            // The method is void. Skipping only this post-layout policy pass keeps
                            // the session visible while the underlying app supplies bar appearance.
                            param.setResult(null);
                            if (FIRST_BYPASS_LOGGED.compareAndSet(false, true)) {
                                XposedBridge.log(Constants.TAG
                                        + " bypassed transparent Gemini VoiceInteractionSession"
                                        + " for system-bar appearance");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(Constants.TAG
                                    + " status-bar callback failed; using original policy: " + t);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(Constants.TAG + " GeminiStatusBar hooked " + hooked
                    + " method(s)");
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG
                    + " GeminiStatusBar hook unavailable; using original policy: " + t);
        }
    }

}
