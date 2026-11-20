#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
# Writes publish=true to $GITHUB_OUTPUT when api version $1 is not released yet:
# no tag api-v<version> on origin and no POM on Maven Central.
set -u
version=$1
pom="https://repo1.maven.org/maven2/org/ankiquest/api/$version/api-$version.pom"

if git ls-remote --exit-code --tags origin "refs/tags/api-v$version" > /dev/null || curl -sfI "$pom" > /dev/null; then
  echo "$version is released already"
  echo "publish=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

echo "$version is new: publishing"
echo "publish=true" >> "$GITHUB_OUTPUT"
