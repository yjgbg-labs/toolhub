#!/bin/sh
set -eu
mkdir -p scala-sdk/dist
set -- scala-sdk/project.scala scala-sdk/sdk-core \
  scala-sdk/adapters/cats scala-sdk/adapters/zio scala-sdk/adapters/sttp \
  scala-sdk/generated scala-sdk/tests/Check.scala --main-class sprout.sdk.tests.Check

for platform in jvm js native; do
  case "$platform" in
    jvm) sh scala-sdk/scripts/scala.sh run "$@" ;;
    js)
      sh scala-sdk/scripts/scala.sh --power package "$@" --js --js-module-kind commonjs -o scala-sdk/dist/check.cjs --force
      bun scala-sdk/dist/check.cjs
      ;;
    native)
      sh scala-sdk/scripts/native.sh --power package "$@" -o scala-sdk/dist/check-native --force
      scala-sdk/dist/check-native
      ;;
  esac
done
