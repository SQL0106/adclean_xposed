package io.github.sql0106.adclean;

import android.os.BaseBundle;

import java.lang.reflect.Method;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;

/**
 * 洪城一卡通专用的系统层兼容补丁（不是去广告）。
 *
 * <p>崩溃栈（`/data/system/dropbox/data_app_crash@*`，v30202 必现，与本模块无关）：</p>
 * <pre>
 * BadParcelableException: Parcelable encountered IOException writing serializable object
 *   (name = com.vector.update_app.UpdateAppBean)
 * Caused by: NotSerializableException: com.jieyisoft.weex.yktApp.activity.MainActivity
 *   at android.os.BaseBundle.dumpStats(BaseBundle.java:1959)
 *   at android.app.servertransaction.PendingTransactionActions$StopInfo.collectBundleStates
 * </pre>
 *
 * <p>Activity 切到后台（stop）时，框架会把 Fragment 的 saved state（里面有
 * {@code UpdateAppBean}，其对象图里挂着不可序列化的 {@code MainActivity}）
 * 塞进 Parcel 里「测体积」——{@code BaseBundle#dumpStats} 只是统计用途，抛异常却直接
 * 变成应用崩溃。这里让 dumpStats 空转：不再序列化，崩溃消失，其他行为不变。</p>
 */
public final class SystemHooks {

    private static boolean installed;

    private SystemHooks() {
    }

    static void install() {
        if (installed) return;
        installed = true;

        int n = XposedBridge.hookAllMethods(BaseBundle.class, "dumpStats",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 按返回类型给一个安全值：boolean 重载返回 false，其余返回 null
                        Class<?> rt = (param.method instanceof Method)
                                ? ((Method) param.method).getReturnType() : void.class;
                        if (rt == boolean.class) {
                            param.setResult(Boolean.FALSE);
                        } else if (rt == int.class) {
                            param.setResult(Integer.valueOf(0));
                        } else {
                            param.setResult(null);
                        }
                    }
                });

        if (n == 0) {
            HookEntry.log("ykt: BaseBundle#dumpStats not found (crash patch inactive)");
        } else {
            HookEntry.log("ykt: BaseBundle#dumpStats neutralized, " + n + " overload(s)");
        }
    }
}
