#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  RCGallery — 编译 + 安装到模拟器
#  用法: bash install_to_emulator.sh
#  注意: 先启动 run_emulator.sh，模拟器跑起来后再执行此脚本
# ═══════════════════════════════════════════════════════════════

ANDROID_SDK_ROOT="/d/AndroidSDK"
AVD_HOME="D:/AndroidStudio/Projects/RCGallery/Test"
JAVA_HOME="/c/Users/16512/.jdks/ms-17.0.19"
PROJECT_DIR="D:/AndroidStudio/Projects/RCGallery"

export ANDROID_AVD_HOME="$AVD_HOME"
export ANDROID_SDK_ROOT
export JAVA_HOME
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$JAVA_HOME/bin:$PATH"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Step 1: Building APK..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
cd "$PROJECT_DIR" || exit 1
./gradlew assembleDebug 2>&1 | tail -5

APK=$(ls -t app/build/outputs/apk/debug/RCGallery_*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  echo "❌ APK not found!"
  exit 1
fi
echo "  APK: $APK"
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Step 2: Installing to emulator..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
adb install -r "$APK" 2>&1

if [ $? -eq 0 ]; then
  echo "✅ Install SUCCESS"
else
  echo "⚠️  Install failed — make sure emulator is running"
  echo "   Start it first: bash run_emulator.sh"
fi