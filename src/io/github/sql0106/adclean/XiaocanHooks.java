package io.github.sql0106.adclean;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import io.github.sql0106.adclean.compat.XC_MethodHook;
import io.github.sql0106.adclean.compat.XposedBridge;
import io.github.sql0106.adclean.compat.XposedHelpers;

/**
 * 小蚕惠生活（com.realtech.xiaocan）专属规则。
 *
 * <p>规则来自社区已在小蚕 3.21.2 上验证过的公开模块
 * （xiguadoudou/XiaoCanNoAds，MIT）：只 hook 精确类名，不动 SDK 全局、不动系统。
 * 这里按 libxposed API 102 重新实现，并保留它「按类名 allowlist + 类加载时安装」
 * 的时机策略 —— 小蚕带壳，业务类运行时才加载。</p>
 *
 * <p>覆盖：开屏、插屏（Sigmob / AM / ToBid）、首页弹窗与浮动活动条、
 * 首页下拉二楼、商城引导弹窗、原生广告容器。</p>
 */
public final class XiaocanHooks {

    private static final String PKG = "com.realtech.xiaocan";
    private static final String BASE = PKG + ".BaseAdActivity";
    private static final String SERVICE = PKG + ".service.SigMobAdServiceImpl";
    private static final String SIGMOB = PKG + ".sigmob.SigMobUtils";
    private static final String AM = "com.realtech.am_sdk_plugin.AmInterstitialAdHandler";
    private static final String WIND = "com.windmill.windmill_ad_plugin.interstitial.InterstitialAd";
    private static final String HOME = "com.realtech.promotion.pages.home.HomeFragment";
    private static final String MALL = PKG + ".fragment.SecondTabPlacementFragment";
    private static final String HOME_VM = "com.realtech.promotion.pages.home.viewmodel.HomeViewModel";
    private static final String SECOND_FLOOR = "com.realtech.promotion.pages.home.tab.ext.HomeSecondFloorKt";
    private static final String HOME_EFFECT = HOME + "$createObserver$30";
    private static final String ACTIVITY_BANNER = "com.realtech.promotion.pages.home.ext.HomeActivityBannerExtKt";
    private static final String HOME_FAB = "com.realtech.promotion.pages.home.ext.HomeFabExtKt";
    private static final String COMPETITOR = "com.realtech.promotion.service.CompetitorChallengeServiceImpl";
    private static final String BQT = PKG + ".ad.BqtAdServiceImpl";
    private static final String HX = PKG + ".ad.HxAdServiceImpl";
    private static final String TOBID_NATIVE = PKG + ".service.TobidNativeAdServiceImpl";

