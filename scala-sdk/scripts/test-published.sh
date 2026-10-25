#!/bin/sh
set -eu
sdk=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$sdk/config.env"
repository=$(CDPATH= cd -- "$SDK_MAVEN_REPOSITORY" && pwd)
repository_uri=$(bun -e 'console.log(require("node:url").pathToFileURL(process.argv[1]).href)' "$repository")
if [ "$repository" = "$HOME/.m2/repository" ]; then repository_uri=m2Local; fi
consumer=$(mktemp -d /tmp/opencode/sprout-published-consumer.XXXXXX)
trap 'rm -rf "$consumer"' EXIT HUP INT TERM
cp "$sdk/tests/PublishedCheck.scala" "$consumer/Main.scala"

for platform in jvm js native; do
  case "$platform" in
    jvm) suffix=_3 ;;
    js) suffix=_sjs1_3 ;;
    native) suffix=_native0.5_3 ;;
  esac
  set -- "$consumer/Main.scala" --scala 3.9.0 --repository "$repository_uri" --main-class consumer.PublishedCheck --suppress-outdated-dependency-warning
  for module in sprout-client sprout-sdk-cats sprout-sdk-zio sprout-sdk-sttp; do
    set -- "$@" --dep "$SDK_ORGANIZATION:$module$suffix:$SDK_VERSION"
  done
  case "$platform" in
    jvm) sh "$sdk/scripts/scala.sh" run "$@" ;;
    js)
      sh "$sdk/scripts/scala.sh" --power package "$@" --js --js-version 1.22.0 --js-module-kind commonjs -o "$consumer/check.cjs"
      bun "$consumer/check.cjs"
      ;;
    native)
      sh "$sdk/scripts/native.sh" --power package "$@" -o "$consumer/check-native"
      "$consumer/check-native"
      ;;
  esac
done

# The generic native CLI uses only published runtime/transport dependencies.
# Its build has no generated business client or SDK source directories.
cp -R "$sdk/cli" "$consumer/cli"
sh "$sdk/scripts/native.sh" --power package "$consumer/cli" --scala 3.9.0 \
  --repository "$repository_uri" --main-class sprout.sdk.cli.NativeCli --suppress-outdated-dependency-warning \
  --dep "$SDK_ORGANIZATION:sprout-sdk-native-unix_native0.5_3:$SDK_VERSION" \
  -o "$consumer/sprout-native"
bun "$sdk/tests/native-cli.ts" "$consumer/sprout-native"
