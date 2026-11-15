package io.github.sql0106.adclean;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * 开屏广告的 View 级拦截 —— 歪麦霸王餐 / 123云盘 靠这条。
 *
 * <p>这两个应用是「易盾加固 + Flutter」，开屏广告不是独立 Activity，而是 SDK 的
 * Flutter 插件把一个 Overlay 直接 {@code addView} 到应用窗口上：
 * 实测歪麦是 <b>com.anythink.flutter.splash.ATSplashOverlayView</b>
 * （再往里是快手 / 穿山甲的 {@code com.byazt.pi.TTCountdownViewForCircle} 等）。</p>
 *
 * <p>这些广告类**永远不会出现在 ClassLoader 的常规加载路径上**（加固壳用自己的方式
 * 定义内存 dex），所以按类名装钩子看不到它们；但 View 一定会被 addView 到窗口，
 * 这里就在那一步把广告 View 隐藏。挂 {@code ViewGroup#addView} 不会碰到业务逻辑，
 * 只做「取类名 → 前缀比较」，Flutter 应用 addView 次数极少，开销可忽略。</p>
 */
public final class SplashViewKiller {


    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 隐藏后 SDK 还可能把 visibility 改回来，重试几次。 */
    private static final long[] REHIDE_DELAYS = {300L, 1000L, 2500L};

    private static final Set<String> HIDDEN =
            Collections.synchronizedSet(new HashSet<String>());
    /** 非开屏的广告 View 只记日志（限量），供后续补规则。 */
    private static final AtomicInteger QUOTA = new AtomicInteger(20);

    private static boolean installed;

    private SplashViewKiller() {
    }

    static void install() {
        if (installed) return;
        installed = true;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object[] args = param.args;
                if (args == null || args.length == 0) return;
                Object child = args[0];
                if (!(child instanceof View)) return;

                String name = child.getClass().getName();
                if (!AdSdkKiller.isSdkClass(name)) return;

                if (isSplash(name)) {
                    hide((View) child, name);
                } else if (QUOTA.getAndDecrement() > 0) {
                    HookEntry.log("ad view + " + name);
                }
            }
        };

        int n = XposedBridge.hookAllMethods(ViewGroup.class, "addView", hook);
        HookEntry.log("splash view killer installed, " + n + " addView overload(s)");
    }

    private static boolean isSplash(String name) {
        String lower = name.toLowerCase();
        return lower.contains("splash") || lower.contains("openscreen");
    }

    private static void hide(final View view, final String name) {
        hideNow(view);
        if (HIDDEN.add(name)) {
            HookEntry.log("hide splash view " + name);
        }
        for (long delay : REHIDE_DELAYS) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideNow(view);
                }
            }, delay);
        }
    }

    private static void hideNow(View view) {
        try {
            view.setVisibility(View.GONE);
        } catch (Throwable ignored) {
            // 已被移除
        }
    }
}
