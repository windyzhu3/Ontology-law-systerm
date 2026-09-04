#!/bin/sh
# Maven Wrapper 3.3.4 bootstrap for the ADR-0004 exact Maven distribution.
set -eu

project_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
properties_file="$project_directory/.mvn/wrapper/maven-wrapper.properties"

property() {
  sed -n "s/^$1=//p" "$properties_file" | tail -n 1
}

distribution_url=$(property distributionUrl)
expected_sha256=$(property distributionSha256Sum)
expected_sha512=$(property distributionSha512Sum)

if [ -n "${MAVEN_HOME:-}" ] && [ -x "${MAVEN_HOME}/bin/mvn" ]; then
  exec "${MAVEN_HOME}/bin/mvn" "$@"
fi

maven_user_home=${MAVEN_USER_HOME:-${HOME}/.m2}
distribution_home="$maven_user_home/wrapper/dists/apache-maven-3.9.16-bin/verified/apache-maven-3.9.16"

if [ ! -x "$distribution_home/bin/mvn" ]; then
  temporary_directory=$(mktemp -d "${TMPDIR:-/tmp}/mvnw.XXXXXX")
  archive="$temporary_directory/apache-maven-3.9.16-bin.zip"
  trap 'rm -rf "$temporary_directory"' EXIT HUP INT TERM
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$distribution_url" --output "$archive"
  elif command -v wget >/dev/null 2>&1; then
    wget --quiet "$distribution_url" --output-document="$archive"
  else
    echo "mvnw requires curl or wget to download Maven" >&2
    exit 1
  fi
  printf '%s  %s\n' "$expected_sha256" "$archive" | sha256sum --check --status
  printf '%s  %s\n' "$expected_sha512" "$archive" | sha512sum --check --status
  mkdir -p "$(dirname -- "$distribution_home")"
  unzip -q "$archive" -d "$(dirname -- "$distribution_home")"
  trap - EXIT HUP INT TERM
  rm -rf "$temporary_directory"
fi

exec "$distribution_home/bin/mvn" "$@"
