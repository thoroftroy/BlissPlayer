#!/usr/bin/env bash
# Build BlissPlayer debug APK in one command.
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="/tmp/opencode/gradle-8.7/bin:$PATH"
cd "$(dirname "$0")"
if [ ! -f gradlew ]; then
  gradle wrapper --gradle-version 8.7 >/dev/null
fi
./gradlew :app:assembleDebug --console=plain
version="$(sed -n 's/^versionName=//p' app/version.properties)"
apk="BlissPlayer-v${version}-debug.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$apk.tmp"
mv -- "$apk.tmp" "$apk"
# Keep the last working APK until its replacement has actually built and
# copied successfully. Only prune versioned debug APKs in this directory;
# never touch an APK the user keeps elsewhere or restore deleted old builds.
for old in BlissPlayer-v*-debug.apk; do
  if [[ -f "$old" && "$old" != "$apk" ]]; then
    rm -- "$old"
  fi
done
echo ""
echo "APK -> $apk"
ls -la "$apk"
