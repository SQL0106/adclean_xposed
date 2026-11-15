package io.github.sql0106.adclean;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * View 树清扫器：把已经上屏的广告 View 逐个隐藏。
 *
 * <p>为什么需要它：{@code SplashViewKiller} 只拦 {@code ViewGroup#addView}，
 * 但广告常常不是「SDK 容器直接 addView」进来的 —— 例如 123云盘 的开屏是它自己的
 * {@code SplashActivity} 里用优量汇渲染，广告 View 挂在应用的普通 FrameLayout 下，
 * addView 那条路上看不到任何 SDK 类名，于是广告照常显示（实测截图与日志都能证明）。</p>
 *
 * <p>做法：Activity {@code onCreate} / {@code onResume} 之后，在若干个时间点遍历
 * DecorView 子树，凡类名命中广告 SDK 前缀的 View 直接 {@code GONE}。只在主线程、
 * 只在 Activity 生命周期触发，节点数有上限，代价可忽略。</p>
 */
public final class ViewSweeper {

    /** 只扫启动页，4 个时间点足够覆盖广告上屏的时机。 */
    private static final long[] DELAYS = {0, 400, 1200, 2500};

    /** 单次遍历的节点上限，防止极端布局把主线程拖住。 */
    private static final int MAX_NODES = 800;

    private static final AtomicInteger SCHED_QUOTA = new AtomicInteger(20);

    private static final AtomicInteger HIDE_QUOTA = new AtomicInteger(40);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean installed;

    private ViewSweeper() {
    }

    static void install() {
        if (installed) return;
        installed = true;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object self = param.thisObject;
                if (!(self instanceof Activity)) return;
                schedule((Activity) self);
            }
        };

        int n = 0;
        n += hookMethod("onCreate", new Class<?>[]{android.os.Bundle.class}, hook);
        n += hookMethod("onResume", new Class<?>[0], hook);
        n += hookMethod("onStart", new Class<?>[0], hook);
        HookEntry.log("view sweeper installed, " + n + " entry point(s)");
    }

    private static int hookMethod(String name, Class<?>[] sig, XC_MethodHook hook) {
        try {
            java.lang.reflect.Method m = Activity.class.getDeclaredMethod(name, sig);
            return XposedBridge.hookMethod(m, hook) ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void schedule(final Activity activity) {
        final String name = activity.getClass().getName();
        final boolean splashLike = isSplashLike(name);
        if (SCHED_QUOTA.getAndDecrement() > 0) {
            HookEntry.log("sweep scheduled on " + name + " splashLike=" + splashLike);
        }
        // 只扫开屏/启动页：那是广告唯一必现的位置。首页、列表页一概不扫，
        // 免得每次切页都白遍历一遍 View 树（这是性能红线）。
        if (!splashLike) return;
        for (final long d : DELAYS) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        sweep(activity, splashLike);
                    } catch (Throwable t) {
                        // 页面已经销毁等，忽略
                    }
                }
            }, d);
        }
    }

    private static boolean isSplashLike(String name) {
        String n = name.toLowerCase();
        return n.contains("splash") || n.contains("launcher") || n.contains("launch");
    }

    private static void sweep(Activity activity, boolean splashLike) {
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        int[] counter = {0};
        walk(root, 0, counter, splashLike);
    }

    private static void walk(View v, int depth, int[] counter, boolean splashLike) {
        if (v == null || counter[0] >= MAX_NODES || depth > 40) return;
        counter[0]++;

        String name = v.getClass().getName();
        if (AdSdkKiller.isSdkClass(name)) {
            if (HIDE_QUOTA.getAndDecrement() > 0) {
                HookEntry.log("sweep hide " + name);
            }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                if (c != null) walk(c, depth + 1, counter, splashLike);
            }
        }
    }
}
