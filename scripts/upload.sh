#!/bin/sh
set -eu
. ./config.env
. ./artifacts/artifact.env
for name in "$ARTIFACT_NAME" "$ARTIFACT_NAME.sha256"; do
  url="$ARTIFACT_UPLOAD_BASE/$name"
  if curl --noproxy '*' --fail --silent --head "$url" >/dev/null 2>&1; then
    remote=$(curl --noproxy '*' --fail --silent --show-error "$url" | sha256sum | cut -d ' ' -f 1)
    local=$(sha256sum "artifacts/$name" | cut -d ' ' -f 1)
    [ "$remote" = "$local" ] || { printf 'Artifact conflict: %s\n' "$url" >&2; exit 1; }
  else
    curl --noproxy '*' --fail --silent --show-error --upload-file "artifacts/$name" "$url"
  fi
  printf '%s\n' "$url"
done
