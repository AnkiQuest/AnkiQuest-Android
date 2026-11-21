#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
# Writes publish=true to $GITHUB_OUTPUT when api version $1 is not released yet:
# no tag api-v<version> on origin and no POM on Maven Central.
set -euo pipefail
readonly GIT_NO_MATCH=2
readonly HTTP_OK=200
readonly HTTP_NOT_FOUND=404
readonly CONNECT_TIMEOUT_SECONDS=10
readonly REQUEST_TIMEOUT_SECONDS=30

version=$1
pom="https://repo1.maven.org/maven2/org/ankiquest/api/$version/api-$version.pom"

# Only an absent tag permits checking Maven. A GitHub lookup error is not absence.
if git ls-remote --exit-code --tags origin "refs/tags/api-v$version" > /dev/null; then
  echo "$version is released already"
  echo "publish=false" >> "$GITHUB_OUTPUT"
  exit 0
else
  git_status=$?
fi

if [ "$git_status" -ne "$GIT_NO_MATCH" ]; then
  echo "Could not check the release tag (git exit $git_status)" >&2
  exit 1
fi

# Distinguish a missing POM from transport failures, refusals and server errors.
if ! http_status=$(curl --silent --show-error --head --output /dev/null \
  --connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$REQUEST_TIMEOUT_SECONDS" \
  --write-out '%{http_code}' "$pom"); then
  echo "Could not check the release on Maven Central" >&2
  exit 1
fi

case "$http_status" in
  "$HTTP_OK")
    echo "$version is released already"
    echo "publish=false" >> "$GITHUB_OUTPUT"
    ;;
  "$HTTP_NOT_FOUND")
    echo "$version is new: publishing"
    echo "publish=true" >> "$GITHUB_OUTPUT"
    ;;
  *)
    echo "Could not check the release on Maven Central (HTTP $http_status)" >&2
    exit 1
    ;;
esac
