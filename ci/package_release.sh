#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
[ "${CI_COMMIT_BRANCH:-}" = "${CI_DEFAULT_BRANCH:-}" ] && [ "${CI_COMMIT_REF_PROTECTED:-}" = true ] || {
    echo "Release requires the protected default branch." >&2
    exit 1
}
case "${CI_COMMIT_SHA:-}" in ''|*[!0-9a-f]*) echo "Invalid source SHA." >&2; exit 1 ;; esac
[ "${#CI_COMMIT_SHA}" -eq 40 ] || { echo "Invalid source SHA length." >&2; exit 1; }
for NAME in CI_PIPELINE_IID CI_PIPELINE_ID CI_JOB_ID; do
    case "$(printenv "$NAME")" in ''|*[!0-9]*) echo "Invalid release provenance: $NAME" >&2; exit 1 ;; esac
done
SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk-linux}}
TOOLS=$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
[ -x "$TOOLS/apksigner" ] && [ -x "$TOOLS/aapt" ] || {
    echo "Android signing/package inspection tools missing." >&2
    exit 1
}
APK="$CI_PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "Signed release APK missing." >&2; exit 1; }
CERTIFICATES=$("$TOOLS/apksigner" verify --print-certs "$APK")
SIGNERS=$(printf '%s\n' "$CERTIFICATES" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')
EXPECTED=1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d
[ "$SIGNERS" = "$EXPECTED" ] || { echo "Unexpected release signing certificate(s)." >&2; exit 1; }
BADGING=$("$TOOLS/aapt" dump badging "$APK")
PACKAGE=$(printf '%s\n' "$BADGING" | sed -n '1p')
case "$PACKAGE" in
    "package: name='com.weartak.plugin.lattice' versionCode='$CI_PIPELINE_IID' versionName='5.8.4'"*) ;;
    *) echo "Unexpected release identity or version." >&2; exit 1 ;;
esac
OUTPUT="$CI_PROJECT_DIR/app/build/outputs/lattice/release"
NAME="WearTAK-Plugin-Lattice-5.8.4-p$CI_PIPELINE_IID.apk"
mkdir -p "$OUTPUT"
cp -- "$APK" "$OUTPUT/$NAME"
(
    cd "$OUTPUT"
    sha256sum "$NAME" > "$NAME.sha256"
)
printf 'source_sha=%s\npipeline_id=%s\npipeline_iid=%s\njob_id=%s\npackage=com.weartak.plugin.lattice\nversion_name=5.8.4\nversion_code=%s\nsigner_sha256=%s\n' \
    "$CI_COMMIT_SHA" "$CI_PIPELINE_ID" "$CI_PIPELINE_IID" "$CI_JOB_ID" "$CI_PIPELINE_IID" "$EXPECTED" > "$OUTPUT/$NAME.provenance.txt"
echo "Verified production-signed release: $NAME"
