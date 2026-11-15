package io.github.sql0106.adclean;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * 通用广告 SDK 展示层拦截。
 *
 * <p>思路：不改应用逻辑、不改 SDK 请求，只在「广告真正上屏」的那一步做手脚，
 * 这样应用侧的回调、计时器、生命周期都还能正常走，不会卡在启动页或空白页。</p>
 *
 * <ul>
 *   <li>开屏类（类名带 splash）：照常 show，但 show 完把广告容器隐藏 —— 用户看到的是
 *       应用自己的启动背景，倒计时/回调照常走。</li>
 *   <li>插屏 / 激励视频 / 信息流原生：直接跳过 show / render，广告永远不上屏。</li>
 *   <li>isReady / isValid 一律返回 false，应用会走「没有广告」的正常分支。</li>
 * </ul>
 *
 * <p>类名匹配只在广告 SDK 的包前缀内进行，业务类一律不碰。</p>
 */
public final class AdSdkKiller {

    /** 只在这个前缀范围内识别广告类，绝不碰应用自身类。 */
    private static final String[] SDK_PREFIXES = {
            "com.anythink.",
            "com.bytedance.msdk.",
            "com.bytedance.sdk.openadsdk.",
            "com.qq.e.",
            "com.kwad.",
            "com.baidu.mobads.",
            "com.sigmob.",
            "com.windmill.",
            "com.mbridge.",
            "com.unity3d.ads.",
            "com.cqyh.cqadsdk.",
            "com.huawei.openalliance.ad.",
            "com.huawei.hms.ads.",
            "com.mintegral.",
            "com.inmobi.",
            "com.applovin.",
            "com.tapjoy.",
            "com.vungle.",
            "com.chartboost.",
            "com.adcolony.",
            "com.facebook.ads.",
    };

    /** 上屏入口方法名（大小写敏感，SDK 都是小驼峰）。 */
    private static final Set<String> SHOW_METHODS = setOf(
            "show", "showAd", "showAdView", "showSplashAd", "showInterstitial",
            "showRewardVideo", "render");

    /** 状态查询方法名，直接返回 false。 */
    private static final Set<String> READY_METHODS = setOf(
            "isReady", "isAdReady", "isValid", "isAdValid", "isAdLoaded", "hasAd");

    private static final Set<String> DONE =
            Collections.synchronizedSet(new HashSet<String>());

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AdSdkKiller() {
    }

    private static Set<String> setOf(String... names) {
        Set<String> s = new HashSet<String>();
        Collections.addAll(s, names);
        return Collections.unmodifiableSet(s);
    }

    static void inspect(Class<?> clazz) {
        String name = clazz.getName();
        if (!isSdkClass(name)) return;

        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        boolean splashClass = name.toLowerCase().contains("splash");

        for (Method m : methods) {
            if (m.isSynthetic() || Modifier.isAbstract(m.getModifiers())) continue;
            String mn = m.getName();
            boolean show = SHOW_METHODS.contains(mn);
            boolean ready = READY_METHODS.contains(mn);
            if (!show && !ready) continue;

            Class<?> rt = m.getReturnType();
            if (ready) {
                if (rt != boolean.class && rt != Boolean.class) continue;
            } else if (rt != void.class) {
                continue;
            }

            String key = name + '#' + mn + '/' + m.getParameterTypes().length;
            if (!DONE.add(key)) continue;

            try {
                if (ready) {
                    XposedBridge.hookMethod(m, new FalseHook(key));
                } else if (splashClass) {
                    XposedBridge.hookMethod(m, new SplashHideHook(key));
                } else {
                    XposedBridge.hookMethod(m, new SkipHook(key));
                }
                HookEntry.log("hooked " + key);
            } catch (Throwable t) {
                HookEntry.log("hook failed " + key + ": " + t);
            }
        }
    }

    private static boolean isSdkClass(String name) {
        for (String p : SDK_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    /** 插屏 / 激励 / 原生：不上屏。 */
    private static final class SkipHook extends XC_MethodHook {
        private final String key;

        SkipHook(String key) {
            this.key = key;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
            HookEntry.log("skip " + key);
        }
    }

    /** 状态查询：一律「没有广告」。 */
    private static final class FalseHook extends XC_MethodHook {
        private final String key;

        FalseHook(String key) {
            this.key = key;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.FALSE);
        }
    }

    /** 开屏：正常 show（回调照走），随后把广告容器隐藏。 */
    private static final class SplashHideHook extends XC_MethodHook {
        private final String key;

        SplashHideHook(String key) {
            this.key = key;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable()) return;
            hide(param.args);
            HookEntry.log("hide splash " + key);
        }
    }

    private static void hide(Object[] args) {
        if (args == null) return;
        for (Object a : args) {
            if (!(a instanceof ViewGroup)) continue;
            final View v = (View) a;
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        v.setVisibility(View.GONE);
                    } catch (Throwable ignored) {
                        // 容器已被移除，忽略
                    }
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                MAIN.post(r);
            }
        }
    }
}
