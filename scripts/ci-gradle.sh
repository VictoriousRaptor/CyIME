#!/usr/bin/env bash
# Retry transient dependency transfers while preserving Gradle's live output and exit status.
set -uo pipefail
log=$(mktemp) || exit 1
trap 'rm -f "$log"' EXIT

for attempt in 1 2 3; do
  ./gradlew "$@" --stacktrace 2>&1 | tee "$log"
  status=("${PIPESTATUS[@]}")
  if (( status[1] != 0 )); then exit "${status[1]}"; fi
  if (( status[0] == 0 )); then exit 0; fi
  if (( attempt == 3 )) || ! grep -Eiq 'Received status code (408|429|500|502|503|504)|java\.net\.(SocketTimeoutException|UnknownHostException)|Connection reset by peer|java\.net\.SocketException: Connection reset' "$log"; then
    exit "${status[0]}"
  fi
  echo "::warning::Gradle reported a transient network failure (attempt $attempt/3). Retrying."
  sleep "$((attempt * 10))"
done
