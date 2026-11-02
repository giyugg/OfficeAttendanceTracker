#!/bin/sh
set -eu
cd "$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
if ! command -v java >/dev/null 2>&1; then
  echo "Install Java 21 or newer and make sure java is available on PATH." >&2
  exit 1
fi
if [ ! -f target/attendance-tracker.jar ]; then
  echo "Building the attendance tracker. Internet is needed for the first build."
  sh ./mvnw -B -ntp verify
fi
exec java -jar target/attendance-tracker.jar "$@"
