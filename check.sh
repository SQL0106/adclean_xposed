#!/bin/bash
# 看模块日志：LSPosed 把 XposedBridge.log 写在 /data/adb/lspd/log/*.log，
# 普通 logcat 里一般看不到。
set -e
TAG=ADCLEAN

run() {
  if command -v adb >/dev/null 2>&1 && [ -n "$(adb devices | sed -n '2p')" ]; then
    adb shell "$1"
  else
    rsh host-android "$1"
  fi
}

echo "== 模块是否安装"
run "pm path io.github.sql0106.adclean"

echo "== LSPosed 日志里的 $TAG（最近 40 行）"
run "grep -h $TAG /data/adb/lspd/log/*.log 2>/dev/null | tail -40"

echo "== logcat 里的 $TAG（部分设备会同时进 logcat）"
run "logcat -d -s $TAG 2>/dev/null | tail -40"

echo "== 目标进程"
for p in com.waimaiii.waimaiii com.realtech.xiaocan com.jieyisoft.weex.yktApp com.mfcloudcalculate.networkdisk; do
  echo -n "$p: "
  run "pidof $p || echo 未运行"
done
