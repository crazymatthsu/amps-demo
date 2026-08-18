#!/usr/bin/env bash
#
# Shared launcher for the tools in this directory.
#
# Each wrapper sources this and calls `launch <subcommand> "$@"`. The job here is
# to make the tools usable without knowing anything about Gradle: find the
# installed launcher, build it if it is missing or stale, and run it from the
# repository root so relative --file/--out paths mean what the caller meant.

set -euo pipefail

BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${BIN_DIR}/../.." && pwd)"
LAUNCHER="${REPO_ROOT}/utils/build/install/amps-utils/bin/amps-utils"

launch() {
    local subcommand="$1"
    shift

    if [[ ! -x "${LAUNCHER}" ]] || is_stale; then
        echo "building the utils launcher (first run, or sources changed)..." >&2
        (cd "${REPO_ROOT}" && ./gradlew :utils:installDist -q) \
            || { echo "error: ./gradlew :utils:installDist failed" >&2; exit 1; }
    fi

    # Run from the repository root: server/data, and the relative paths people
    # type, are all meant relative to it.
    cd "${REPO_ROOT}"
    exec "${LAUNCHER}" "${subcommand}" "$@"
}

# Rebuild if any source is newer than the installed launcher, so editing a tool
# and re-running does the obvious thing.
is_stale() {
    local newer
    newer="$(find "${REPO_ROOT}/utils/src" "${REPO_ROOT}/common/src" \
        -name '*.java' -newer "${LAUNCHER}" -print -quit 2>/dev/null || true)"
    [[ -n "${newer}" ]]
}
