#!/usr/bin/env bash
#
# AMPS lifecycle for the cache-persistent-store demo: the standard amps.sh,
# pinned to the `cache` flow (server/config/flows/cache/).
#
#   ./cache-persistent-store/scripts/amps-cache.sh start
#   ./cache-persistent-store/scripts/amps-cache.sh status | logs | wait
#   ./cache-persistent-store/scripts/amps-cache.sh restart      # the recovery demo:
#                                                               # SOW survives restart
#   ./cache-persistent-store/scripts/amps-cache.sh validate     # server checks the config
#   ./cache-persistent-store/scripts/amps-cache.sh stop | reset
#
# AMPS_IMAGE must point at an image built from server/Containerfile (there is
# no public AMPS image -- see the repository README). Every other amps.sh
# environment override applies unchanged; this wrapper only pins AMPS_FLOW.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

AMPS_FLOW=cache exec "${REPO_ROOT}/server/scripts/amps.sh" "$@"
