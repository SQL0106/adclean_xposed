package io.github.sql0106.adclean;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * 运行时类加载观察器。
 *
 * <p>歪麦、小蚕、123云盘三个应用都带壳（业务 dex 运行时才解密加载），所以模块加载时
 * 用 {@code Class.forName} 找不到业务类。</p>
 *
 * <p><b>为什么不能只挂 {@code ClassLoader#loadClass}：</b>加固壳会自己写一个
 * ClassLoader 子类覆盖 {@code loadClass}，或者直接用
 * {@code DexFile#defineClass} / {@code loadClassBinaryName} 从内存 dex 定义类。
 * 这两种路径都不会走到 {@code java.lang.ClassLoader#loadClass}（子类覆盖是另一个方法），
 * 于是广告 SDK 的类永远看不见 —— 实测歪麦/123云盘就是这样，日志里一条 hook 都没有。</p>
 *
 * <p>观察的入口（都是「类真正被定义/找到」的必经点，各自覆盖一类加载方式）：</p>
 * <ol>
 *   <li>{@code ClassLoader#loadClass}：普通应用、DexClassLoader 插件；</li>
 *   <li>{@code dalvik.system.DexPathList#findClass}：所有标准 dex 加载的终点
 *       （{@code BaseDexClassLoader#findClass} 只是转调它，所以不重复挂）；</li>
 *   <li>{@code dalvik.system.DexFile#loadClassBinaryName} / {@code DexFile#defineClass}：
 *       内存 dex、解密后的 dex；</li>
 *   <li>{@code java.lang.ClassLoader#defineClass(byte[])}：动态生成字节码。</li>
 * </ol>
 *
 * <p><b>性能（重点）：</b>这些入口每个类都会走一次，所以判定必须廉价 ——
 * 先判空、{@code instanceof Class}，再拿类名做**纯字符串前缀比较**，不命中立刻返回；
 * 命中才进 {@code SEEN} 去重、拿 {@code ThreadLocal} 防重入、反射看方法。全程无锁、
 * 无分配、不打日志（日志只在真正 hook 到东西时打）。</p>
 */
public final class ClassLoadWatch {

    private static volatile boolean installed;
    private static String sPackage = "";

    /** 已处理过的类名（只存命中名单的类，数量极小）。 */
    private static final Set<String> SEEN =
            Collections.synchronizedSet(new HashSet<String>(64));

    /** 防重入：dispatch 里反射、打日志可能再触发类加载。 */
    private static final ThreadLocal<Boolean> IN_DISPATCH = new ThreadLocal<Boolean>();

    private ClassLoadWatch() {
    }

    static void install(String pkg) {
        if (installed) return;
        installed = true;
        sPackage = pkg;

        ClassResultHook hook = new ClassResultHook("?");

        // 1) 常规路径（小蚕这类壳走的就是它）
        int n = XposedBridge.hookAllMethods(ClassLoader.class, "loadClass",
                new ClassResultHook("ClassLoader#loadClass"));
        // 2) 自定义 loadClass / 标准 dex 加载的终点
        n += watch("dalvik.system.DexPathList", "findClass", hook);
        if (!HookEntry.YKT.equals(pkg)) {
            // 3) 内存 dex / 解密 dex：完全绕过 ClassLoader（没有壳的应用不需要）
            n += watch("dalvik.system.DexFile", "loadClassBinaryName", hook);
            n += watch("dalvik.system.DexFile", "defineClass", hook);
            // 4) 动态生成字节码
            n += watch("java.lang.ClassLoader", "defineClass", hook);
        }

        HookEntry.log("class-load watcher installed, " + n + " entry points");
    }

    private static int watch(String className, String methodName, XC_MethodHook hook) {
        try {
            Class<?> c = Class.forName(className, false, ClassLoader.getSystemClassLoader());
            return XposedBridge.hookAllMethods(c, methodName,
                    new ClassResultHook(className + "#" + methodName));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 廉价预筛：绝大部分类（框架、应用自身）在这里就返回 false，
     * 不碰集合、不碰反射。字符串前缀比较是几个字符的比较，成本可忽略。
     */
    private static boolean interesting(String name) {
        if (AdSdkKiller.isSdkClass(name)) return true;
        if (HookEntry.XIAOCAN.equals(sPackage)) return name.startsWith("com.realtech.");
        if (HookEntry.YKT.equals(sPackage)) return name.startsWith("com.cqyh.cqadsdk.");
        return false;
    }

    private static final class ClassResultHook extends XC_MethodHook {
        private final String label;
        /** 入口是否已经活过一次（只在第一次打日志，用来确认钩子真的生效）。 */
        private boolean fired;

        ClassResultHook(String label) {
            this.label = label;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!fired) {
                fired = true;
                HookEntry.log("class-load entry alive: " + label);
            }
            if (param.hasThrowable()) return;
            Object r = param.getResult();
            if (!(r instanceof Class)) return;
            Class<?> clazz = (Class<?>) r;
            String name = clazz.getName();
            if (!interesting(name)) return;
            if (IN_DISPATCH.get() != null) return;
            IN_DISPATCH.set(Boolean.TRUE);
            try {
                if (!SEEN.add(name)) return;
                dispatch(clazz, name);
            } finally {
                IN_DISPATCH.remove();
            }
        }
    }

    private static void dispatch(Class<?> clazz, String name) {
        try {
            AdSdkKiller.inspect(clazz, name);
        } catch (Throwable t) {
            HookEntry.log("sdk inspect failed: " + name, t);
        }
        if (HookEntry.XIAOCAN.equals(sPackage)) {
            try {
                XiaocanHooks.inspect(clazz);
            } catch (Throwable t) {
                HookEntry.log("xiaocan inspect failed: " + name, t);
            }
        } else if (HookEntry.YKT.equals(sPackage)) {
            try {
                YktHooks.inspect(clazz);
            } catch (Throwable t) {
                HookEntry.log("ykt inspect failed: " + name, t);
            }
        }
    }
}
