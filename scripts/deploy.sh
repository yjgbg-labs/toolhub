#!/bin/sh
set -eu
set -a
. ./config.env
. ./artifacts/artifact.env
set +a
exec bun scripts/deploy.ts "$@"
