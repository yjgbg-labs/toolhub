#!/bin/sh
set -eu
set -a
. ./config.env
if [ "${1:-}" = all ]; then
  . ./artifacts/artifact.env
  mc cp --quiet "artifacts/$ARTIFACT_NAME" "artifacts/$ARTIFACT_NAME.sha256" "$ARTIFACT_UPLOAD_BASE/"
fi
set +a
exec bun scripts/deploy.ts "$@"
