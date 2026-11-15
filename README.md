# 应用去广告（adclean）

LSPosed 模块，API **102**（libxposed），一个模块覆盖四个应用：

| 应用 | 包名 | 适配版本 |
|---|---|---|
| 歪麦霸王餐 | `com.waimaiii.waimaiii` | 1.1.220 (vc303) |
| 小蚕惠生活 | `com.realtech.xiaocan` | 3.22.4 (vc3040) |
| 洪城一卡通 | `com.jieyisoft.weex.yktApp` | 3.2.2 (vc30202) |
| 123云盘 | `com.mfcloudcalculate.networkdisk` | 3.3.6 (vc3248) |

作用域（`META-INF/xposed/scope.list`）已内置四个包名，配合 `staticScope=true`，
安装后只要在 LSPosed 里启用模块、勾选这四个应用（新版 LSPosed 会自动带上）即可，
不需要手动填作用域。

## 去的是什么广告

三类手段，全部在 Java 层，不联网、不改宿主、不做全局 hook：

1. **通用广告 SDK 展示层**（`AdSdkKiller`）
   运行时按包前缀识别 AnyThink/TopOn、穿山甲(Gromore/Pangle)、优量汇(GDT)、快手、
   百度百青藤、Sigmob、ToBid、Mintegral、Unity Ads、华为广告等 SDK 的类，
   只拦「广告上屏」的那一步：
   - 开屏：照常 `show`（回调、倒计时都正常走），随后把广告容器隐藏 ——
     用户看到的是应用自己的启动背景，不会卡启动页；
   - 插屏 / 激励视频 / 信息流原生：跳过 `show` / `render`，广告永不上屏；
   - `isReady` / `isValid` 一律返回 `false`，应用走「没有广告」的正常分支。

2. **广告 Activity 兜底**（`ActivityKiller`）
   SDK 自带的开屏页、激励视频页、落地页（含 CQAdSDK 的 Web Activity）在
   `onCreate` 后直接 `finish()`，用户不会被带到广告落地页。

