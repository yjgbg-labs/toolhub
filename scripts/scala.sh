#!/bin/sh
set -eu
. ./config.env
export COURSIER_REPOSITORIES=https://repo.maven.apache.org/maven2
exec groot use-jdk "$JDK_MAJOR" -- scala-cli "$@" --jvm system --server=false
