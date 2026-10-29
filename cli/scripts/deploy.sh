#!/bin/sh
set -eu
mkdir -p cli/dist
sh cli/scripts/native.sh --power package cli/project.scala cli/src/main/scala \
  --main-class sprout.cli.NativeCli -o cli/dist/sprout-native --force
bun cli/test/native-cli.ts
bun test cli/test/release.test.ts
[ "${CHECK_ONLY:-0}" != 1 ] || exit 0
sh cli/scripts/release.sh upload
sh cli/scripts/release.sh install-built