3. **应用专属规则**
   - 小蚕惠生活（`XiaocanHooks`）：移植社区公开模块
     [xiguadoudou/XiaoCanNoAds](https://github.com/xiguadoudou/XiaoCanNoAds)（MIT）
     已验证的规则集 —— 开屏 `BaseAdActivity.loadAd` 走「正常退出」分支、
     Sigmob/AM/ToBid 插屏、首页推广弹窗与活动条、首页下拉二楼、商城引导弹窗、
     原生广告容器。因为小蚕带壳，规则按「类加载时精确类名命中」安装。
   - 洪城一卡通（`YktHooks`）：它的广告全部走自研封装 `com.cqyh.cqadsdk`，
     于是直接让 `CQAdSDKAdLoadStrategy` 的 `fetchSplashAd` / `fetchExpressAd` /
     `fetchInterstitialAd` / `fetchRewardVideoAd` / `fetchNativeAd` 不请求广告、
     回调 `onAdLoadFailed`。开屏失败回调就是应用自己的 `gotoMain()`，所以秒进主页。

## 已知限制

- **Flutter 自绘内容拦不住**。歪麦、小蚕、123云盘都是 Flutter 应用，SDK 广告之外还有
  一部分是 Dart 直接画在界面里的运营位；Java 层 hook 无法移除，只能去掉这些位置
  背后的广告 SDK 物料（大多数情况下会变成空白或直接不显示）。
- 三个 Flutter 应用都带加固壳（网易易盾 / appsec / 自研），业务 dex 运行时才解密，
  所以静态分析看不到业务代码；模块的策略是「SDK 层拦截 + 运行时类名命中」，
  不做业务代码 patch。
- 应用更新后类名/方法签名可能变化，模块会静默跳过（`ADCLEAN` 日志里会记
  `missing hook`），不会导致应用崩溃（`exceptionMode=protective`）。
- 只处理广告，不动会员、付费、签到奖励等业务逻辑。

## 安装

1. 从 [Releases](../../releases) 或 Actions 产物下载 `adclean-<版本>-universal.apk`；
2. `adb install -r` 安装（或直接在手机上点开）；
3. LSPosed 里启用「应用去广告」，作用域勾选上面四个应用；
4. 强制停止这四个应用再打开（`am force-stop <包名>`）。

日志 TAG：`ADCLEAN`（`logcat -s ADCLEAN` 或 LSPosed 日志里过滤）。

## 深入阅读

- [analysis/NOTES.md](analysis/NOTES.md)：每个应用查到了什么、为什么这样 hook；
- [analysis/EXPERIENCE.md](analysis/EXPERIENCE.md)：arm64 原生工具链、API 102 规范、
  加固应用的安装时机、调试方法。

## 构建

不依赖 Gradle，手工链：`javac` → `d8` → `aapt` → 塞 `META-INF/xposed` →
`zipalign` → `apksigner`。

```bash
ANDROID_SDK_ROOT=/path/to/sdk ./build.sh
```

`build.sh` 会自动挑可用的组件：`platforms/android-*` 里版本号最大且能被 aapt
真正链接的 `android.jar`（android-36 的 jar 用老版 aapt 解析不了，会自动跳过）、
`build-tools/*/lib/d8.jar` 或 `cmdline-tools/*/lib/r8.jar`、以及本机的
`aapt`/`zipalign`/`apksigner`。

**全部原生，不做任何转译**：aapt / zipalign / apksigner 一律用系统自带的原生版本
（arm64 机器就是 arm64 ELF；Google 官方 build-tools 里那些是 x86_64，本机跑不了，
也不用 qemu/box64 去凑）。`d8.jar` / `r8.jar` / `apksigner` 是纯 Java 工具，
由本机 JVM 原生执行，与架构无关。

**CI**：`.github/workflows/build.yml` 跑在 `ubuntu-24.04-arm`（arm64 runner）上，
`apt-get install aapt zipalign apksigner`（Ubuntu 官方 arm64 原生包），
配 `platform-34-ext7_r02.zip` 的 android.jar 和 Maven 上的 `r8-9.5.32.jar`（d8），
JDK 21 (temurin)。push / PR 出 artifact，`v*` tag 自动发 Release。

工具链版本：aapt **1:14~beta1**（Ubuntu/Debian 原生 arm64）、zipalign **1:10.0.0+r36**、
apksigner **31.0.2**、android.jar **android-34**、d8 **9.5.32**、
JDK **21 (temurin)**、libxposed api **102.0.0**、
actions/checkout@v7、actions/setup-java@v6、actions/cache@v6、
actions/upload-artifact@v7、softprops/action-gh-release@v3。

## 文件结构

```
AndroidManifest.xml           模块 APK 清单（label / description 供 LSPosed 显示）
res/values/strings.xml        模块名与描述
module/META-INF/xposed/       module.prop / java_init.list / scope.list
src/io/github/sql0106/adclean/
  HookEntry.java              入口（extends XposedModule，API 102）
  ClassLoadWatch.java         运行时类加载观察（应对加固壳）
  AdSdkKiller.java            通用广告 SDK 展示层拦截
  ActivityKiller.java         广告 Activity 兜底
  XiaocanHooks.java           小蚕专属规则
  YktHooks.java               洪城一卡通专属规则
  compat/                     legacy Xposed API 门面（复用于 zybclean）
libs/libxposed-api-102.jar    编译期依赖
build.sh                      本地构建（javac + d8 + aapt + apksigner，全原生）
deploy.sh                     装到手机并强制停止目标应用
check.sh                      看 ADCLEAN 日志与目标进程
analysis/NOTES.md             四个应用的分析结论与每条 hook 的来由
analysis/EXPERIENCE.md        工具链 / API 102 / 加固应用 / 调试经验
.github/workflows/build.yml   arm64 runner + 原生 deb + 缓存 + 日志
```

## 声明

- 本模块由 LLM 生成，仅供学习与个人使用，请自行评估风险。
- 不收集、不上传任何数据；模块本身不申请网络权限，也不读写应用数据。
- 广告是应用开发者的收入来源，如果你喜欢某个应用，请支持正版 / 会员。
- 与所列应用的开发者无任何关联。License: MIT。
