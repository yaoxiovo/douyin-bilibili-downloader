#!/usr/bin/env bash
set -euo pipefail

version=$(sed -nE "s/^[[:space:]]*versionName[[:space:]]+'([^']+)'[[:space:]]*$/\1/p" android/hub/build.gradle)
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::Expected a major.minor.patch Hub versionName" >&2
  exit 1
fi
printf "%s\n" "$version"
