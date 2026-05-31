#!/bin/bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/07589cbe-21d4-43d2-9cf5-7acbe33b6dac

# 1. Setup environment
source ~/.bashrc 2>/dev/null
export ANDROID_HOME=/root/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))

# 2. Replace AAPT2 for ARM64
AAPT2_URL="https://github.com/ReVanced/aapt2/releases/download/v1.0.0/aapt2-arm64-v8a"
AAPT2_LOCAL="$ANDROID_HOME/build-tools/35.0.0/aapt2"

if [ -f "$AAPT2_LOCAL" ]; then
    file "$AAPT2_LOCAL" 2>/dev/null | grep -q "aarch64"
    if [ $? -ne 0 ]; then
        echo "Replacing AAPT2 with ARM64 version..."
        curl -sL "$AAPT2_URL" -o "$AAPT2_LOCAL"
        chmod +x "$AAPT2_LOCAL"
    fi
fi

# 3. Clean old caches
rm -rf .gradle app/build build

# 4. Build
./gradlew assembleDebug --no-daemon --stacktrace 2>&1 | tail -200
echo "EXIT_CODE=$?"