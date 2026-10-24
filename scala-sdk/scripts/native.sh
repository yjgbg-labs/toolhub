#!/bin/sh
set -eu
sdk=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# Ubuntu development fallback, like the repository's native helpers: extract
# toolchain/header packages into a user-owned cache rather than requiring sudo.
if ! command -v clang >/dev/null 2>&1 || ! command -v pkg-config >/dev/null 2>&1 || ! pkg-config --exists libcurl openssl libidn2; then
  cache="$sdk/.cache/native"
  if [ ! -f "$cache/ready-v2" ]; then
    mkdir -p "$cache/packages"
    (cd "$cache/packages" && apt-get download clang-21 libclang-common-21-dev llvm-21-linker-tools libcurl4-openssl-dev libcurl4t64 libidn2-dev libidn2-0 libssl-dev libssl3t64 libunwind-dev libunwind8 libgc-dev libgc1 pkgconf-bin)
    for package in "$cache"/packages/*.deb; do dpkg-deb -x "$package" "$cache"; done
    touch "$cache/ready-v2"
  fi
  multiarch=$(gcc -print-multiarch)
  export PATH="$cache/usr/lib/llvm-21/bin:$cache/usr/bin:$PATH"
  export C_INCLUDE_PATH="$cache/usr/include:$cache/usr/include/$multiarch${C_INCLUDE_PATH:+:$C_INCLUDE_PATH}"
  export CPLUS_INCLUDE_PATH="$C_INCLUDE_PATH${CPLUS_INCLUDE_PATH:+:$CPLUS_INCLUDE_PATH}"
  export LIBRARY_PATH="$cache/usr/lib/$multiarch${LIBRARY_PATH:+:$LIBRARY_PATH}"
  export LD_LIBRARY_PATH="$cache/usr/lib/$multiarch${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
[ "${1:-}" != "--setup" ] || exit 0
exec sh "$sdk/scripts/scala.sh" "$@" --native --native-version 0.5.12
