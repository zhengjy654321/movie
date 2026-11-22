#!/bin/bash
set -eu
release=2.11.37
archive="$RUNNER_TEMP/cmfa.apk"
curl --fail --location --retry 3 --silent --show-error "https://github.com/MetaCubeX/ClashMetaForAndroid/releases/download/v$release/cmfa-$release-meta-universal-release.apk" -o "$archive"
echo "2c76a5c88a150294f1dfcb79b2b54ce3c79c75a705b055087571c47a6907f76a  $archive" | sha256sum -c -
python3 - "$archive" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys
with ZipFile(sys.argv[1]) as archive:
 for name in archive.namelist():
  if name.startswith('lib/') and name.endswith(('/libbridge.so','/libclash.so')):
   dest=Path('app/src/main/jniLibs')/name[4:]
   dest.parent.mkdir(parents=True,exist_ok=True)
   dest.write_bytes(archive.read(name))
assert len(list(Path('app/src/main/jniLibs').glob('*/libbridge.so')))==4
PY
curl -fsSL --retry 2 https://raw.githubusercontent.com/MetaCubeX/ClashMetaForAndroid/v2.11.37/NOTICE -o app/src/main/assets/NOTICE
curl -fsSL --retry 2 https://raw.githubusercontent.com/MetaCubeX/ClashMetaForAndroid/v2.11.37/LICENSE -o app/src/main/assets/CORE-LICENSE
