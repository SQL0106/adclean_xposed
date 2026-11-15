package io.github.sql0106.adclean;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;
import io.github.sql0106.adclean.compat.XposedHelpers;

/**
 * 洪城一卡通（com.jieyisoft.weex.yktApp 3.2.2）专属规则。
 *
 * <p>这款应用的广告全部走自家封装的 CQAdSDK（com.cqyh.cqadsdk）：
 * 开屏走 {@code CQAdSDKAdLoadStrategy#fetchSplashAd}，
 * 充值/发票/二维码/结果页的信息流走 {@code fetchExpressAd}，
 * 另外还有插屏、激励视频、原生入口。</p>
 *
 * <p>做法：这些 fetch* 方法一律不请求广告，直接回调
 * {@code onAdLoadFailed(AdError)}。应用自己的失败分支是完整业务分支 ——
 * 开屏失败会立刻 {@code gotoMain()}（实测 LaunchActivity$1.onAdLoadFailed →
 * gotoMain），信息流失败就不渲染广告卡片。</p>
 *
 * <p><b>为什么要留一个 deferred 入口：</b>ykt 有 6 个 dex，
 * {@code com.cqyh.cqadsdk.*} 不一定在 classes.dex 里，模块加载的那一刻
 * {@code Class.forName} 会报 class not found（实测 11:17 那次就是这样）。
 * 所以 {@link #inspect(Class)} 由 {@link ClassLoadWatch} 在类真正出现时补装。</p>
 */
public final class YktHooks {

    private static final String STRATEGY = "com.cqyh.cqadsdk.CQAdSDKAdLoadStrategy";
    private static final String EXPRESS = "com.cqyh.cqadsdk.express.CQExpressAd";
    private static final String AD_ERROR = "com.cqyh.cqadsdk.AdError";

    private static final String[] FETCH = {
            "fetchSplashAd", "fetchExpressAd", "fetchInterstitialAd",
            "fetchRewardVideoAd", "fetchNativeAd",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean strategyHooked;
    private static volatile boolean expressHooked;

    private YktHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> strategy = null;
        try {
            strategy = XposedHelpers.findClass(STRATEGY, cl);
        } catch (Throwable t) {
            HookEntry.log("ykt: strategy not loaded yet, waiting for class-load watcher");
        }
        if (strategy != null) {
            try {
                hookStrategy(strategy, cl);
            } catch (Throwable t) {
                HookEntry.log("ykt: strategy hook failed", t);
            }
        }
        try {
            hookExpress(XposedHelpers.findClass(EXPRESS, cl));
        } catch (Throwable t) {
            HookEntry.log("ykt: express not loaded yet, deferred");
        }
    }

    /** 类加载观察器回调：只在精确命中这两个类时才装钩子。 */
    static void inspect(Class<?> c) {
        String n = c.getName();
        if (STRATEGY.equals(n)) {
            try {
                hookStrategy(c, c.getClassLoader());
            } catch (Throwable t) {
                HookEntry.log("ykt: strategy hook failed", t);
            }
        } else if (EXPRESS.equals(n)) {
            try {
                hookExpress(c);
            } catch (Throwable t) {
                HookEntry.log("ykt: express hook failed", t);
            }
        }
    }

    private static void hookStrategy(Class<?> strategy, ClassLoader cl) {
        if (strategyHooked) return;
        strategyHooked = true;

        Constructor<?> ctor = null;
        try {
            Class<?> error = XposedHelpers.findClass(AD_ERROR, cl);
            ctor = error.getDeclaredConstructor(int.class, String.class);
            ctor.setAccessible(true);
        } catch (Throwable t) {
            HookEntry.log("AdError(int,String) not found", t);
        }

        for (String name : FETCH) {
            for (Method m : strategy.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getReturnType() != void.class) continue;
                XposedBridge.hookMethod(m, new FailHook(name, ctor));
                HookEntry.log("hooked " + STRATEGY + "." + name + "/"
                        + m.getParameterTypes().length);
            }
        }
    }

    /** 双保险：万一有已预缓存的广告实例，render/show 也不让它上屏。 */
    private static void hookExpress(Class<?> express) {
        if (expressHooked) return;
        expressHooked = true;
        for (Method m : express.getDeclaredMethods()) {
            if (!m.getName().equals("render") && !m.getName().equals("show")) continue;
            if (m.getReturnType() != void.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
            HookEntry.log("hooked " + EXPRESS + "." + m.getName());
        }
    }

    /** fetch* 一律不请求广告，直接回调失败。 */
    private static final class FailHook extends XC_MethodHook {
        private final String name;
        private final Constructor<?> ctor;

        FailHook(String name, Constructor<?> ctor) {
            this.name = name;
            this.ctor = ctor;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
            Object[] args = param.args;
            if (args == null || args.length == 0) return;
            final Object listener = args[args.length - 1];
            if (listener == null) return;
            final Constructor<?> c = ctor;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Object err = null;
                    if (c != null) {
                        try {
                            err = c.newInstance(Integer.valueOf(1), "AdClean: blocked");
                        } catch (Throwable ignored) {
                        }
                    }
                    if (err != null) {
                        try {
                            XposedHelpers.callMethod(listener, "onAdLoadFailed", err);
                            return;
                        } catch (Throwable ignored) {
                        }
                    }
                    // 兜底：走「广告没有/超时」回调，让应用正常往下走
                    for (String alt : new String[]{"onTimeReached", "onAdSkip", "onAdRenderFailed"}) {
                        try {
                            if ("onAdRenderFailed".equals(alt)) {
                                if (err == null) continue;
                                XposedHelpers.callMethod(listener, alt, err);
                            } else {
                                XposedHelpers.callMethod(listener, alt);
                            }
                            return;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            HookEntry.log("block " + name);
        }
    }
}
