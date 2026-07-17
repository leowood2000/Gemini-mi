package com.vince.geminimi.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeminiStatusBarHookTest {

    @Test
    public void bypassesOnlyTransparentGoogleVoiceSession() {
        assertTrue(HookPolicy.shouldBypassVoiceInteractionPolicy(
                HookPolicy.TYPE_VOICE_INTERACTION,
                HookPolicy.PIXEL_FORMAT_TRANSPARENT,
                "com.google.android.googlequicksearchbox"));

        assertFalse(HookPolicy.shouldBypassVoiceInteractionPolicy(
                HookPolicy.TYPE_VOICE_INTERACTION,
                -1,
                "com.google.android.googlequicksearchbox"));
        assertFalse(HookPolicy.shouldBypassVoiceInteractionPolicy(
                2,
                HookPolicy.PIXEL_FORMAT_TRANSPARENT,
                "com.google.android.googlequicksearchbox"));
        assertFalse(HookPolicy.shouldBypassVoiceInteractionPolicy(
                HookPolicy.TYPE_VOICE_INTERACTION,
                HookPolicy.PIXEL_FORMAT_TRANSPARENT,
                "com.example.assistant"));
    }
}
