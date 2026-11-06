#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET="$ROOT/.dependencies/sdk"
REVISION=$(tr -d '\r\n' < "$ROOT/sdk-revision.txt")
case "$REVISION" in
    ''|*[!0-9a-f]*) echo "Invalid SDK revision." >&2; exit 1 ;;
esac
[ "${#REVISION}" -eq 40 ] || { echo "Invalid SDK revision length." >&2; exit 1; }
if [ ! -e "$TARGET" ]; then
    mkdir -p "$ROOT/.dependencies"
    URL=https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git
    if [ -n "${CI_JOB_TOKEN:-}" ]; then
        HEADER=$(printf 'gitlab-ci-token:%s' "$CI_JOB_TOKEN" | base64 | tr -d '\r\n')
        # Older canonical Git images predate GIT_CONFIG_COUNT support.
        PARAMETERS="${GIT_CONFIG_PARAMETERS:+$GIT_CONFIG_PARAMETERS }'http.https://git.tak.gov/.extraHeader=Authorization: Basic $HEADER'"
        env GIT_TERMINAL_PROMPT=0 "GIT_CONFIG_PARAMETERS=$PARAMETERS" \
            git clone --quiet --no-checkout "$URL" "$TARGET"
    else
        GIT_TERMINAL_PROMPT=0 git clone --quiet --no-checkout "$URL" "$TARGET"
    fi
    git -C "$TARGET" checkout --quiet --detach "$REVISION"
fi
ACTUAL=$(git -C "$TARGET" rev-parse HEAD)
DIRTY=$(git -C "$TARGET" status --porcelain --untracked-files=all)
[ "$ACTUAL" = "$REVISION" ] && [ -z "$DIRTY" ] || {
    echo "SDK checkout differs from pin. Preserve its changes; supply a clean pinned checkout." >&2
    exit 1
}
[ -f "$TARGET/LICENSE" ] || { echo "SDK license missing." >&2; exit 1; }
echo "Pinned SDK source and license verified."
