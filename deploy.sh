#!/bin/bash
# 把编译好的模块装到手机并重启目标应用（不重启手机、不碰 LSPosed 配置库）
set -e
cd "$(dirname "$0")"

PKG=io.github.sql0106.adclean
TARGETS="com.waimaiii.waimaiii com.realtech.xiaocan com.jieyisoft.weex.yktApp com.mfcloudcalculate.networkdisk"

APK="$(ls -1 adclean-*-universal.apk 2>/dev/null | sort -V | tail -1)"
[ -n "$APK" ] || { echo "先跑 ./build.sh 产出 APK"; exit 1; }
echo "安装 $APK"

if command -v adb >/dev/null 2>&1 && [ -n "$(adb devices | sed -n '2p')" ]; then
  adb install -r "$APK" >/dev/null
  for p in $TARGETS; do adb shell am force-stop "$p" || true; done
elif command -v rsh >/dev/null 2>&1; then
  # 容器/其它机器上：走宿主 sshd，装到 /data/local/tmp 再 pm install
  cat "$APK" | rsh host-android 'cat > /data/local/tmp/adclean.apk'
  rsh host-android "pm install -r /data/local/tmp/adclean.apk && rm -f /data/local/tmp/adclean.apk"
  for p in $TARGETS; do rsh host-android "am force-stop $p" || true; done
else
  echo "既没有 adb 也没有 rsh，手动安装：$APK"
  exit 1
fi

echo "装好了。到 LSPosed 里启用「应用去广告」并勾选作用域，然后 ./check.sh 看日志。"
