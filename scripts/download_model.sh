#!/bin/bash
# Downloads the MobileFaceNet face model into the app's assets folder and checks that it is the exact file
# this app was tested with. Run it from the project folder:   bash scripts/download_model.sh
set -e

URL="https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/android/app/src/main/assets/MobileFaceNet.tflite"
SHA256="d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b"
DEST="android/app/src/main/assets/MobileFaceNet.tflite"

mkdir -p "$(dirname "$DEST")"
echo "Downloading model (about 5 MB)..."
curl -L --fail -o "$DEST" "$URL"

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$DEST" | cut -d' ' -f1)
else
  ACTUAL=$(shasum -a 256 "$DEST" | cut -d' ' -f1)
fi

if [ "$ACTUAL" != "$SHA256" ]; then
  echo "WARNING: the downloaded file is different from the one this app was tested with."
  echo "  expected: $SHA256"
  echo "  actual  : $ACTUAL"
  echo "The app may not work correctly with it. Delete $DEST and ask for help."
  exit 1
fi
echo "OK: $DEST is the tested model."
