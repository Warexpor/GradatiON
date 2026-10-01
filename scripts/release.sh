#!/usr/bin/env bash
# Build, sign and check a release APK. See docs/RELEASING.md.
#
#   scripts/release.sh              # tests, then build
#   SKIP_TESTS=1 scripts/release.sh
#
# Signing credentials come from $GRADATION_SIGNING_DIR/signing.properties (default
# ~/.gradation-release), or from the GRADATION_* environment variables CI sets. They never touch
# the repo. The build refuses to finish unless the APK
#   - is signed with the key whose fingerprint is committed in docs/release-cert.sha256,
#   - has a versionCode above the previous release tag's,
#   - is the package the installed release app expects.
set -euo pipefail
cd "$(dirname "$0")/.."

DIR="${GRADATION_SIGNING_DIR:-$HOME/.gradation-release}"
PROPS="$DIR/signing.properties"
EXPECTED_ID="io.github.warexpor.gradation"
FP_FILE="${GRADATION_CERT_FILE:-docs/release-cert.sha256}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="$SDK/build-tools/36.0.0"
GRADLE="${GRADLE:-./gradlew}"

fail() { echo "release: $*" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS"; }

if [[ -n "${GRADATION_STORE_FILE:-}" ]]; then
  STORE_FILE="$GRADATION_STORE_FILE"; STORE_PASSWORD="${GRADATION_STORE_PASSWORD:?}"
  KEY_ALIAS="${GRADATION_KEY_ALIAS:?}"; KEY_PASSWORD="${GRADATION_KEY_PASSWORD:?}"
else
  [[ -f "$PROPS" ]] || fail "no signing key at $PROPS. Run scripts/make-release-key.sh once."
  STORE_FILE="$(prop storeFile)"; STORE_PASSWORD="$(prop storePassword)"
  KEY_ALIAS="$(prop keyAlias)"; KEY_PASSWORD="$(prop keyPassword)"
fi
[[ -f "$STORE_FILE" ]] || fail "keystore not found: $STORE_FILE"
[[ -x "$BT/apksigner" ]] || fail "apksigner not found in $BT"

[[ -z "$(git status --porcelain)" ]] || fail "working tree is not clean; commit or stash first."

if [[ -z "${SKIP_TESTS:-}" ]]; then
  "$GRADLE" testDebugUnitTest -Pfull
fi

"$GRADLE" clean assembleRelease \
  -Pgradation.storeFile="$STORE_FILE" -Pgradation.storePassword="$STORE_PASSWORD" \
  -Pgradation.keyAlias="$KEY_ALIAS" -Pgradation.keyPassword="$KEY_PASSWORD"

APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || fail "expected $APK"

"$BT/apksigner" verify --verbose "$APK" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" \
  || fail "APK signature does not verify"
FP="$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
[[ -n "$FP" ]] || fail "could not read the signing certificate"

if [[ -f "$FP_FILE" ]]; then
  [[ "$FP" == "$(tr -d ' \n' < "$FP_FILE")" ]] || fail "signed with the WRONG key ($FP). Expected $(cat "$FP_FILE"). Installed releases would refuse this update."
else
  fail "$FP_FILE is missing. Commit the fingerprint from scripts/make-release-key.sh ($FP) before the first release."
fi

BADGING="$("$BT/aapt2" dump badging "$APK")"
ID="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")"
CODE="$(sed -n "s/^package:.* versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING")"
NAME="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")"
[[ "$ID" == "$EXPECTED_ID" ]] || fail "package is $ID, expected $EXPECTED_ID"

PREV_TAG="$(git tag --list 'v*' --sort=-v:refname | grep -vx "v$NAME" | head -1 || true)"
if [[ -n "$PREV_TAG" ]]; then
  PREV_CODE="$(git show "$PREV_TAG:app/build.gradle.kts" | awk '/^val appVersion(Major|Minor|Patch)/ {v[$2]=$4} END {print v["appVersionMajor"]*10000+v["appVersionMinor"]*100+v["appVersionPatch"]}')"
  (( CODE > PREV_CODE )) || fail "versionCode $CODE is not above $PREV_TAG ($PREV_CODE). Bump the version."
fi

mkdir -p build-out
OUT="build-out/gradation-$NAME.apk"
cp "$APK" "$OUT"
( cd build-out && sha256sum "gradation-$NAME.apk" > "gradation-$NAME.apk.sha256" )

echo
echo "Release $NAME (versionCode $CODE) ok: $OUT"
echo "Signer SHA-256: $FP"
echo "Next: git tag v$NAME && git push origin v$NAME, then attach $OUT to the GitHub release."
