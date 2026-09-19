#!/bin/sh
set -eu
set -a
. ./config.env
set +a
exec bun scripts/deploy.ts "$@"
