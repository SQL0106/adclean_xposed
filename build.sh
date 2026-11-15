#!/bin/bash
# 手工打包 adclean（不用 gradle）
#
# 【硬规则】必须全部原生，不准转译/模拟（qemu、binfmt、wine、box64 一律不用）。
# 官方 build-tools 的 aapt/zipalign/apksigner 是 x86_64 ELF，本机是 arm64，
# 所以在 arm64 上一律用系统自带的原生 aapt/zipalign/apksigner；
# 纯 Java 工具（d8.jar / r8.jar）由 arm64 JVM 原生执行，不算转译，取最新版。
#
#   - android.jar : $SDK/platforms/android-*/android.jar，取版本号最大且 aapt 真能链接的
#   - d8          : $D8_JAR → $SDK/build-tools/*/lib/d8.jar → cmdline-tools/*/lib/r8.jar → .sdk/r8*.jar
#                   （都是 jar，靠本机 JVM 跑，与执行架构无关）
#   - aapt/zipalign/apksigner : 系统原生版本（arm64），可用 AAPT/ZIPALIGN/APKSIGNER 覆盖
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk/sdk}}"

# 仓库内自带工具链（例如从 Debian aapt 包里解出的原生 aapt）优先于 PATH
[ -d "$PWD/toolchain/bin" ] && export PATH="$PWD/toolchain/bin:$PATH"

AAPT="${AAPT:-$(command -v aapt || true)}"
ZIPALIGN="${ZIPALIGN:-$(command -v zipalign || true)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
[ -n "$AAPT" ] || { echo "aapt 未找到（原生 arm64 版）"; exit 1; }
[ -n "$ZIPALIGN" ] || { echo "zipalign 未找到（原生 arm64 版）"; exit 1; }
[ -n "$APKSIGNER" ] || { echo "apksigner 未找到（原生 arm64 版）"; exit 1; }

# 防呆：不允许把 x86_64 ELF 当成工具塞进来
check_native() {
  local bin="$1" arch
  arch="$(readelf -h "$bin" 2>/dev/null | sed -n 's/.*Machine: *//p' | head -1)"
  case "$(uname -m)" in
    aarch64|arm64)
      case "$arch" in
        *AArch64*) ;;
        "") ;; # 脚本包装器（如 apksigner 是 shell 脚本），跳过检查
        *) echo "拒绝使用非原生工具: $bin ($arch)"; exit 1 ;;
      esac
      ;;
    x86_64)
      case "$arch" in
        *X86-64*|"") ;;
        *) echo "拒绝使用非原生工具: $bin ($arch)"; exit 1 ;;
      esac
      ;;
  esac
}
check_native "$AAPT"
check_native "$ZIPALIGN"
check_native "$APKSIGNER"

rm -rf build
mkdir -p build/classes build/dex build/pkg build/jarcheck

VER="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)"
[ -n "$VER" ] || { echo "读不到 versionName"; exit 1; }
OUT="adclean-${VER}-universal.apk"

# 冒烟清单：验证 android.jar 能被 aapt 真正解析
# （Debian 的 aapt 只认文件名恰为 AndroidManifest.xml 的清单）
cat > build/jarcheck/AndroidManifest.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="smoke.test">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36" />
</manifest>
XML

jar_ok() {
  local jar="$1" size
  [ -f "$jar" ] || return 1
  size="$(stat -c%s "$jar" 2>/dev/null || echo 0)"
  [ "$size" -ge 20000000 ] || return 1
  "$AAPT" package -f -M build/jarcheck/AndroidManifest.xml -I "$jar" -F build/jarcheck/smoke.apk >/dev/null 2>&1
}

JAR=""
for cand in "$ANDROID_JAR" $(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V -r) .sdk/platforms/*/android.jar; do
  [ -n "$cand" ] || continue
  if jar_ok "$cand"; then JAR="$cand"; break; fi
  echo "跳过 aapt 链接不了的 android.jar: $cand ($(stat -c%s "$cand" 2>/dev/null || echo 0) bytes)" >&2
done
[ -n "$JAR" ] || { echo "没有可用的 android.jar"; exit 1; }

D8_JAR=""
for cand in "${D8_JAR:-}" "$SDK"/build-tools/*/lib/d8.jar $(ls "$SDK"/cmdline-tools/*/lib/r8.jar 2>/dev/null | sort -V -r) .sdk/build-tools/*/lib/d8.jar .sdk/r8*.jar; do
  [ -n "$cand" ] && [ -f "$cand" ] && { D8_JAR="$cand"; break; }
done
[ -n "$D8_JAR" ] || { echo "d8.jar / r8.jar 未找到"; exit 1; }
D8_VER="$(java -cp "$D8_JAR" com.android.tools.r8.D8 --version 2>&1 | head -1)"

echo "arch=$(uname -m)"
echo "android.jar=$JAR ($(stat -c%s "$JAR") bytes)"
echo "aapt=$AAPT ($("$AAPT" version 2>&1 | head -1))"
echo "zipalign=$ZIPALIGN"
echo "apksigner=$APKSIGNER ($("$APKSIGNER" --version 2>&1 | head -1))"
echo "d8=$D8_VER ($D8_JAR)"

# 1. 模块源码（libxposed API 102 仅编译期依赖，运行期由框架提供）
API_JAR="libs/libxposed-api-102.jar"
[ -f "$API_JAR" ] || { echo "缺少 $API_JAR"; exit 1; }
javac --release 17 -Xlint:-options -nowarn -d build/classes -cp "$API_JAR:$JAR" \
    $(find src -name '*.java')

# 2. dex
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 24 --release \
    --lib "$JAR" --lib "$API_JAR" \
    --output build/dex $(find build/classes -name '*.class')

# 3. 清单/资源
"$AAPT" package -f -M AndroidManifest.xml -S res -I "$JAR" -F build/base.apk

# 4. 合入 dex + 现代 Xposed 元数据（入口/作用域/配置）
cp build/dex/classes.dex build/pkg/
cp -r module/META-INF build/pkg/
( cd build/pkg && zip -q -X -r ../base.apk classes.dex META-INF )

# 5. 对齐 + 签名
"$ZIPALIGN" -f -p 4 build/base.apk build/aligned.apk
KS="$PWD/adclean.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias adclean -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass adclean -keypass adclean \
    -dname "CN=adclean, O=sql0106, C=CN" >/dev/null 2>&1
  echo "keystore created: $KS"
fi

"$APKSIGNER" sign --ks "$KS" --ks-pass pass:adclean --key-pass pass:adclean \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" build/aligned.apk
"$APKSIGNER" verify --print-certs "$OUT" | head -3

cp -f "$OUT" adclean.apk
ls -l "$OUT"
echo BUILD_OK
