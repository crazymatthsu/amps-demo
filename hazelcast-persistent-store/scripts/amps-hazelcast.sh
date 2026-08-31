#!/usr/bin/env bash
#
# AMPS lifecycle for the hazelcast-persistent-store demo: the standard
# amps.sh, pinned to the `hazelcast` flow (server/config/flows/hazelcast/).
#
#   ./hazelcast-persistent-store/scripts/amps-hazelcast.sh start
#   ./hazelcast-persistent-store/scripts/amps-hazelcast.sh status | logs | wait
#   ./hazelcast-persistent-store/scripts/amps-hazelcast.sh restart   # hz.persistent
#                                                                    # survives this
#   ./hazelcast-persistent-store/scripts/amps-hazelcast.sh validate
#   ./hazelcast-persistent-store/scripts/amps-hazelcast.sh stop | reset
#
# AMPS_IMAGE must point at an image built from server/Containerfile (there is
# no public AMPS image -- see the repository README). Every other amps.sh
# environment override applies unchanged; this wrapper only pins AMPS_FLOW.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

AMPS_FLOW=hazelcast exec "${REPO_ROOT}/server/scripts/amps.sh" "$@"
