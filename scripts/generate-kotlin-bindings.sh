#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

cargo build -p larpcode-ffi --features cli

case "$(uname -s)" in
    Darwin)
        library="$root/target/debug/liblarpcode_ffi.dylib"
        ;;
    *)
        library="$root/target/debug/liblarpcode_ffi.so"
        ;;
esac

rm -rf "$root/android/app/src/main/java/dev/wingky530/larpcode/ffi"
"$root/target/debug/uniffi-bindgen" generate \
    --library "$library" \
    --language kotlin \
    --out-dir "$root/android/app/src/main/java" \
    --no-format
