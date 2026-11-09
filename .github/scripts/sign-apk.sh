#!/usr/bin/env bash
set -euo pipefail
umask 077

# No fallback: trusted published builds must always use the saved repository key.
for name in ANDROID_SIGNING_KEY ANDROID_KEY_ALIAS ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "::error::Missing repository secret: $name. Configure the persistent signing key before publishing."
    exit 1
  fi
done

keystore="$(mktemp "$RUNNER_TEMP/libretube-signing.XXXXXX")"
trap 'rm -f "$keystore"' EXIT
printf '%s' "$ANDROID_SIGNING_KEY" | base64 --decode > "$keystore"
build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
apk=app/build/outputs/apk/debug/app-debug.apk
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD "$apk"
"$build_tools/apksigner" verify --verbose --print-certs "$apk"
unzip -t "$apk" > /dev/null
