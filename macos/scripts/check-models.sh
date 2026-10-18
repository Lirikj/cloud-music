#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [[ $# != 2 ]]; then echo 'Usage: scripts/check-models.sh /absolute/isolated-library /absolute/original-demo-fixture'; exit 2; fi
if [[ -f "$1/library.json" ]]; then echo 'Use a fresh isolated library directory for the live checks.'; exit 2; fi
swift build --arch arm64 -j 2
module_dir=.build/arm64-apple-macosx/debug
if [[ -d "$module_dir/Modules" ]]; then module_dir="$module_dir/Modules"; fi
mkdir -p dist
swiftc -parse-as-library -module-name CloudMusicModelQA -target arm64-apple-macosx13.0 -I "$module_dir" scripts/ModelChecks.swift Sources/CloudMusic/LibraryModel.swift Sources/CloudMusic/PlayerModel.swift Sources/CloudMusic/ArtworkStore.swift .build/arm64-apple-macosx/debug/CloudMusicCore.build/*.swift.o -o dist/model-checks
dist/model-checks --library-directory "$1" --local-fixture "$2"
