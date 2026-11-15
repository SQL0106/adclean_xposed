package io.github.sql0106.adclean;

import android.app.Activity;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * 广告落地页 / 激励视频页 / SDK 自带广告 Activity 的兜底拦截。
 *
 * <p>很多广告 SDK 的开屏、插屏、落地页是独立 Activity。这里在 onCreate 之后直接
 * {@code finish()}：页面根本不会显示，用户也不会被带到广告落地页。</p>
 *
 * <p>只按 SDK 包前缀匹配，应用自身 Activity 一律不碰。每个 Activity 的类名会打一行
 * 诊断日志（上限 30 条），用来确认开屏到底是 Activity 还是 View。</p>
 */
public final class ActivityKiller {

    private static final String[] AD_ACTIVITY_PREFIXES = {
            "com.anythink.",
            "com.qq.e.ads.",
            "com.qq.e.tg.",
            "com.bytedance.sdk.openadsdk.stub.",
            "com.bytedance.sdk.openadsdk.activity.",
            "com.bytedance.sdk.openadsdk.multipro.",
            "com.kwad.sdk.api.proxy.app.",
            "com.kwad.components.",
            "com.baidu.mobads.",
            "com.sigmob.",
            "com.mbridge.",
            "com.unity3d.services.",
            "com.cqyh.cqadsdk.activity.",
            "com.cqyh.cqadsdk.api.",
            "com.huawei.openalliance.ad.activity.",
            "com.windmill.",
            "com.mintegral.",
    };

    private static final AtomicInteger TRACE_QUOTA = new AtomicInteger(30);

    private static boolean installed;

    private ActivityKiller() {
    }

    static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (!(self instanceof Activity)) return;
                String name = self.getClass().getName();
                if (TRACE_QUOTA.getAndDecrement() > 0) {
                    HookEntry.log("activity " + name);
                }
                if (!isAdActivity(name)) return;
                try {
                    ((Activity) self).finish();
                    HookEntry.log("finish ad activity " + name);
                } catch (Throwable t) {
                    HookEntry.log("finish ad activity failed: " + name, t);
                }
            }
        };
        XposedBridge.hookMethod(findOnCreate(), hook);
    }

    private static Method findOnCreate() {
        try {
            return Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            throw new RuntimeException("Activity#onCreate not found", t);
        }
    }

    private static boolean isAdActivity(String name) {
        for (String p : AD_ACTIVITY_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }
}
