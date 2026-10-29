#!/bin/sh
set -eu
set -a
. ./config.env
set +a
mkdir -p server/dist
sh server/scripts/scala.sh --power package server/project.scala server/src/main/scala \
  --assembly --preamble=false --main-class groot.Main -o server/dist/groot.jar --force
[ "${CHECK_ONLY:-0}" != 1 ] || exit 0
SERVER_SHA256=$(sha256sum server/dist/groot.jar | cut -d ' ' -f 1)
export SERVER_SHA256
printf '%s  groot.jar\n' "$SERVER_SHA256" > server/dist/groot.jar.sha256
printf 'SERVER_SHA256=%s\n' "$SERVER_SHA256" > server/dist/release.env
destination="$ARTIFACT_UPLOAD_BASE/server/$SERVER_SHA256"
if ! mc stat "$destination/groot.jar" >/dev/null 2>&1; then
  mc cp --quiet server/dist/groot.jar "$destination/groot.jar"
fi
mc cp --quiet server/dist/groot.jar.sha256 "$destination/groot.jar.sha256"
bun server/scripts/install.ts
