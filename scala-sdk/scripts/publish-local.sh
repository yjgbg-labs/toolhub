#!/bin/sh
set -eu
sdk=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$sdk/config.env"

platform=${1:?Expected jvm, js or native}
case "$platform" in
  jvm) suffix=_3 ;;
  js) suffix=_sjs1_3 ;;
  native) suffix=_native0.5_3 ;;
  *) echo "Unknown platform: $platform" >&2; exit 1 ;;
esac

mkdir -p "$SDK_MAVEN_REPOSITORY"
repository=$(CDPATH= cd -- "$SDK_MAVEN_REPOSITORY" && pwd)
repository_uri=$(bun -e 'console.log(require("node:url").pathToFileURL(process.argv[1]).href)' "$repository")
organization_path=$(printf '%s' "$SDK_ORGANIZATION" | tr . /)

publish() {
  name=$1
  sources=$2
  set -- --power publish "$sdk/project.scala" "$sources" \
    --organization "$SDK_ORGANIZATION" --module-name "$name$suffix" \
    --project-version "$SDK_VERSION" --publish-repository "$repository" \
    --repository "$repository_uri" --sources-jar --doc=false --signer none \
    --workspace "$sdk/.scala-build/publish/$platform/$name" \
    --suppress-directives-in-multiple-files-warning --suppress-outdated-dependency-warning

  case "$platform" in
    jvm) set -- "$@" --scala-option -release:17 ;;
    js) set -- "$@" --js --js-version 1.22.0 ;;
    native) : ;;
  esac
  if [ "$name" != sprout-sdk-core ]; then
    set -- "$@" --dep "$SDK_ORGANIZATION:sprout-sdk-core$suffix:$SDK_VERSION"
  fi
  if [ "$name" = sprout-sdk-native-unix ]; then
    set -- "$@" --dep "$SDK_ORGANIZATION:sprout-sdk-sttp$suffix:$SDK_VERSION"
  fi
  if [ "$platform" = native ]; then
    sh "$sdk/scripts/native.sh" "$@"
  else
    sh "$sdk/scripts/scala.sh" "$@"
  fi

  # Keep SHA-256 alongside Scala CLI's standard Maven POM/JAR checksums.
  base="$repository/$organization_path/$name$suffix/$SDK_VERSION/$name$suffix-$SDK_VERSION"
  for file in "$base.jar" "$base-sources.jar" "$base.pom"; do
    sha256sum "$file" | cut -d ' ' -f 1 > "$file.sha256"
  done
}

publish sprout-sdk-core "$sdk/core"
publish sprout-client "$sdk/generated/sprout"
publish sprout-sdk-cats "$sdk/adapters/cats"
publish sprout-sdk-zio "$sdk/adapters/zio"
publish sprout-sdk-sttp "$sdk/adapters/sttp"
if [ "$platform" = native ]; then
  publish sprout-sdk-native-unix "$sdk/adapters/native"
fi
printf 'Published %s artifacts to %s (%s:%s)\n' "$platform" "$repository" "$SDK_ORGANIZATION" "$SDK_VERSION"
