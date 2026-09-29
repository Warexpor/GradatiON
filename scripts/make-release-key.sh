#!/usr/bin/env bash
# One-time: create the GradatiON release signing key. Run it on your own machine, once, then back
# the folder up (password manager plus an offline copy). Every future update must be signed with
# this same key, or Android refuses to install it over the old one. Lose it and users have to
# uninstall (and lose their chats) to update.
#
#   scripts/make-release-key.sh            # keys go to ~/.gradation-release
#   GRADATION_SIGNING_DIR=/path scripts/make-release-key.sh
set -euo pipefail

DIR="${GRADATION_SIGNING_DIR:-$HOME/.gradation-release}"
JKS="$DIR/gradation-release.jks"
PROPS="$DIR/signing.properties"
ALIAS="gradation-release"

if [[ -e "$JKS" ]]; then
  echo "A key already exists at $JKS. Refusing to overwrite it." >&2
  exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"

keytool -genkeypair -keystore "$JKS" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=GradatiON, O=Warexpor"

umask 077
cat > "$PROPS" <<EOF
storeFile=$JKS
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF
chmod 600 "$JKS" "$PROPS"

FINGERPRINT="$(keytool -list -v -keystore "$JKS" -storepass "$PASSWORD" -alias "$ALIAS" \
  | sed -n 's/^ *SHA256: *//p' | tr -d ':' | tr 'A-F' 'a-f')"

echo
echo "Key:         $JKS"
echo "Credentials: $PROPS"
echo "SHA-256:     $FINGERPRINT"
echo
echo "Next:"
echo "  1. Back up $DIR (both files) somewhere safe. There is no recovery."
echo "  2. Commit the public fingerprint so later releases can be checked against it:"
echo "       echo $FINGERPRINT > docs/release-cert.sha256"
echo "  3. scripts/release.sh"
