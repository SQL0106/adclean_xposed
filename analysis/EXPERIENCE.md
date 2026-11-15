# 开发经验（adclean）

踩过的坑与可复用的做法，按「工具链 / API 102 / 调试」分类。

---

## 一、工具链：arm64 上必须全原生

### 硬规则

- 不用 qemu / binfmt / box64 / wine，不做跨架构。
- Google 的 build-tools（`aapt`、`zipalign`、`apksigner` 二进制）只有 x86_64 ELF，
  在 arm64 上**跑不了也不该跑**。
- 纯 Java 工具（`d8.jar`、`r8.jar`、`apksigner` 脚本）由本机 JVM 执行，与架构无关，
  可以用最新版。

### 可用的原生来源

| 工具 | 来源 | 说明 |
|---|---|---|
| `aapt` / `aapt2` | Debian/Ubuntu 包 `aapt`（`1:14~beta1`，arm64） | 独立包，不用装整个 build-tools；Ubuntu noble 也有 |
| `zipalign` | Ubuntu/Debian 包 `zipalign`（`1:10.0.0+r36`，arm64） | 本地编译的原生二进制 |
| `apksigner` | 包 `apksigner`（`31.0.2`，arch all） | 纯 Java，靠 JDK 跑 |
| `d8` / `r8` | Maven `com.android.tools:r8:9.5.32` | **只在 Google Maven**：`dl.google.com/dl/android/maven2/...`；Maven Central 是 404 |

### android.jar 的坑（重要）

`platform-36_r02.zip` 里的 `android.jar` **任何 Debian aapt 都解析不了**：

```
ERROR: No resource identifier found for attribute 'minSdkVersion' in package 'android'
ERROR: Entry offset at index 1234 points outside the Type's boundaries
```

新版 platform 的 `resources.arsc` 用了新的条目编码，老 aapt（v1，Android 14 源码为止）
读不了。所以**原生 arm64 链路只能用 `android-34` 的 jar**。
android.jar 本身是架构无关的，从官方 `platform-34-ext7_r02.zip` 取即可。

`build.sh` 里因此做了 `jar_ok()`：用 aapt 真去链接一次，链接不了就跳过下一个候选，
而不是假设「版本最大就最好」。

另一个坑：Debian/Ubuntu 的 `aapt -M` **只认文件名恰好是 `AndroidManifest.xml`**，
冒烟测试用 `smoke.xml` 会报 `No AndroidManifest.xml file found`。

### 手工链（不用 Gradle）

```
javac --release 17  →  d8 --min-api 24  →  aapt package
  →  zip 塞 classes.dex + META-INF/xposed  →  zipalign  →  apksigner
```

CI 跑在 `ubuntu-24.04-arm` 上，全程 arm64 原生；
`actions/cache` 缓存 android.jar 的 zip 和 r8.jar（约 84MB），日志用 `tee` 落盘并
`if: always()` 上传，同时在 `$GITHUB_STEP_SUMMARY` 里留一份。

---

## 二、libxposed API 102（LSPosed 新 API）

- 入口必须 `extends io.github.libxposed.api.XposedModule`；
  `targetApiVersion >= 102` 时**禁止**使用 `de.robv.android.xposed.*`。
- 模块元数据**原样打进 APK 根**（不是 res）：

  ```
  META-INF/xposed/module.prop      minApiVersion / targetApiVersion / staticScope / exceptionMode
  META-INF/xposed/java_init.list   一行：入口类全名
  META-INF/xposed/scope.list       每行一个包名
  ```

  `staticScope=true` + `scope.list` 才能「装上即带作用域」，否则用户要在
  LSPosed 里手动勾。
- 模块名与描述来自 AndroidManifest 的 `android:label` / `android:description`
  指向 `strings.xml`，Manifest 里**不要**放 `xposed*` meta-data。
- 回调时序：`onModuleLoaded`（此后才能 hook）→ `onPackageLoaded`（有 default
  ClassLoader）→ `onPackageReady`（Application 已建）。
- `hook(Executable).setExceptionMode(PROTECTIVE).intercept(chain -> …)`；
  `return 值` 即跳过原方法。抽象方法不能 hook。

---

## 三、加固应用：怎么在正确时机装钩子

业务 dex 是运行时解密的，`onPackageLoaded` 里 `Class.forName` 一定失败。

有效做法：

1. 挂 `ClassLoader#loadClass`（after），按**精确类名 allowlist** 命中即安装；
2. 再挂 `Application#attach(Context)`，用 `context.getClassLoader()` 补装一遍；
3. 去重：`IdentityHashMap<Class>` 记已装、`ThreadLocal` 防重入、
   `Set<Method>` 防重复 hook。

不要 `hookAllMethods` 全量扫 SDK —— 加固应用类加载量巨大（几十万），会拖慢启动。
`AdSdkKiller` 只在**广告 SDK 包前缀**内按方法名（`show` / `render` / `isReady`）筛选。

---

## 四、调试

- `XposedBridge.log` **不进 logcat**。LSPosed 把日志写在
  `/data/adb/lspd/log/verbose_<开机时间>.log`，行内带 `[模块名]` 前缀。
- 本模块统一 TAG `ADCLEAN`，`logcat -s ADCLEAN` 或
  `grep ADCLEAN /data/adb/lspd/log/*.log`。
- 更新模块：`pm install -r` + `am force-stop <目标包>` 即可，不用重启手机。
- `/data/adb/lspd/config/modules_config.db` 是 LSPosed 的配置库，
  **不要拿 sql 直接改并重启** —— 作用域这种事交给 LSPosed 管理器 UI。
- 带壳应用不要在真机反复拉前台/截图来做「验证」，测试交给用户，用日志定位。

---

## 五、两条硬经验

1. **找广告入口先看 View，再看类。** 带壳应用的 `ClassLoader#loadClass`、
   `DexFile#defineClass` 可能都被绕过（易盾就是这样），类加载观察器一条也不命中，
   但 `ViewGroup#addView` 一定能看到广告容器上树。开屏是 Overlay View
   （`com.anythink.flutter.splash.ATSplashOverlayView`）而不是 Activity 时尤其如此。
2. **框架级统计方法可以安全空转。** `BaseBundle#dumpStats` 只用于统计 Bundle 体积，
   但它会真的去序列化内容；应用把不可序列化对象塞进 Fragment saved state 时，
   Android 17 在 `onStop` 就会崩。空转它既修崩溃又无副作用。
