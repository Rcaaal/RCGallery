#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  RCGallery — Android Emulator 启动脚本
#  用法: bash run_emulator.sh [options]
#  选项:
#    -no-window    不显示窗口（headless，适合调试）
#    -wipe-data    清除用户数据（重置模拟器）
#    -read-only    只读模式（退出不保存修改）
# ═══════════════════════════════════════════════════════════════

ANDROID_SDK_ROOT="/d/AndroidSDK"
ANDROID_AVD_HOME="D:/AndroidStudio/Projects/RCGallery/Test"
AVD_NAME="test_device"
JAVA_HOME="/c/Users/16512/.jdks/ms-17.0.19"

export ANDROID_SDK_ROOT
export ANDROID_AVD_HOME
export JAVA_HOME
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$JAVA_HOME/bin:$PATH"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Starting emulator: $AVD_NAME"
echo "  AVD home: $ANDROID_AVD_HOME"
echo "  Args: $@"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exec emulator -avd "$AVD_NAME" -gpu auto -memory 2048 -noaudio "$@"