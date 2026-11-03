#!/usr/bin/env bash
# Push one YMusic batch to BlissTest2 and finalize MediaStore pending flags.
# Usage: push-batch.sh /tmp/opencode/ym1 [more dirs...]
set -euo pipefail
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
DEV=emulator-5554
DEST=/sdcard/Music/YMusic
adb -s $DEV shell "mkdir -p $DEST"
for d in "$@"; do
  echo "== pushing $d"
  adb -s $DEV push "$d/." "$DEST/"
done
echo "== finalizing is_pending"
adb -s $DEV shell '
for id in $(content query --uri content://media/external/audio/media --projection _id:is_pending --where "is_pending=1" 2>/dev/null | sed "s/.*_id=//;s/,.*//"); do
  content update --uri content://media/external/audio/media --bind is_pending:i:0 --where "_id=$id" >/dev/null 2>&1 || true
done
content query --uri content://media/external/audio/media --projection _id --where "is_pending=1" 2>/dev/null | wc -l
'
echo "== count on device"
adb -s $DEV shell "ls $DEST | wc -l"