    private static final Set<String> TARGETS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(BASE, SERVICE, SIGMOB, AM, WIND, HOME, MALL, HOME_VM,
                    SECOND_FLOOR, HOME_EFFECT, ACTIVITY_BANNER, HOME_FAB, COMPETITOR, BQT, HX,
                    TOBID_NATIVE)));

    private static final Set<Class<?>> INSTALLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>()));
    private static final ThreadLocal<Boolean> INSTALLING = new ThreadLocal<Boolean>();
    private static final Set<Method> HOOKED =
            Collections.synchronizedSet(new HashSet<Method>());

    private XiaocanHooks() {
    }

    static void install(ClassLoader cl) {
        XposedBridge.hookMethod(findMethod(Application.class, "attach", Context.class), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args == null || param.args.length == 0) return;
                Context ctx = (Context) param.args[0];
                if (ctx != null) installAvailable(ctx.getClassLoader());
            }
        });
        installAvailable(cl);
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            throw new RuntimeException("NoSuchMethod " + c.getName() + "#" + name, t);
        }
    }

    static void inspect(Class<?> c) {
        if (!TARGETS.contains(c.getName())) return;
        install(c);
    }

    private static void installAvailable(ClassLoader loader) {
        if (loader == null) return;
        for (String name : TARGETS) {
            try {
                install(Class.forName(name, false, loader));
            } catch (Throwable ignored) {
                // 壳里的类可能稍后才可用，交给 ClassLoadWatch 继续盯
            }
        }
    }

    private static synchronized void install(Class<?> c) {
        if (INSTALLED.contains(c) || Boolean.TRUE.equals(INSTALLING.get())) return;
        INSTALLING.set(Boolean.TRUE);
        try {
            String n = c.getName();
            if (BASE.equals(n)) {
                hook(c, "loadAd", 0, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.callMethod(param.thisObject, "requestExit", "adclean:no-ad");
                            param.setResult(null);
                            HookEntry.log("xiaocan: blocked splash, requested normal exit");
                        } catch (Throwable t) {
                            HookEntry.log("xiaocan: splash hook failed; original retained", t);
                        }
                    }
                });
            } else if (SERVICE.equals(n) || SIGMOB.equals(n)) {
                hook(c, SERVICE.equals(n) ? "loadInterstitialAd" : "load", 2, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        final Object callback = param.args[1];
                        param.setResult(null);
                        onMain(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    safeCall(callback, "onError", "Interstitial disabled by AdClean");
                                }
                            }
                        });
                        HookEntry.log("xiaocan: blocked native interstitial");
                    }
                });
                if (SIGMOB.equals(n)) {
                    hook(c, "preload", 2, skip("interstitial preload"));
                    hook(c, "showInterstitialHost", 0, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Object ad = param.thisObject;
                            param.setResult(null);
                            onMain(new Runnable() {
                                @Override
                                public void run() {
                                    safeCall(ad, "notifyError", "Interstitial disabled by AdClean");
                                }
                            });
                        }
                    });
                }
            } else if (AM.equals(n)) {
                hook(c, "onMethodCall", 2, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        String method = (String) XposedHelpers.getObjectField(param.args[0], "method");
                        if (!"load".equals(method) && !"show".equals(method)) return;
                        Object result = param.args[1];
                        safeCall(result, "error", "AD_BLOCKED", "Interstitial disabled", null);
                        final Object owner = param.thisObject;
                        param.setResult(null);
                        onMain(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    emit(XposedHelpers.getObjectField(owner, "channel"), "onAdFailed");
                                } catch (Throwable t) {
                                    HookEntry.log("xiaocan: AM event failed", t);
                                }
                            }
                        });
                        HookEntry.log("xiaocan: blocked AM interstitial " + method);
                    }
                });
            } else if (WIND.equals(n)) {
                for (final String method : new String[]{"load", "showAd"}) {
                    hook(c, method, 1, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            final Object owner = param.thisObject;
                            param.setResult(null);
                            onMain(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        emit(XposedHelpers.getObjectField(owner, "adChannel"),
                                                "load".equals(method) ? "onAdFailedToLoad" : "onAdShowError");
                                    } catch (Throwable t) {
                                        HookEntry.log("xiaocan: ToBid event failed", t);
                                    }
                                }
                            });
                            HookEntry.log("xiaocan: blocked ToBid interstitial " + method);
                        }
                    });
                }
                hook(c, "isReady", 1, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.FALSE);
                    }
                });
            } else if (HOME.equals(n)) {
                hook(c, "showSharerHomePopup", 1, skip("home promotional popup"));
            } else if (HOME_EFFECT.equals(n)) {
                hook(c, "emit", 2, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] == null) return;
                        String effect = param.args[0].getClass().getName();
                        String prefix = "com.realtech.promotion.pages.home.HomeEffect$";
                        if (!effect.equals(prefix + "ShowActiveVipDialog")
                                && !effect.equals(prefix + "ShowFullRefundWheelDialog")
                                && !effect.equals(prefix + "ShowFreeLotteryDialog")
                                && !effect.equals(prefix + "ShowMAChallengeDialog")
                                && !effect.equals(prefix + "ShowFullRefundSecRedPackExpiringDialog")) return;
                        try {
                            Object unit = XposedHelpers.getStaticObjectField(
                                    XposedHelpers.findClass("kotlin.Unit",
                                            param.thisObject.getClass().getClassLoader()), "INSTANCE");
                            if (effect.equals(prefix + "ShowFullRefundWheelDialog")
                                    || effect.equals(prefix + "ShowFreeLotteryDialog")) {
                                Object fragment = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                XposedHelpers.callMethod(fragment, "completePostLoginLotteryDecision", Boolean.FALSE);
                            }
                            param.setResult(unit);
                            HookEntry.log("xiaocan: blocked effect " + effect);
                        } catch (Throwable t) {
                            HookEntry.log("xiaocan: effect hook failed; original retained", t);
                        }
                    }
                });
            } else if (ACTIVITY_BANNER.equals(n)) {
                hook(c, "renderHomeActivityBanner", 2, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.args[1] = Collections.emptyList();
                        HookEntry.log("xiaocan: cleared floating activity banner");
                    }
                });
            } else if (HOME_FAB.equals(n)) {
                hook(c, "initNewUserFabView$lambda$6", 2, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        try {
                            Object binding = XposedHelpers.callMethod(param.args[0], "getBinding");
                            Object fab = XposedHelpers.getObjectField(binding, "layoutFab");
                            ((View) XposedHelpers.getObjectField(fab, "rainFab")).setVisibility(View.GONE);
                        } catch (Throwable t) {
                            HookEntry.log("xiaocan: rain widget failed", t);
                        }
                    }
                });
            } else if (COMPETITOR.equals(n)) {
                hook(c, "shouldCheckHomeActivityPop", 0, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.FALSE);
                    }
                });
            } else if (BQT.equals(n) || HX.equals(n) || TOBID_NATIVE.equals(n)) {
                String load = TOBID_NATIVE.equals(n) ? "loadAndShowIntoContainer" : "loadAndShow";
                int count = BQT.equals(n) ? 4 : HX.equals(n) ? 3 : 6;
                hook(c, load, count, nativeAdFailure(count - 1, TOBID_NATIVE.equals(n)));
                if (HX.equals(n)) {
                    hook(c, "preload", 3, nativeAdFailure(2, false));
                    hook(c, "show", 2, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.FALSE);
                        }
                    });
                }
            } else if (HOME_VM.equals(n)) {
                hook(c, "canUseHomeSecondFloor", 0, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.FALSE);
                    }
                });
            } else if (SECOND_FLOOR.equals(n)) {
                hook(c, "checkSceondDFloorGuide", 1, skip("second-floor guide"));
            } else if (MALL.equals(n)) {
                hook(c, "enqueueFirstGuideDialogIfNeeded", 0, skip("mall first-guide popup"));
                hook(c, "enqueueBonusDialogIfNeeded", 1, skip("mall bonus popup"));
            }
            INSTALLED.add(c);
        } catch (Throwable t) {
            HookEntry.log("xiaocan: install failed " + c.getName(), t);
        } finally {
            INSTALLING.remove();
        }
    }

    private static void hook(Class<?> c, String name, int argc, XC_MethodHook callback) {
        boolean found = false;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != argc) continue;
            found = true;
            if (HOOKED.contains(m)) continue;
            XposedBridge.hookMethod(m, callback);
            HOOKED.add(m);
            HookEntry.log("xiaocan: hooked " + c.getName() + "." + name);
        }
        if (!found) HookEntry.log("xiaocan: missing hook " + c.getName() + "." + name);
    }

    private static XC_MethodHook skip(final String what) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
                HookEntry.log("xiaocan: blocked " + what);
            }
        };
    }

    private static XC_MethodHook nativeAdFailure(final int listenerIndex, final boolean tobid) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                final Object listener = param.args[listenerIndex];
                param.setResult(null);
                onMain(new Runnable() {
                    @Override
                    public void run() {
                        if (listener == null) return;
                        if (tobid) safeCall(listener, "onResult", Boolean.FALSE);
                        else safeCall(listener, "onAdLoadedFailure");
                    }
                });
                HookEntry.log("xiaocan: blocked native ad container");
            }
        };
    }

    private static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static void emit(Object channel, String event) {
        if (channel == null) return;
        Map<String, Object> error = new HashMap<String, Object>();
        error.put("code", Integer.valueOf(-1));
        error.put("message", "Interstitial disabled by AdClean");
        safeCall(channel, "invokeMethod", event, error);
    }

    private static void safeCall(Object object, String method, Object... args) {
        try {
            XposedHelpers.callMethod(object, method, args);
        } catch (Throwable t) {
            HookEntry.log("xiaocan: callback " + method + " failed", t);
        }
    }
}
