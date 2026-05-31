#!/bin/bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/07589cbe-21d4-43d2-9cf5-7acbe33b6dac
source ~/.bashrc 2>/dev/null

# Build and capture all output
./gradlew assembleDebug --no-daemon 2>/tmp/gradle_build_output.txt
EXIT_CODE=$?

echo "BUILD_EXIT_CODE=$EXIT_CODE"

# Show just the errors
if [ $EXIT_CODE -ne 0 ]; then
    echo "=== BUILD ERRORS ==="
    grep -E '(e: |error:|Error|FAILED|Unresolved|Cannot|not found)' /tmp/gradle_build_output.txt | head -50
    echo "=== END ERRORS ==="
fi