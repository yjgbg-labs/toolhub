#!/bin/sh
set -eu
. ./config.env
mkdir -p artifacts
stage=$(mktemp -d artifacts/package.XXXXXX)
trap 'rm -rf "$stage"' EXIT HUP INT TERM
bun scripts/stage.ts "$stage"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -czf artifacts/package.tar.gz -C "$stage" .
hash=$(sha256sum artifacts/package.tar.gz | cut -d ' ' -f 1)
name="$PROJECT_NAME-$hash.tar.gz"
mv artifacts/package.tar.gz "artifacts/$name"
(cd artifacts && sha256sum "$name" > "$name.sha256")
printf 'ARTIFACT_NAME=%s\nARTIFACT_SHA256=%s\n' "$name" "$hash" > artifacts/artifact.env
printf '%s\n' "artifacts/$name"
