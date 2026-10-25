#!/bin/sh
set -eu
sdk=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$sdk/config.env"

platform=${1:?Expected jvm, js or native}
scope=${2:-protocol}
case "$scope" in protocol|client) : ;; *) echo "Expected protocol or client" >&2; exit 1 ;; esac
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
  version=$SDK_VERSION
  if [ "$scope" = client ]; then version=$SDK_CLIENT_VERSION; fi
  base="$repository/$organization_path/$name$suffix/$version/$name$suffix-$version"
  # Released protocol/tooling artifacts are immutable. Business publishing never
  # calls this branch and cannot republish the core, adapters or generator.
  if [ -f "$base.jar" ]; then
    case "$version" in
      *-SNAPSHOT) : ;;
      *)
        for file in "$base.jar" "$base-sources.jar" "$base.pom"; do
          expected=$(cat "$file.sha256")
          actual=$(sha256sum "$file" | cut -d ' ' -f 1)
          test "$actual" = "$expected"
        done
        printf 'Already published: %s:%s (immutable)\n' "$name$suffix" "$version"
        return
        ;;
    esac
  fi
  set -- --power publish "$sdk/project.scala" "$sources" \
    --organization "$SDK_ORGANIZATION" --module-name "$name$suffix" \
    --project-version "$version" --publish-repository "$repository" \
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
  for file in "$base.jar" "$base-sources.jar" "$base.pom"; do
    sha256sum "$file" | cut -d ' ' -f 1 > "$file.sha256"
  done
}

if [ "$scope" = client ]; then
  publish "$SDK_CLIENT_MODULE" "$SDK_CLIENT_SOURCES"
else
  publish sprout-sdk-core "$sdk/sdk-core"
  publish sprout-sdk-generator "$sdk/sdk-generator"
  publish sprout-sdk-cats "$sdk/adapters/cats"
  publish sprout-sdk-zio "$sdk/adapters/zio"
  publish sprout-sdk-sttp "$sdk/adapters/sttp"
  if [ "$platform" = native ]; then
    publish sprout-sdk-native-unix "$sdk/adapters/native"
  fi
fi
printf 'Published %s %s artifacts to %s\n' "$scope" "$platform" "$repository"
