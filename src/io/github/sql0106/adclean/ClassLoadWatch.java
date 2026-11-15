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
 * 用 {@code Class.forName} 找不到业务类。这里挂 {@link ClassLoader#loadClass}，
 * 每加载出一个类就交给各 killer 判断一次：只对命中名单的类做处理，其余直接返回。</p>
 *
 * <p>性能：一次 loadClass 只做「类名前缀比较 + 数组遍历」，没有反射、没有锁；
 * 命中后才进同步块去重。进程内只装一次。</p>
 */
public final class ClassLoadWatch {

    private static volatile boolean installed;
    private static String sPackage = "";

    /** 已处理过的类名（不同 ClassLoader 同名类只处理一次，避免重复 hook）。 */
    private static final Set<String> SEEN =
            Collections.synchronizedSet(new HashSet<String>());

    private ClassLoadWatch() {
    }

    static void install(String pkg) {
        sPackage = pkg;
        if (installed) return;
        installed = true;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object r = param.getResult();
                if (!(r instanceof Class)) return;
                Class<?> clazz = (Class<?>) r;
                if (!SEEN.add(clazz.getName())) return;
                dispatch(clazz);
            }
        };
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", hook);
        HookEntry.log("class-load watcher installed");
    }

    private static void dispatch(Class<?> clazz) {
        try {
            AdSdkKiller.inspect(clazz);
        } catch (Throwable t) {
            HookEntry.log("sdk inspect failed: " + clazz.getName(), t);
        }
        if (HookEntry.XIAOCAN.equals(sPackage)) {
            try {
                XiaocanHooks.inspect(clazz);
            } catch (Throwable t) {
                HookEntry.log("xiaocan inspect failed: " + clazz.getName(), t);
            }
        }
    }
}
