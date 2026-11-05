#!/usr/bin/env bash
# Build the Linux desktop distributable (tarball with AppDir layout).
# The jar stays far below the 100 MB GitHub limit; VLC/ffmpeg/JRE come
# from the host system (documented in README).
set -euo pipefail
cd "$(dirname "$0")"

VER="$(sed -n 's/^versionName=//p' app/version.properties)"

./gradlew :desktopApp:fatJar --console=plain
JAR="$(ls -t desktopApp/build/libs/blissplayer-linux*.jar | head -1)"

STAGE="/tmp/opencode/bliss-stage/BlissPlayer"
rm -rf "$STAGE"
mkdir -p "$STAGE/usr/bin"
cp "$JAR" "$STAGE/usr/bin/blissplayer-desktop.jar"
cp BlissPlayer.AppDir/bliss-player.png "$STAGE/" 2>/dev/null || true

cat > "$STAGE/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
if ! command -v java >/dev/null 2>&1; then
  echo "BlissPlayer needs a Java 17+ runtime (JRE) on PATH." >&2
  exit 1
fi
exec java -jar "$HERE/usr/bin/blissplayer-desktop.jar" "$@"
EOF
chmod +x "$STAGE/AppRun"

cat > "$STAGE/BlissPlayer.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=BlissPlayer
Comment=Music player with online metadata, loudness matching and XP/Aqua/neon themes
Exec=blissplayer-desktop
Icon=bliss-player
Categories=AudioVideo;Audio;Player;
Terminal=false
StartupWMClass=blissplayer-desktop-DesktopMain
EOF

OUT="BlissPlayer-linux-v${VER}.tar.gz"
tar -czf "$OUT.tmp" -C /tmp/opencode/bliss-stage BlissPlayer
mv -- "$OUT.tmp" "$OUT"
SIZE_MB=$(du -m "$OUT" | cut -f1)
echo ""
echo "Linux -> $OUT (${SIZE_MB} MB)"
ls -la "$OUT"
if [ "$SIZE_MB" -ge 100 ]; then
  echo "ERROR: distributable is over the 100 MB commit limit." >&2
  exit 1
fi
