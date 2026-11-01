package groot.usejdk

import java.nio.charset.StandardCharsets.UTF_8

private[usejdk] case class InvalidUseJdk(message: String) extends RuntimeException(message)

/** Only generates shell; discovery, installation and command execution happen on the CLI host. */
object UseJdkService:
  private def quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

  def compile(version: Int, command: String): String =
    if version < 1 then throw InvalidUseJdk("version must be a positive JDK major version")
    if command.trim.isEmpty || command.contains('\u0000') then throw InvalidUseJdk("command must be nonblank and contain no NUL")
    if command.getBytes(UTF_8).length > 64 * 1024 then throw InvalidUseJdk("command exceeds 64 KiB UTF-8")
    s"#!/bin/sh\nset -eu\nversion=$version\n" + setup + s"\nexec /bin/sh -c ${quote(command)}\n"

  private val setup = """
fail() { printf 'use-jdk: %s\n' "$*" >&2; exit 1; }
find_home() {
  for candidate in "$1" "$1/Contents/Home"; do
    if [ -x "$candidate/bin/java" ] && [ -x "$candidate/bin/javac" ] && [ -f "$candidate/release" ]; then
      release_version=$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$candidate/release")
      case "$release_version" in
        "$version"|"$version".*|"$version"-*|"$version"+*|"1.$version."*) printf '%s\n' "$candidate"; return 0 ;;
      esac
    fi
  done
  return 1
}
root=${GROOT_HOME:-${HOME:?HOME must be set}/.config/groot}
case "$root" in /*) ;; *) root="$PWD/$root" ;; esac
cache="$root/jdks"
install="$cache/temurin-$version"
if ! java_home=$(find_home "$install"); then
  [ ! -e "$install" ] && [ ! -L "$install" ] || fail "cached JDK is incomplete or has the wrong version: $install"
  case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=mac ;;
    *) fail 'unsupported operating system; expected Linux or macOS' ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    aarch64|arm64) arch=aarch64 ;;
    i386|i486|i586|i686) arch=x32 ;;
    armv7l|armv6l) arch=arm ;;
    ppc64le) arch=ppc64le ;;
    ppc64) arch=ppc64 ;;
    s390x) arch=s390x ;;
    riscv64) arch=riscv64 ;;
    *) fail 'unsupported CPU architecture' ;;
  esac
  for tool in curl jq tar; do command -v "$tool" >/dev/null 2>&1 || fail "required for JDK installation: $tool"; done
  if command -v sha256sum >/dev/null 2>&1; then checksum_tool=sha256sum
  elif command -v shasum >/dev/null 2>&1; then checksum_tool=shasum
  else fail 'required for JDK installation: sha256sum or shasum'; fi
  mkdir -p "$cache"
  work=$(mktemp -d "$cache/.temurin-$version.XXXXXXXX")
  lock=
  cleanup() {
    [ -z "$lock" ] || rm -f "$lock"
    rm -rf "$work"
  }
  trap cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  printf 'Downloading Eclipse Temurin JDK %s from Adoptium...\n' "$version" >&2
  metadata="https://api.adoptium.net/v3/assets/feature_releases/$version/ga?architecture=$arch&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=$os&page=0&page_size=1&project=jdk&vendor=eclipse"
  curl --fail --location --silent --show-error --retry 3 --connect-timeout 10 --max-time 60 "$metadata" -o "$work/releases.json"
  jq -e --argjson version "$version" --arg os "$os" --arg arch "$arch" '
    [.[0] | select(.version_data.major == $version) | .binaries[] |
      select(.os == $os and .architecture == $arch and .image_type == "jdk") | .package][0] // error("No matching JDK release")
  ' "$work/releases.json" > "$work/package.json"
  url=$(jq -er '.link | select(type == "string" and startswith("https://"))' "$work/package.json")
  checksum=$(jq -er '.checksum | select(type == "string" and test("^[0-9a-fA-F]{64}$"))' "$work/package.json" | tr 'A-F' 'a-f')
  [ -n "$checksum" ] || fail 'missing package SHA-256'
  curl --fail --location --retry 3 --connect-timeout 10 --max-time 600 "$url" -o "$work/jdk.tar.gz"
  if [ "$checksum_tool" = sha256sum ]; then
    actual=$(sha256sum < "$work/jdk.tar.gz")
  else
    actual=$(shasum -a 256 < "$work/jdk.tar.gz")
  fi
  actual=${actual%% *}
  [ "$actual" = "$checksum" ] || fail 'downloaded JDK SHA-256 mismatch'
  mkdir "$work/extracted"
  tar -xzf "$work/jdk.tar.gz" -C "$work/extracted"
  archive_root=
  for directory in "$work/extracted" "$work/extracted"/*; do
    if find_home "$directory" >/dev/null; then
      [ -z "$archive_root" ] || fail 'archive contains multiple JDK roots'
      archive_root=$directory
    fi
  done
  [ -n "$archive_root" ] || fail 'archive does not contain the requested JDK'
  # Serialize only publication, keeping downloads independent and the final cache complete.
  attempts=0
  until (set -C; : > "$cache/.temurin-$version.lock") 2>/dev/null; do
    attempts=$((attempts + 1))
    [ "$attempts" -lt 50 ] || fail "JDK publication is locked: $cache/.temurin-$version.lock"
    sleep 0.1
  done
  lock="$cache/.temurin-$version.lock"
  if ! java_home=$(find_home "$install"); then
    [ ! -e "$install" ] && [ ! -L "$install" ] || fail "cached JDK is incomplete or has the wrong version: $install"
    mv "$archive_root" "$install"
    java_home=$(find_home "$install") || fail 'installed JDK is unusable'
    printf 'Installed Eclipse Temurin JDK %s at %s\n' "$version" "$install" >&2
  fi
  cleanup
  trap - EXIT HUP INT TERM
fi
JAVA_HOME=$java_home
PATH="$JAVA_HOME/bin:${PATH:-}"
export JAVA_HOME PATH
"""
