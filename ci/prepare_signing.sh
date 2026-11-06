#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
for NAME in WEARTAK_RELEASE_KEYSTORE_B64 RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD WEARTAK_RELEASE_KEYSTORE_FILE; do
    [ -n "$(printenv "$NAME")" ] || { echo "Missing signing variable: $NAME" >&2; exit 1; }
done
[ ! -e "$WEARTAK_RELEASE_KEYSTORE_FILE" ] || {
    echo "Refusing to overwrite an existing signing file." >&2
    exit 1
}
umask 077
trap 'STATUS=$?; if [ "$STATUS" -ne 0 ]; then rm -f -- "$WEARTAK_RELEASE_KEYSTORE_FILE"; fi' EXIT
printf '%s' "$WEARTAK_RELEASE_KEYSTORE_B64" | base64 --decode > "$WEARTAK_RELEASE_KEYSTORE_FILE"
chmod 600 "$WEARTAK_RELEASE_KEYSTORE_FILE"
keytool -list -keystore "$WEARTAK_RELEASE_KEYSTORE_FILE" \
    -storepass:env RELEASE_STORE_PASSWORD -alias "$RELEASE_KEY_ALIAS" >/dev/null
echo "Production signing credentials verified."
