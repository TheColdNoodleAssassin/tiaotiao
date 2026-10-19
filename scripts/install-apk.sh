#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 用 adb 安装，绕开 Android 13+ 的「应用已被限制」。
#
#   用法:  bash scripts/install-apk.sh [apk 路径]
#   默认:  dist/ 下最新的 TiaoTiao-*.apk
#
# 为什么必须走 adb：
#   侧载（文件管理器 / 微信）安装时，系统会把「显示在其他应用上层（悬浮窗）」
#   在受限权限里锁住，应用信息页顶部出现红字「应用已被限制」，开关点不动。
#   判定依据是安装来源；adb 的安装来源是 com.android.shell，属于受信任安装，
#   不会被打标。注意要「先卸载再装」，否则旧的受限标记可能被保留。
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
cd "$PROJECT_DIR"

PKG="com.pcxx.tiaotiao"

find_adb() {
  for cand in "${ADB:-}" "D:/work/android-sdk/platform-tools/adb.exe" \
              "/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
              "$(command -v adb 2>/dev/null || true)"; do
    [ -n "$cand" ] && [ -f "$cand" ] && { echo "$cand"; return 0; }
  done
  return 1
}

APK="${1:-}"
if [ -z "$APK" ]; then
  # 取 dist 下最新的包
  APK="$(ls -t dist/TiaoTiao-*.apk 2>/dev/null | head -1 || true)"
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "找不到 APK，先在项目根目录执行 bash scripts/build-apk.sh"; exit 1; }

ADB="$(find_adb)" || { echo "找不到 adb（可设 ADB=/path/to/adb 指定）"; exit 1; }

echo "ADB: $ADB"
echo "APK: $APK"
echo

"$ADB" start-server >/dev/null 2>&1 || true
COUNT="$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
if [ "$COUNT" = "0" ]; then
  echo "没有检测到已授权的设备。请检查："
  echo "  1. 手机 设置 → 系统和更新 → 开发人员选项 → USB 调试 已打开"
  echo "  2. 插线后手机上弹出「允许 USB 调试吗」并点了允许"
  "$ADB" devices
  exit 1
fi

echo "[1/2] 卸载旧版本（清掉「受限设置」标记，位置等本地设置会一起清掉）"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || echo "  （没装过，跳过）"

echo "[2/2] 安装"
"$ADB" install -r "$APK"

echo
echo "完成。打开「跳跳」，悬浮窗权限开关此时应该可以正常打开，"
echo "应用信息页也不会再出现「应用已被限制」。"
