#!/bin/sh
set -eu
cli=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$cli/../config.env"
case "${1:-}" in
  upload)
    hash=$(sha256sum "$cli/dist/sprout-native" | cut -d ' ' -f 1)
    printf '%s  sprout-native\n' "$hash" > "$cli/dist/sprout-native.sha256"
    destination="$ARTIFACT_UPLOAD_BASE/native-cli/$hash"
    if ! mc stat "$destination/sprout-native" >/dev/null 2>&1; then
      mc cp --quiet "$cli/dist/sprout-native" "$destination/sprout-native"
    fi
    mc cp --quiet "$cli/dist/sprout-native.sha256" "$destination/sprout-native.sha256"
    printf 'CLI_SHA256=%s\n' "$hash" > "$cli/dist/release.env"
    ;;
  install|install-built)
    if [ "$1" = install-built ]; then . "$cli/dist/release.env"; fi
    case "${CLI_SHA256:-}" in *[!a-f0-9]*|'') echo 'Invalid CLI SHA-256' >&2; exit 1 ;; esac
    test "${#CLI_SHA256}" -eq 64
    mkdir -p "$CLI_INSTALL_ROOT/releases" "$CLI_BIN_DIR"
    stage=$(mktemp -d "$CLI_INSTALL_ROOT/releases/.download.XXXXXX")
    temporary=
    trap 'rm -rf "$stage"; if [ -n "$temporary" ]; then rm -f "$temporary"; fi' EXIT HUP INT TERM
    url="$ARTIFACT_DOWNLOAD_BASE/native-cli/$CLI_SHA256"
    curl --fail --silent --show-error --location --max-time 180 "$url/sprout-native" -o "$stage/sprout-native"
    curl --fail --silent --show-error --location --max-time 30 "$url/sprout-native.sha256" -o "$stage/sprout-native.sha256"
    (cd "$stage" && sha256sum -c sprout-native.sha256)
    test "$(sha256sum "$stage/sprout-native" | cut -d ' ' -f 1)" = "$CLI_SHA256"
    chmod 755 "$stage/sprout-native"
    release="$CLI_INSTALL_ROOT/releases/$CLI_SHA256"
    if [ -e "$release" ]; then cmp "$stage/sprout-native" "$release/sprout-native"; else mv "$stage" "$release"; fi
    for name in sprout sprout-native; do
      destination="$CLI_BIN_DIR/$name"
      if [ -L "$destination" ]; then
        case "$(readlink "$destination")" in "$CLI_INSTALL_ROOT"/releases/*/sprout-native) : ;; *) echo "Unmanaged link: $destination" >&2; exit 1 ;; esac
      elif [ -e "$destination" ]; then
        grep -q 'Managed by sprout-http deployment' "$destination" || { echo "Unmanaged file: $destination" >&2; exit 1; }
      fi
      temporary="$CLI_BIN_DIR/.$name.$$"
      ln -s "$release/sprout-native" "$temporary"
      mv -Tf "$temporary" "$destination"
    done
    printf 'Installed Native CLI: %s/sprout (%s)\n' "$CLI_BIN_DIR" "$CLI_SHA256"
    ;;
  *) echo 'Expected upload, install or install-built' >&2; exit 1 ;;
esac
