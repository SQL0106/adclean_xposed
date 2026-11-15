package io.github.sql0106.adclean.compat;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 兼容层：仿 legacy Xposed 的 {@code XposedBridge}，把 hookMethod / hookAllMethods / log
 * 转发到 libxposed 现代 API（102）。
 *
 * <p>{@link #bind(XposedInterface)} 由 {@code HookEntry.onModuleLoaded} 调用一次。</p>
 */
public final class XposedBridge {

    /** logcat / LSPosed 日志里统一的前缀 tag。 */
    public static final String TAG = "ADCLEAN";

    /** android.util.Log.INFO，避免 import android.* 也能用。 */
    private static final int INFO = 4;

    private static volatile XposedInterface sFramework;

    private XposedBridge() {
    }

    public static void bind(XposedInterface framework) {
        sFramework = framework;
    }

    public static void log(String msg) {
        XposedInterface f = sFramework;
        if (f != null) f.log(INFO, TAG, msg);
    }

    public static void log(String msg, Throwable t) {
        XposedInterface f = sFramework;
        if (f != null) {
            f.log(INFO, TAG, msg);
            f.log(INFO, TAG, String.valueOf(t));
        }
    }

    /** hook 单个方法/构造器。 */
    public static void hookMethod(Member member, XC_MethodHook callback) {
        XposedInterface f = sFramework;
        if (f == null) throw new IllegalStateException("XposedBridge not bound yet");
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("not executable: " + member);
        }
        f.hook((Executable) member)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(new BridgeHooker(callback));
    }

    /** hook 名字匹配的全部重载，返回成功个数（抽象的跳过，不抛）。 */
    public static int hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int n = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
            try {
                hookMethod(m, callback);
                n++;
            } catch (Throwable ignored) {
                // 单个重载 hook 失败不影响其它重载
            }
        }
        return n;
    }

    /** 把现代 API 的拦截链适配成 before/after 两段式。 */
    private static final class BridgeHooker implements Hooker {
        private final XC_MethodHook cb;

        BridgeHooker(XC_MethodHook cb) {
            this.cb = cb;
        }

        @Override
        public Object intercept(Chain chain) throws Throwable {
            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.thisObject = chain.getThisObject();
            p.method = chain.getExecutable();
            List<Object> args = chain.getArgs();
            p.args = args == null ? new Object[0] : args.toArray(new Object[0]);

            cb.invokeBefore(p);
            if (p.hasResult()) return p.getResult();

            Object ret;
            try {
                ret = chain.proceed(p.args);
            } catch (Throwable t) {
                p.setThrowable(t);
                cb.invokeAfter(p);
                if (p.hasResult()) return p.getResult();
                throw t;
            }

            cb.invokeAfter(p);
            return p.hasResult() ? p.getResult() : ret;
        }
    }
}
