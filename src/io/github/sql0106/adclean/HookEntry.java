package io.github.sql0106.adclean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * 应用去广告模块入口（libxposed API 102）。
 *
 * <p>一个模块覆盖四个应用：歪麦霸王餐、小蚕惠生活、洪城一卡通、123云盘。
 * 作用域写在 {@code META-INF/xposed/scope.list}（staticScope），用户安装即可用。</p>
 */
public class HookEntry extends XposedModule {

    public static final String TAG = XposedBridge.TAG;

    public static final String WAIMAI = "com.waimaiii.waimaiii";
    public static final String XIAOCAN = "com.realtech.xiaocan";
    public static final String YKT = "com.jieyisoft.weex.yktApp";
    public static final String MFCLOUD = "com.mfcloudcalculate.networkdisk";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        XposedBridge.bind(this);
        log("module loaded, process=" + param.getProcessName()
                + ", api=" + XposedInterface.LIB_API);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!isTarget(pkg)) return;
        ClassLoader cl = param.getDefaultClassLoader();
        log("package loaded: " + pkg);

        try {
            ClassLoadWatch.install(pkg);
        } catch (Throwable t) {
            log("ClassLoadWatch install failed", t);
        }
        try {
            // 开屏广告是 SDK 的 Flutter Overlay View（不是 Activity），
            // 只能在 addView 那一步隐藏；只给歪麦开，别的应用不需要。
            // 123云盘 不在这里：它的壳会让 LSPosed 注入的代码装完就不再执行，
            // 任何 Java 层钩子都不回调（已实测），故不纳入作用域。
            if (WAIMAI.equals(pkg)) {
                SplashViewKiller.install();
                ViewSweeper.install();
            }
        } catch (Throwable t) {
            log("SplashViewKiller install failed", t);
        }
        try {
            ActivityKiller.install(cl);
        } catch (Throwable t) {
            log("ActivityKiller install failed", t);
        }
        if (XIAOCAN.equals(pkg)) {
            try {
                XiaocanHooks.install(cl);
            } catch (Throwable t) {
                log("XiaocanHooks install failed", t);
            }
        } else if (YKT.equals(pkg)) {
            try {
                YktHooks.install(cl);
            } catch (Throwable t) {
                log("YktHooks install failed", t);
            }
            try {
                SystemHooks.install();
            } catch (Throwable t) {
                log("SystemHooks install failed", t);
            }
        }
        log("hooks installed for " + pkg);
    }

    /** 临时诊断：确认进程内「我们的代码」还活着，以及任何 hook 回调是否会发生。 */
    static boolean isTarget(String pkg) {
        return WAIMAI.equals(pkg) || XIAOCAN.equals(pkg) || YKT.equals(pkg);
    }

    public static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    public static void log(String msg, Throwable t) {
        XposedBridge.log(TAG + ": " + msg + " -> " + t);
    }
}
