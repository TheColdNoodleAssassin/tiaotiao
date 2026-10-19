#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 不依赖 Gradle / Android Studio 的 APK 构建脚本。
# 只使用 Android SDK 自带的 aapt2 / d8 / zipalign / apksigner。
#
#   用法:  bash scripts/build-apk.sh
#   产物:  dist/TiaoTiao-1.0.1-debug.apk
#
# 之所以能这么做：本项目不使用 AndroidX / Material 等任何外部依赖，
# 纯平台 API + 资源，因此不需要解析任何在线依赖。
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
cd "$PROJECT_DIR"

APP_NAME="TiaoTiao"
VERSION_NAME="1.0.1"
VERSION_CODE="2"
MIN_SDK="26"
TARGET_SDK="36"
PKG="com.pcxx.tiaotiao"

BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"

# ---------------------------------------------------------------- 定位工具链
find_sdk() {
  for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
              "D:/work/android-sdk" "C:/Users/Administrator/AppData/Local/Android/Sdk" \
              "/c/Users/Administrator/AppData/Local/Android/Sdk" "D:/Android/Sdk"; do
    [ -n "$cand" ] || continue
    if [ -d "$cand/platforms/android-$TARGET_SDK" ] && [ -d "$cand/build-tools" ]; then
      echo "$cand"; return 0
    fi
  done
  return 1
}

# 一个目录是不是可用 JDK：有 javac 文件，并且真的能跑出版本号
is_jdk() {
  local dir="$1" javac_bin=""
  [ -n "$dir" ] || return 1
  if [ -f "$dir/bin/javac.exe" ]; then
    javac_bin="$dir/bin/javac.exe"
  elif [ -f "$dir/bin/javac" ]; then
    javac_bin="$dir/bin/javac"
  else
    return 1
  fi
  "$javac_bin" -version >/dev/null 2>&1
}

find_jdk() {
  for cand in "${JAVA_HOME:-}" "/c/Program Files/Android/Android Studio/jbr" \
              "/c/Program Files/Java/jdk-17"; do
    if is_jdk "$cand"; then echo "$cand"; return 0; fi
  done
  # 兜底：常见 IDE 内置的 JBR 与任意 JDK 安装目录（不写死具体产品）
  for cand in /c/Program\ Files/Android/*/jbr /c/Program\ Files/Java/jdk-* /d/*/jbr; do
    if is_jdk "$cand"; then echo "$cand"; return 0; fi
  done
  if command -v javac >/dev/null 2>&1; then
    dirname "$(dirname "$(command -v javac)")"; return 0
  fi
  return 1
}

find_python() {
  for cand in "${PYTHON:-}" python python3 \
              "C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"; do
    [ -n "$cand" ] || continue
    if [ -x "$cand" ] || command -v "$cand" >/dev/null 2>&1; then echo "$cand"; return 0; fi
  done
  return 1
}

SDK="$(find_sdk)" || { echo "找不到 Android SDK（需要 platforms/android-$TARGET_SDK）。"; exit 1; }
JDK="$(find_jdk)" || { echo "找不到 JDK 17+。"; exit 1; }
PY="$(find_python)" || { echo "找不到 python（用于补 package 属性与封装 classes.dex）。"; exit 1; }

# 选一个可用的 build-tools（优先 36.x）
BT_DIR="$(ls -d "$SDK"/build-tools/36.* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT_DIR" ] || BT_DIR="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT_DIR" ] || { echo "找不到 build-tools。"; exit 1; }

ANDROID_JAR="$SDK/platforms/android-$TARGET_SDK/android.jar"
AAPT2="$BT_DIR/aapt2.exe";     [ -x "$AAPT2" ] || AAPT2="$BT_DIR/aapt2"
ZIPALIGN="$BT_DIR/zipalign.exe"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$BT_DIR/zipalign"
APKSIGNER="$BT_DIR/apksigner.bat"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT_DIR/apksigner"
D8="$BT_DIR/d8.bat";           [ -f "$D8" ] || D8="$BT_DIR/d8"
JAVAC="$JDK/bin/javac.exe";    [ -x "$JAVAC" ] || JAVAC="$JDK/bin/javac"
KEYTOOL="$JDK/bin/keytool.exe";[ -x "$KEYTOOL" ] || KEYTOOL="$JDK/bin/keytool"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$BT_DIR:$PATH"

echo "SDK        : $SDK"
echo "build-tools: $BT_DIR"
echo "JDK        : $JDK"
echo

# ---------------------------------------------------------------- 清理
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

# ---------------------------------------------------------------- 1. 编译资源
echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir app/src/main/res -o "$BUILD_DIR/res.zip"

# ---------------------------------------------------------------- 2. 处理清单
# AGP 8 起不允许在 manifest 里写 package 属性（由 namespace 接管），
# 但 aapt2 手工链接需要它，所以在这里生成一份补好 package 的副本。
echo "[2/6] 准备 AndroidManifest"
"$PY" - "$PROJECT_DIR/app/src/main/AndroidManifest.xml" "$BUILD_DIR/AndroidManifest.xml" "$PKG" <<'PY'
import io, sys
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(src, encoding="utf-8").read()
if 'package=' not in s.split('<application')[0]:
    s = s.replace('<manifest ', '<manifest package="%s" ' % pkg, 1)
io.open(dst, "w", encoding="utf-8").write(s)
PY

# ---------------------------------------------------------------- 3. 链接资源
echo "[3/6] aapt2 link"
"$AAPT2" link \
  -o "$BUILD_DIR/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD_DIR/AndroidManifest.xml" \
  -R "$BUILD_DIR/res.zip" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

# ---------------------------------------------------------------- 4. 编译 Java
echo "[4/6] javac"
find app/src/main/java "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
"$JAVAC" --release 17 \
  -cp "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  -encoding UTF-8 \
  @"$BUILD_DIR/sources.txt"

# ---------------------------------------------------------------- 5. dex
echo "[5/6] d8"
find "$BUILD_DIR/classes" -name "*.class" > "$BUILD_DIR/classes.txt"
"$D8" --lib "$ANDROID_JAR" --release --min-api "$MIN_SDK" \
  --output "$BUILD_DIR/dex" @"$BUILD_DIR/classes.txt"

"$PY" - "$BUILD_DIR/base.apk" "$BUILD_DIR/dex/classes.dex" <<'PY'
import shutil, sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

# ---------------------------------------------------------------- 6. 对齐 + 签名
echo "[6/6] zipalign + apksigner"
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk"

KS="$BUILD_DIR/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

OUT="$DIST_DIR/${APP_NAME}-${VERSION_NAME}-debug.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT" "$BUILD_DIR/aligned.apk"

echo
echo "APK 已生成: $OUT"
ls -lh "$OUT"
"$APKSIGNER" verify --print-certs "$OUT" | head -6
