#!/usr/bin/env bash
# Roda os testes instrumentados no emulador e guarda log e screenshots em build/ci.
set -u
mkdir -p build/ci
adb logcat -c || true
./gradlew --no-daemon --stacktrace connectedDebugAndroidTest -PtargetAbi=x86_64
status=$?
adb logcat -d -v time > build/ci/logcat.txt || true
echo "===== Log dos testes ====="
grep -E "BaixaVideosTest|Engine|Downloads|AndroidRuntime|FATAL" build/ci/logcat.txt | tail -n 300 || true
adb pull /sdcard/Download/BaixaVideosCI build/ci/screenshots || true
exit $status
