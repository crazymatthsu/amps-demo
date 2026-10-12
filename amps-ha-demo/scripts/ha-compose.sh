#!/usr/bin/env bash
#
# The amps-ha-demo pair -- an AMPS primary and secondary replicating to each
# other -- via podman compose (or docker compose), with the bind-mounted data
# folders created for you and the readiness of BOTH instances and the
# replication link between them actually waited for.
#
#   scripts/ha-compose.sh start             create the folders, start both, wait for the link
#   scripts/ha-compose.sh stop              stop both (state kept)
#   scripts/ha-compose.sh down              stop and remove both containers (state kept)
#   scripts/ha-compose.sh restart           down, then start
#   scripts/ha-compose.sh status            compose ps, plus each instance's replication state
#   scripts/ha-compose.sh logs [svc] [-f]   amps-primary | amps-secondary
#   scripts/ha-compose.sh failover          THE DEMO: SIGKILL the primary while clients are
#                                           on it. An HAClient publisher and consumer move to
#                                           the secondary; nothing is lost. (= kill primary)
#   scripts/ha-compose.sh kill <primary|secondary>
#                                           SIGKILL one instance: a crash, not a shutdown
#   scripts/ha-compose.sh revive <primary|secondary>
#                                           start a killed instance again on its own data and
#                                           wait until it has recovered and rejoined replication
#   scripts/ha-compose.sh demo              run the Java demo (publisher + consumer, one JVM)
#                                           against the stack; kill an instance while it runs
#   scripts/ha-compose.sh validate          the server's own --verify-config on both configs
#   scripts/ha-compose.sh reset             down, then DELETE deploy/: both instances' SOW,
#                                           journal and stats
#   scripts/ha-compose.sh printenv          show what the variables resolved to
#
# Environment:
#   AMPS_IMAGE           REQUIRED: an image built from server/Containerfile
#   AMPS_PLATFORM        default linux/amd64 (the AMPS distribution is x86_64 only)
#   AMPS_BIN             the server binary in the image (default /opt/amps/bin/ampServer)
#   HA_STACK             compose project / container-name prefix (default amps-ha)
#   HA_PRIMARY_PORT, HA_PRIMARY_WS_PORT, HA_PRIMARY_ADMIN_PORT         9007 / 9008 / 8085
#   HA_SECONDARY_PORT, HA_SECONDARY_WS_PORT, HA_SECONDARY_ADMIN_PORT   9107 / 9108 / 8185
#   CONTAINER_ENGINE     podman | docker (default: podman if present, else docker)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
COMPOSE_FILE="${MODULE_DIR}/compose.yml"
DEPLOY_DIR="${MODULE_DIR}/deploy"

die() { echo "error: $*" >&2; exit 1; }

export HA_STACK="${HA_STACK:-amps-ha}"
export AMPS_PLATFORM="${AMPS_PLATFORM:-linux/amd64}"
export AMPS_BIN="${AMPS_BIN:-/opt/amps/bin/ampServer}"
export HA_PRIMARY_PORT="${HA_PRIMARY_PORT:-9007}"
export HA_PRIMARY_WS_PORT="${HA_PRIMARY_WS_PORT:-9008}"
export HA_PRIMARY_ADMIN_PORT="${HA_PRIMARY_ADMIN_PORT:-8085}"
export HA_SECONDARY_PORT="${HA_SECONDARY_PORT:-9107}"
export HA_SECONDARY_WS_PORT="${HA_SECONDARY_WS_PORT:-9108}"
export HA_SECONDARY_ADMIN_PORT="${HA_SECONDARY_ADMIN_PORT:-8185}"

# SELinux hosts need :z on bind mounts or the container cannot read them --
# the same rule server/scripts/amps.sh applies. macOS and plain Linux do not.
if [[ "$(uname -s)" == "Linux" ]] && command -v getenforce >/dev/null 2>&1 \
        && [[ "$(getenforce 2>/dev/null)" != "Disabled" ]]; then
    export HA_MOUNT_SUFFIX=":z"
