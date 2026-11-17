#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
vot_root="$PWD"
vot_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
vot_input="${1:-build/youtube-original.apk}"
vot_tag="$(date +%Y%m%d-%H%M%S)"
vot_candidate="$vot_root/build/apk-candidate/YouTube-VOT-20.14.43.apk"
vot_output="$vot_root/build/YouTube-VOT-20.14.43.apk"
vot_log="$vot_root/build/patch-$vot_tag.log"
test -f "$vot_input"
test -f build/vot-patches.rvp
mkdir -p build/apk-candidate signing
chmod 700 signing
if [ ! -x build/revanced-aapt2 ]; then
  python3 - <<'PY'
from pathlib import Path
import zipfile
p = Path('build/revanced-aapt2')
with zipfile.ZipFile('build/revanced-cli.jar') as archive:
    p.write_bytes(archive.read('prebuilt/macosx/aapt2_64'))
p.chmod(0o755)
PY
fi
rm -f "$vot_candidate"
java -Xmx3g -jar build/revanced-cli.jar patch "$vot_input" \
  -p build/vot-patches.rvp -b --exclusive \
  -e 'Voice-over translation' -e 'GmsCore support' -e 'Spoof video streams' \
  -e 'Custom branding' -O 'App name=YouTube VOT' \
  --keystore "$vot_root/signing/youtube-vot.keystore" \
  --custom-aapt2-binary "$vot_root/build/revanced-aapt2" \
  -o "$vot_candidate" -t "$vot_root/build/patch-$vot_tag" 2>&1 | tee "$vot_log"
test -s "$vot_candidate"
if rg -q 'SEVERE:|failed:' "$vot_log"; then
  echo 'Patching reported an error; retaining the previous APK.' >&2
  exit 1
fi
"$vot_sdk/build-tools/36.0.0/apksigner" verify --verbose "$vot_candidate"
"$vot_sdk/build-tools/36.0.0/aapt2" dump badging "$vot_candidate" > build/final-badging.txt
rg -q "application-label:'YouTube VOT'" build/final-badging.txt
rg -q "name='app.revanced.android.youtube'" build/final-badging.txt
python3 - "$vot_candidate" <<'PY'
from pathlib import Path
import sys, zipfile
with zipfile.ZipFile(Path(sys.argv[1])) as archive:
    assert 'assets/vot/LICENSE' in archive.namelist()
    assert 'assets/vot/THIRD_PARTY_NOTICES.txt' in archive.namelist()
PY
chmod 600 signing/youtube-vot.keystore
mv "$vot_candidate" "$vot_output"
shasum -a 256 "$vot_output"
