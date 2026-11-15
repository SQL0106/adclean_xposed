# 分析笔记（adclean）

四个目标应用的静态/运行时分析结论，以及每条 hook 的来由。
静态分析用 dexsql（`classes` / `methods` / `method_calls`），
运行时证据来自真机 `/data/data/<包名>` 与 `pm path`。

---

## 1. 歪麦霸王餐 `com.waimaiii.waimaiii`

| 项 | 值 |
|---|---|
| 版本 | 1.1.220 / vc303 |
| SDK | minSdk 24 / targetSdk 33 |
| 加固 | 网易易盾（`com.netease.nis.wrapper.*`） |
| 框架 | Flutter（`libflutter.so` 11.5MB、`libapp.so` 18MB） |
| dex | `classes.dex` 80MB，但只有 **30 个类 / 657 个方法**，全是壳 |

**结论**：业务类（含 Manifest 里的 `MainActivity`）运行时才解密，静态看不到调用点。
带广告 SDK 的 native 库与资产：

- 穿山甲：`libpanglearmor.so`、`libpangleflipped.so`、`libPglbizssdk_ml.so`
- 讯飞：`libiflyads.so`
- 火山：`libVolcBaseLog.so`
- 聚合/其他：`libcom_baihemob_ad_base.so`、`libInno.so`
- assets：`bdxadsdk.jar`（百度）、`gdt_plugin/gdtadv2.jar`（优量汇）、
  `ksad_data.3gp` / `ksad_idc.json`（快手）、`jad_click.json` / `jad_shake.json` /
  `jad_slideup.json`（京东）、`anythink/pl/46/6.6.52-7.8.1.4-0.jar`（TopOn）

运行时还会从服务端下插件 jar（真机实测）：

- `app_qq_com_plugin_*/gdt_plugin.jar`
- `app_baidu_sdk_remote/__xadsdk__remote__final__running__.jar`
- `ksad_dex/dexJar.jar`
- `app_qm/_qumeng_local.jar`

**hook 决策**：不做业务层规则，只用通用层（`AdSdkKiller` + `ActivityKiller`）
在 SDK 上屏那一步拦。Flutter 自绘的运营位 Java 层无解。

---

## 2. 小蚕惠生活 `com.realtech.xiaocan`

| 项 | 值 |
|---|---|
| 版本 | 3.22.4 / vc3040 |
| SDK | minSdk 24 / targetSdk 34 |
| 加固 | `com.Install.DexInstall*` + `com.Proxy.*` + `com.appsec.*` |
| 框架 | Flutter |
| dex | `classes.dex` 115MB，只有 **41 个类 / 390 个方法**（纯壳） |

业务类（`MainActivity`、`WelComeActivity`、`HomeFragment` 等）都在壳外，运行时解密。

