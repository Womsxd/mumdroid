#!/usr/bin/env bash
# Regenerates the APKs under app/src/test/resources/apk-signatures/.
#
# Those files are NOT committed (see .gitignore): the keys below are generated
# fresh on every run, so the certificates — and the digests the tests derive from
# them — are per-machine artifacts. Run this once after cloning to enable the
# signature tests; without the fixtures they skip themselves with an INFO notice.
#
# The fixtures exist to pin down one specific attack: "appended APK signature".
# Android only validates the newest signing scheme present, and its v3
# strip-protection marker defends against *removing* v3 but not against
# *adding* it. So an attacker can keep the original v2 block, modify the APK,
# and append a v3 block signed with their own key: the system verifies the APK
# and reports the attacker's certificate.
#
# Requirements: a JDK, plus Android SDK build-tools (`apksigner`, `aapt2`) and
# a platform android.jar. Set ANDROID_HOME, or put build-tools on PATH.
#
#   ./tools/make-signature-fixtures.sh
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/../app/src/test/resources/apk-signatures"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
PLATFORM_JAR="$(ls -d "$ANDROID_HOME"/platforms/*/android.jar | sort -V | tail -1)"
PATH="$BUILD_TOOLS:$PATH"

cd "$work"
mkdir -p "$out"

# --- a minimal APK we can sign over and over -------------------------------
cat > AndroidManifest.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.demo.app" android:versionCode="1" android:versionName="1.0">
  <application android:label="Demo"/>
</manifest>
XML
aapt2 link -o base-unsigned.apk -I "$PLATFORM_JAR" --manifest AndroidManifest.xml

# --- two distinct keys: the developer we trust, and an attacker ------------
mk_key() { # name alias
  keytool -genkeypair -keystore "$1.jks" -storepass 123456 -keypass 123456 \
    -alias "$2" -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=$2"
}
mk_key dev "Original Dev"
mk_key atk "Attacker"

sign() { # keystore out v2 v3
  apksigner sign --ks "$1.jks" --ks-pass pass:123456 --key-pass pass:123456 \
    --v1-signing-enabled false --v2-signing-enabled "$3" --v3-signing-enabled "$4" \
    --out "$2" base-unsigned.apk
}

sign dev honest-v2-only.apk true false
sign dev honest-v3-only.apk false true
sign dev honest-v2v3.apk true true
sign atk attacker-v3-only.apk false true

# --- the forgery -----------------------------------------------------------
# Keep the developer's v2 pairs, append the attacker's v3 pairs, then fix up
# the End-Of-Central-Directory offset. The result passes `apksigner verify`.
python3 "$here/build-appended-v3.py" \
  honest-v2-only.apk attacker-v3-only.apk forged-appended-v3.apk

# --- two signers inside ONE scheme -----------------------------------------
# Both certificates are trusted, so only the "a scheme must not carry two
# different certificates" rule can reject this.
python3 "$here/build-same-scheme-two-signers.py" \
  honest-v2-only.apk attacker-v3-only.apk same-scheme-two-signers.apk

# --- copy to the test resources --------------------------------------------
cp honest-v2-only.apk        "$out/honest-v2-only.apk"
cp honest-v3-only.apk        "$out/honest-v3-only.apk"
cp honest-v2v3.apk           "$out/honest-v2-and-v3.apk"
cp attacker-v3-only.apk      "$out/attacker-v3-only.apk"
cp forged-appended-v3.apk    "$out/forged-appended-v3.apk"
cp same-scheme-two-signers.apk "$out/same-scheme-two-signers.apk"

echo "fixtures written to $out"
