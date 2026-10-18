#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
config="${CONFIGURATION:-release}"
mkdir -p dist
if [[ "${BUNDLE_ONLY:-0}" != "1" ]]; then
  swift build -c "$config" --arch arm64 -j 2
  if [[ "${UNIVERSAL:-1}" == "1" ]]; then swift build -c "$config" --arch x86_64 -j 2; fi
fi
app_path="$project_dir/dist/Cloud Music.app"
mkdir -p "$app_path/Contents/MacOS" "$app_path/Contents/Resources"
if [[ "${UNIVERSAL:-1}" == "1" ]]; then
  lipo -create ".build/arm64-apple-macosx/$config/CloudMusic" ".build/x86_64-apple-macosx/$config/CloudMusic" -output "$app_path/Contents/MacOS/CloudMusic"
else
  cp ".build/arm64-apple-macosx/$config/CloudMusic" "$app_path/Contents/MacOS/CloudMusic"
fi
chmod 755 "$app_path/Contents/MacOS/CloudMusic"
cat > "$app_path/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>CloudMusic</string>
<key>CFBundleIdentifier</key><string>app.cloudmusic.macos</string>
<key>CFBundleName</key><string>Cloud Music</string>
<key>CFBundleDisplayName</key><string>Cloud Music</string>
<key>CFBundleShortVersionString</key><string>1.1.0</string>
<key>CFBundleVersion</key><string>2</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleIconFile</key><string>AppIcon</string>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>LSApplicationCategoryType</key><string>public.app-category.music</string>
<key>NSHighResolutionCapable</key><true/>
<key>CFBundleDocumentTypes</key><array><dict><key>CFBundleTypeName</key><string>Audio</string><key>CFBundleTypeRole</key><string>Viewer</string><key>LSHandlerRank</key><string>Alternate</string><key>LSItemContentTypes</key><array><string>public.audio</string></array></dict></array>
</dict></plist>
PLIST
if [[ ! -f Resources/AppIcon.icns ]]; then
  mkdir -p dist/AppIcon.iconset
  swift scripts/make-icon.swift dist/icon-1024.png
  for size in 16 32 128 256 512; do
    sips -z "$size" "$size" dist/icon-1024.png --out "dist/AppIcon.iconset/icon_${size}x${size}.png" >/dev/null
    twice=$((size*2))
    sips -z "$twice" "$twice" dist/icon-1024.png --out "dist/AppIcon.iconset/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns dist/AppIcon.iconset -o Resources/AppIcon.icns
fi
cp Resources/AppIcon.icns "$app_path/Contents/Resources/"
rm -rf "$app_path/Contents/Resources/Demo"
python3 scripts/make-demo.py "$app_path/Contents/Resources/Demo"
if [[ -n "${DEVELOPER_ID_IDENTITY:-}" ]]; then
  codesign --force --options runtime --timestamp --sign "$DEVELOPER_ID_IDENTITY" "$app_path"
else
  codesign --force --options runtime --timestamp=none --sign - "$app_path"
fi
codesign --verify --deep --strict --verbose=2 "$app_path"
echo "$app_path"
