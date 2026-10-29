#!/bin/sh
set -eu
set -a
. ./config.env
set +a
bun install --cwd plugin --frozen-lockfile
bun run --cwd plugin check
bun run --cwd plugin build
bun test plugin/test
[ "${CHECK_ONLY:-0}" != 1 ] || exit 0
PLUGIN_SHA256=$(sha256sum plugin/dist/index.js | cut -d ' ' -f 1)
export PLUGIN_SHA256
printf '%s  index.js\n' "$PLUGIN_SHA256" > plugin/dist/index.js.sha256
printf 'PLUGIN_SHA256=%s\n' "$PLUGIN_SHA256" > plugin/dist/release.env
destination="$ARTIFACT_UPLOAD_BASE/plugin/$PLUGIN_SHA256"
if ! mc stat "$destination/index.js" >/dev/null 2>&1; then
  mc cp --quiet plugin/dist/index.js "$destination/index.js"
fi
mc cp --quiet plugin/dist/index.js.sha256 "$destination/index.js.sha256"
bun plugin/scripts/install.ts