else
    export HA_MOUNT_SUFFIX=""
fi

# The line AMPS logs once it is genuinely ready. An open port is not readiness:
# the engine's port forwarder accepts connections as soon as the container
# exists, and a client that races it is dropped mid-logon.
READY_MARKER="initialization completed"
# What an instance logs when its PEER has connected to its incoming replication
# transport (wording as of AMPS 5.3.5.135). Both instances having logged it
# means the link is up in both directions.
REPLICATION_UP_PATTERN='AMPS replication client session logon'

# --- which engine, which compose? -----------------------------------------
engine() {
    if [[ -n "${CONTAINER_ENGINE:-}" ]]; then
        echo "${CONTAINER_ENGINE}"
    elif command -v podman >/dev/null 2>&1; then
        echo podman
    elif command -v docker >/dev/null 2>&1; then
        echo docker
    else
        die "neither podman nor docker is on PATH"
    fi
}
ENGINE="$(engine)"

compose_command() {
    if [[ "${ENGINE}" == podman ]]; then
        if podman compose version >/dev/null 2>&1; then echo "podman compose"; return; fi
        if command -v podman-compose >/dev/null 2>&1; then echo "podman-compose"; return; fi
    fi
    if docker compose version >/dev/null 2>&1; then echo "docker compose"; return; fi
    if command -v docker-compose >/dev/null 2>&1; then echo "docker-compose"; return; fi
    die "no compose implementation found (tried: podman compose, podman-compose, docker compose, docker-compose)"
}
COMPOSE=()
ensure_compose() {
    if [[ ${#COMPOSE[@]} -eq 0 ]]; then
        read -ra COMPOSE <<< "$(compose_command)"
        COMPOSE+=(-p "${HA_STACK}" -f "${COMPOSE_FILE}")
    fi
}

require_amps_image() {
    [[ -n "${AMPS_IMAGE:-}" ]] || die "AMPS_IMAGE is not set, and there is no public AMPS server image to default to.
Build one from server/Containerfile (see server/scripts/amps.sh), then export AMPS_IMAGE=<the tag>."
}

prepare_dirs() {
    local inst
    for inst in primary secondary; do
        mkdir -p "${DEPLOY_DIR}/${inst}/sow" "${DEPLOY_DIR}/${inst}/journal" "${DEPLOY_DIR}/${inst}/stats"
    done
}

instance_arg() {
    case "${1:-}" in
        primary|secondary) echo "$1" ;;
        *) die "which instance? primary | secondary" ;;
    esac
}

container_name() { echo "${HA_STACK}-$1"; }

container_running() {
    [[ "$("${ENGINE}" inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" == "true" ]]
}

# How many times the container has logged the ready marker. Counted rather
# than matched because `revive` needs a NEW one: the previous run's is still
# in the log, so testing for presence would return at once with a server that
# is still recovering.
ready_count() {
    "${ENGINE}" logs "$1" 2>&1 | grep -c "${READY_MARKER}" || true
}

wait_for_ready() {
    local container="$1" min_count="${2:-1}" timeout="${3:-180}"
    local deadline=$(( SECONDS + timeout ))
    echo -n "waiting for ${container} to finish initialising "
    while (( SECONDS < deadline )); do
        if (( $(ready_count "${container}") >= min_count )); then
            echo " ready"
            return 0
        fi
        if ! container_running "${container}"; then
            echo
            "${ENGINE}" logs "${container}" 2>&1 | tail -20
            die "${container} exited; see the log above"
        fi
        echo -n "."
        sleep 2
    done
    echo " not ready after ${timeout}s"
    return 1
}

wait_for_replication() {
    local container="$1" timeout="${2:-60}"
    local deadline=$(( SECONDS + timeout ))
    echo -n "waiting for ${container} to bring its replication link up "
    while (( SECONDS < deadline )); do
        # grep reads to the end on purpose: with pipefail, a `grep -q` that
        # exits early hands the log command a SIGPIPE and the match reads as a
        # failure.
        if "${ENGINE}" logs "${container}" 2>&1 | grep -E "${REPLICATION_UP_PATTERN}" >/dev/null; then
            echo " up"
            return 0
        fi
        echo -n "."
        sleep 2
    done
    echo " no replication line in ${timeout}s (check '$0 logs')"
    return 1
}

# The replication lines that say something: link logons, resyncs, replays,
# downgrades and upgrades. Not the thread monitor's register/de-register
# chatter, and not the scheduled action's every-two-seconds "module invoked".
replication_lines() {
    "${ENGINE}" logs "$1" 2>&1 | grep -iE "replicat" | grep -vE "thread monitor|action module invoked" \
        | tail -"${2:-4}" | sed 's/^/    /'
}

cmd_start() {
    ensure_compose
    require_amps_image
    prepare_dirs
    echo "starting stack ${HA_STACK}"
    echo "  image:      ${AMPS_IMAGE}"
    echo "  primary:    tcp://127.0.0.1:${HA_PRIMARY_PORT}/amps/json    admin http://127.0.0.1:${HA_PRIMARY_ADMIN_PORT}/   data ${DEPLOY_DIR}/primary"
    echo "  secondary:  tcp://127.0.0.1:${HA_SECONDARY_PORT}/amps/json    admin http://127.0.0.1:${HA_SECONDARY_ADMIN_PORT}/   data ${DEPLOY_DIR}/secondary"
    "${COMPOSE[@]}" up -d amps-primary amps-secondary
    wait_for_ready "$(container_name primary)" || true
    wait_for_ready "$(container_name secondary)" || true
    wait_for_replication "$(container_name primary)" || true
    wait_for_replication "$(container_name secondary)" || true
    echo
    cmd_status
    echo
    echo "run the demo:  $0 demo        (or: ./gradlew :amps-ha-demo:run --args=both)"
    echo "then, while it runs:"
    echo "  $0 failover               # SIGKILL the primary; watch the clients move"
    echo "  $0 revive primary         # bring it back; it catches up from the secondary"
    echo "  $0 kill secondary         # and the clients move again"
}

cmd_stop() {
    ensure_compose
    "${COMPOSE[@]}" stop
    echo "stopped (state in ${DEPLOY_DIR} is untouched)"
}

cmd_down() {
    ensure_compose
    "${COMPOSE[@]}" down
    echo "removed (state in ${DEPLOY_DIR} is untouched)"
}

cmd_restart() {
    cmd_down
    cmd_start
}

cmd_status() {
    ensure_compose
    "${COMPOSE[@]}" ps
    local inst container
    for inst in primary secondary; do
        container="$(container_name "${inst}")"
        if container_running "${container}"; then
            echo "${inst}: running, ${container}; last replication lines:"
            replication_lines "${container}" 3
        else
            echo "${inst}: NOT running (${container})"
        fi
    done
}

cmd_logs() {
    ensure_compose
    "${COMPOSE[@]}" logs "$@"
}

# A crash, not a shutdown: SIGKILL gives the instance no chance to flush,
# close its journal or say goodbye to its peer -- which is the case the
# clients' publish and bookmark stores exist for.
cmd_kill() {
    local inst container
    inst="$(instance_arg "${1:-}")"
    container="$(container_name "${inst}")"
    container_running "${container}" || die "${container} is not running"
    echo "SIGKILL ${container} at $(date +%H:%M:%S.%N | cut -c1-12)"
    "${ENGINE}" kill --signal SIGKILL "${container}" >/dev/null
    echo "${inst} is gone. HAClient publishers and consumers connected to it will:"
    echo "  - notice within a heartbeat or two, redial the other instance and log on"
    echo "  - publisher: replay every message not yet acknowledged as persisted"
    echo "  - consumer:  resubscribe from its bookmark store's most recent position"
    echo "The survivor will not acknowledge publishes for a few seconds (its replication"
    echo "link to ${inst} is synchronous) until its scheduled action downgrades the link."
    echo
    echo "bring it back:  $0 revive ${inst}"
}

cmd_failover() {
    cmd_kill primary
}

cmd_revive() {
    local inst container before
    inst="$(instance_arg "${1:-}")"
    container="$(container_name "${inst}")"
    container_running "${container}" && die "${container} is already running"
    before="$(ready_count "${container}")"
    echo "starting ${container} again on its own data (${DEPLOY_DIR}/${inst})"
    "${ENGINE}" start "${container}" >/dev/null
    wait_for_ready "${container}" $(( before + 1 )) || true
    wait_for_replication "${container}" || true
    echo "${inst} is back; it recovers its SOW and journal from disk, then receives"
    echo "from its peer everything published while it was away. Replication lines:"
    replication_lines "${container}" 6
}

cmd_demo() {
    echo "running the Java demo against ${HA_PRIMARY_PORT} and ${HA_SECONDARY_PORT}; kill an instance while it runs:"
    echo "  $0 failover"
    (cd "${REPO_ROOT}" && ./gradlew :amps-ha-demo:run --args="${1:-both}" -q \
        "-Dha.uris=tcp://127.0.0.1:${HA_PRIMARY_PORT}/amps/json,tcp://127.0.0.1:${HA_SECONDARY_PORT}/amps/json")
}

# The server's own parser on both configs, the way server/scripts/amps.sh
# validate does it. Catches what an XML well-formedness check cannot: an
# element AMPS does not know, a topic replicated but not journaled.
cmd_validate() {
    require_amps_image
    local inst status=0
    for inst in primary secondary; do
        echo "--- ${AMPS_BIN} --verify-config config/${inst}/amps-config.xml ---"
        if "${ENGINE}" run --rm --platform "${AMPS_PLATFORM}" \
                -v "${MODULE_DIR}/config/${inst}:/amps/config${HA_MOUNT_SUFFIX}" \
                -w /amps/data --entrypoint "${AMPS_BIN}" "${AMPS_IMAGE}" \
                --verify-config /amps/config/amps-config.xml; then
            echo "${inst}: config accepted"
        else
            echo "${inst}: config REJECTED (see above)" >&2
            status=1
        fi
    done
    return "${status}"
}

cmd_reset() {
    ensure_compose
    "${COMPOSE[@]}" down >/dev/null 2>&1 || true
    echo "deleting ${DEPLOY_DIR}"
    rm -rf "${DEPLOY_DIR}"
    echo "reset: both instances' SOW, journal and stats are gone"
}

cmd_printenv() {
    local name
    for name in AMPS_IMAGE AMPS_PLATFORM AMPS_BIN HA_STACK HA_PRIMARY_PORT HA_PRIMARY_WS_PORT HA_PRIMARY_ADMIN_PORT \
                HA_SECONDARY_PORT HA_SECONDARY_WS_PORT HA_SECONDARY_ADMIN_PORT HA_MOUNT_SUFFIX; do
        echo "${name}=${!name:-}"
    done
    echo "engine=${ENGINE}"
    echo "compose=$(compose_command 2>/dev/null || echo '(none found)')"
    echo "deploy=${DEPLOY_DIR}"
}

usage() {
    awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
    start)     shift; cmd_start "$@" ;;
    stop)      shift; cmd_stop "$@" ;;
    down)      shift; cmd_down "$@" ;;
    restart)   shift; cmd_restart "$@" ;;
    status)    shift; cmd_status "$@" ;;
    logs)      shift; cmd_logs "$@" ;;
    failover)  shift; cmd_failover "$@" ;;
    kill)      shift; cmd_kill "$@" ;;
    revive)    shift; cmd_revive "$@" ;;
    demo)      shift; cmd_demo "$@" ;;
    validate)  shift; cmd_validate "$@" ;;
    reset)     shift; cmd_reset "$@" ;;
    printenv)  shift; cmd_printenv "$@" ;;
    ""|-h|--help|help) usage ;;
    *)         die "unknown command '$1' (try --help)" ;;
esac
