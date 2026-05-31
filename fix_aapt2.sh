#!/bin/bash
# Fix AAPT2 for ARM64 in Gradle cache
ANDROID_HOME=/root/android-sdk
ARM64_AAPT2="https://github.com/ReVanced/aapt2/releases/download/v1.0.0/aapt2-arm64-v8a"

# 1. Replace in SDK build-tools
SDK_AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
echo "Replacing SDK aapt2..."
curl -sL "$ARM64_AAPT2" -o "$SDK_AAPT2"
chmod +x "$SDK_AAPT2"
file "$SDK_AAPT2"

# 2. Find and replace in gradle cache JARs
echo "Finding aapt2 in gradle cache..."
find /root/.gradle/caches -path '*/aapt2*' -name '*.jar' 2>/dev/null | while read jar; do
    echo "Processing jar: $jar"
    # Check if it contains aapt2 binary
    TMPDIR=$(mktemp -d)
    cd "$TMPDIR"
    unzip -q "$jar" 2>/dev/null
    if [ -f "com/android/tools/build/aapt2" ]; then
        echo "  Found aapt2 in jar, replacing..."
        curl -sL "$ARM64_AAPT2" -o "com/android/tools/build/aapt2"
        chmod +x "com/android/tools/build/aapt2"
        zip -q -u "$jar" "com/android/tools/build/aapt2"
    fi
    cd /
    rm -rf "$TMPDIR"
done

# 3. Replace any standalone aapt2 files in gradle cache
find /root/.gradle/caches -name 'aapt2*' -type f ! -name '*.jar' ! -name '*.sha1' ! -name '*.md5' ! -name '*.xml' ! -name '*.pom' ! -name '*.module' 2>/dev/null | while read f; do
    echo "Checking: $f"
    file "$f" 2>/dev/null | grep -q 'ELF' && {
        file "$f" 2>/dev/null | grep -q 'aarch64' || {
            echo "  Replacing with ARM64..."
            curl -sL "$ARM64_AAPT2" -o "$f"
            chmod +x "$f"
        }
    }
done

# 4. Delete the problematic transform cache
echo "Cleaning transform cache..."
rm -rf /root/.gradle/caches/9.1.0/transforms/e8cbbfd6ce6d03a211bc4f93569f6d4b 2>/dev/null
rm -rf /root/.gradle/caches/9.1.0/transforms/4457ce4fce68df375f5f01c74521d4a5 2>/dev/null

# Also clean all transforms containing aapt2
find /root/.gradle/caches -path '*/transforms/*' -name 'aapt2' -type f 2>/dev/null | while read f; do
    echo "Replacing transform aapt2: $f"
    curl -sL "$ARM64_AAPT2" -o "$f"
    chmod +x "$f"
done

echo "DONE fixing AAPT2"