**规则来源**：社区公开模块
[xiguadoudou/XiaoCanNoAds](https://github.com/xiguadoudou/XiaoCanNoAds)（MIT），
已在 3.21.2 上验证；本项目按 API 102 重写，保留其**精确类名 allowlist**与
「类加载时安装」的时机。

小蚕的广告入口（全部在应用自身类，不是 SDK 类）：

| 入口 | 处理 |
|---|---|
| `BaseAdActivity#loadAd()` | 调 `requestExit("adclean:no-ad")` 走正常退出分支 |
| `service.SigMobAdServiceImpl#loadInterstitialAd(2)` | 不请求，回调 `onError` |
| `sigmob.SigMobUtils#{load(2),preload(2),showInterstitialHost()}` | 同上 |
| `com.realtech.am_sdk_plugin.AmInterstitialAdHandler#onMethodCall(2)` | 拦 `load`/`show`，回 `error` + `onAdFailed` |
| `com.windmill...interstitial.InterstitialAd#{load,showAd}` | 拦，`isReady` 返回 `false`（ToBid） |
| `HomeFragment#showSharerHomePopup(1)` | 不弹分享弹窗 |
| `HomeFragment$createObserver$30#emit(2)` | 按 `HomeEffect$` 子类拦 VIP/抽奖/免单/挑战弹窗 |
| `HomeActivityBannerExtKt#renderHomeActivityBanner(2)` | 活动条数据换成空 list |
| `HomeFabExtKt#initNewUserFabView$lambda$6(2)` | 隐藏 `rainFab` 浮层 |
| `CompetitorChallengeServiceImpl#shouldCheckHomeActivityPop()` | 返回 `false` |
| `ad.BqtAdServiceImpl#loadAndShow(4)` | 原生广告容器不加载 |
| `ad.HxAdServiceImpl#{loadAndShow(3),preload(3),show(2)}` | 同上，`show` 返回 `false` |
| `service.TobidNativeAdServiceImpl#loadAndShowIntoContainer(6)` | 同上 |
| `HomeViewModel#canUseHomeSecondFloor()` | 返回 `false`（关下拉二楼） |
| `HomeSecondFloorKt#checkSceondDFloorGuide(1)` | 不弹二楼引导 |
| `fragment.SecondTabPlacementFragment#{enqueueFirstGuideDialogIfNeeded(),enqueueBonusDialogIfNeeded(1)}` | 商城弹窗不排队 |

小蚕 assets 里还有 `assets/AdxSdk`、`assets/assets/template_*.json`（穿山甲样式模板）。
业务 dex 会往 `app_libs/cloudmobi/adbid_adx_2.0.44.dex`、`.cache/fake_classes2.dex` 落盘，
但都在内存里加载，静态拿不到。

---

## 3. 洪城一卡通 `com.jieyisoft.weex.yktApp`

| 项 | 值 |
|---|---|
| 版本 | 3.2.2 / vc30202 |
| SDK | minSdk 21 / targetSdk 30 |
| 加固 | **无** |
| dex | 6 个，48,709 类 / 369,956 方法（`com` 36,211、`org` 3,591、`androidx` 3,164、`kotlin` 2,870） |

**唯一可以完整静态分析的应用。** 关键发现：应用代码**不直接引用** anythink/pangle，
全部走自研封装 `com.cqyh.cqadsdk`（CQAdSDK，appId `300045`）：

- 开屏：`LaunchActivity.initView()` → `CQAdSDKConfig.Builder` →
  `CQAdSDKManager.getInstance().initConfig(ctx, cfg)` → `new CQAdSDKAdLoadStrategy()
  .fetchSplashAd(this, slot, 5000, LaunchActivity$1)`，placementId `1000014788`
- 信息流/原生：`CardRechargeAllActivity`、`InvoicesAllActivity`、
  `QrcodeRechargeActivity`、`ResultActivity`、`MobileTrafficCardActivity`、
  `QrcodeFragment` 调 `fetchExpressAd(...)`
- `CQAdSDKAdLoadStrategy` 还声明了 `fetchInterstitialAd(3)`、`fetchRewardVideoAd(3)`、
  `fetchNativeAd(4)`

**关键证据**：`LaunchActivity$1.onAdLoadFailed(AdError)` 末尾调用
`access$300(this$0)` → `LaunchActivity.gotoMain()`，即**广告失败就直接进主页**。
所以 `YktHooks` 让所有 `fetch*` 直接回调 `onAdLoadFailed(new AdError(1, "..."))`，
开屏秒过，信息流位置自然空掉。

`com.cqyh.cqadsdk.express.CQExpressAd` 的 `render()` / `show(ViewGroup)` 另做双保险。

ykt 的 dex 里也打包了 anythink / pangle / kwad / 华为（`com.huawei.openalliance.ad`、
`com.huawei.hms.ads`）/ 百度 mobads，但调用方都在混淆包里（`bcaa*` / `ms` / `pl`），
应用逻辑用不到，通用层顺手兜住。

---

## 4. 123云盘 `com.mfcloudcalculate.networkdisk`

| 项 | 值 |
|---|---|
| 版本 | 3.3.6 / vc3248 |
| SDK | minSdk 24 / targetSdk 34 |
| 加固 | 有壳（`Ls/h/e/l/l/{S,A,N,C};`，4 个类 / 37 个方法） |
| 框架 | Flutter |

广告 SDK：

- AnyThink/TopOn：`assets/anythink/pl/46/6.6.21-7.6.1.1-0.jar`
- 穿山甲：`libpanglearmor.so`、`libpangleflipped.so`、`libPglbizssdk_ml.so`、`libtt_ugen_layout`
- 其他：`assets/AdxSdk`、`assets/bdxadsdk.jar`、`assets/bio_res.json`、
  `assets/bid-log-key-public*.key`、`libsgcore`、`libhxsdk`

运行时落盘：百度 `xadsdk`、`gdt_plugin.jar`、`cache/adgain/*.dex`、`app_flutter/web_res_123`。

**hook 决策**：同歪麦，只用通用层。

---

## 通用层设计

### 为什么需要「类加载观察」

三个应用带壳，业务类运行时解密，`onPackageLoaded` 里 `Class.forName` 看不到。
`ClassLoadWatch` 挂 `ClassLoader#loadClass(String)` 与 `(String, boolean)` 的
after 钩子：一次 `loadClass` 只做「类名前缀比较」，命中才开始反射，按类名去重，
不会重复 hook，也不改类加载结果。

### `AdSdkKiller`：只拦「上屏」那一步

| 情况 | 做法 | 原因 |
|---|---|---|
| 开屏（类名带 `splash`） | 照常 `show`，再 `setVisibility(GONE)` 隐藏容器 | 回调、倒计时、生命周期都照走，不会卡启动页 |
| 插屏 / 激励 / 原生 | 跳过 `show` / `showAd` / `render` | 广告永不上屏，应用继续跑 |
| `isReady` / `isValid` 等 | 返回 `false` | 应用走「没有广告」的正常分支 |

识别前缀：`com.anythink.`、`com.bytedance.msdk.`、`com.bytedance.sdk.openadsdk.`、
`com.qq.e.`、`com.kwad.`、`com.baidu.mobads.`、`com.sigmob.`、`com.windmill.`、
`com.mbridge.`、`com.unity3d.ads.`、`com.cqyh.cqadsdk.`、`com.huawei.openalliance.ad.`、
`com.huawei.hms.ads.`、`com.mintegral.`、`com.inmobi.`、`com.applovin.`、`com.tapjoy.`、
`com.vungle.`、`com.chartboost.`、`com.adcolony.`、`com.facebook.ads.`

只匹配广告 SDK 的包前缀，业务类一律不碰；抽象方法不 hook
（`Cannot hook abstract methods`），失败只记日志。

### `ActivityKiller`：广告页面兜底

`Activity#onCreate` 之后按 SDK 包前缀 `finish()`：开屏页、激励视频页、
落地页（含 `com.cqyh.cqadsdk.activity.*` 的 Web Activity）。

---

## 验证方式

```bash
adb logcat -s ADCLEAN              # 或 LSPosed 日志里 grep ADCLEAN
```

正常应看到：

```
hooked com.anythink.splashad.api.ATSplashAd#show/2
hide splash com.anythink.splashad.api.ATSplashAd#show/2
finish ad activity com.qq.e.ads.PortraitADActivity
```

如果某个广告还在，多半是：

1. 类名变了 —— 日志里对应条目消失或出现 `hook failed` / `missing hook`；
2. 广告是 Flutter/Dart 自己画的 —— Java 层没有调用点，日志里不会有 `hooked`；
3. 走的是没列进前缀的小众 SDK —— 按日志里的广告 Activity 类名补前缀。

---

## 5. 两个「非 hook 能解决」的坑

### 5.1 洪城一卡通 onStop 崩溃（不是本模块引入）

真机 `/data/system/dropbox/data_app_crash@1794711772812.txt`：

```
BadParcelableException: Parcelable encountered IOException writing serializable object
  (name = com.vector.update_app.UpdateAppBean)
  at android.os.Parcel.writeSerializable
  at android.os.BaseBundle.writeToParcelInner
  at androidx.fragment.app.FragmentState.writeToParcel
  at android.os.BaseBundle.dumpStats
  at android.app.servertransaction.PendingTransactionActions$StopInfo.collectBundleStates
Caused by: NotSerializableException: com.jieyisoft.weex.yktApp.activity.MainActivity
```

Activity **stop** 时框架要把 Fragment 的 saved state 序列化来统计体积
（`StopInfo.collectBundleStates` → `BaseBundle.dumpStats`），而 `UpdateAppBean`
是 Serializable、字段里挂着不可序列化的 `MainActivity` → 抛异常 → 崩溃。

`dumpStats` 纯统计用途，没有业务语义，所以 `SystemHooks` 把它的三个重载全部
`setResult(0/false/null)` 空转，崩溃消失（真机验证：`am start` → 回桌面，
`dropbox` 不再新增 `data_app_crash`）。

### 5.2 歪麦 / 123云盘 的类加载 hook 完全失效

探针（`ViewGroup#addView`）证明广告入口是：
`com.anythink.flutter.splash.ATSplashOverlayView`（AnyThink 的 **Flutter 插件 Overlay**）
→ 内含 `com.byazt.pi.TTCountdownViewForCircle`（穿山甲混淆包）+ ImageView/TextView。
它**不是独立 Activity**，`ActivityKiller` 拦不到。

同时 `ClassLoadWatch` 的三个入口都打了 `entry alive`，但一条 `saw sdk class` 都没有
—— 加固壳（易盾）用非常规方式定义内存 dex，`loadClass`/`DexFile#defineClass`
都绕过了。结论：带壳应用**不能只靠类加载观察**，得在 View 树上拦。

因此新增 `SplashViewKiller`：hook `ViewGroup#addView` 的 5 个重载，child 类名命中
广告 SDK 前缀且含 `splash`/`openscreen` 就 `setVisibility(GONE)`（0/300/1000/2500ms
重设，防 SDK 又改回来）。纯字符串判断，不反射、不打日志刷屏（每类名只记一次）。
