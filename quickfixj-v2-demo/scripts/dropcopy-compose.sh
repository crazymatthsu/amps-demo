#!/usr/bin/env bash
#
# The quickfixj-v2-demo stack -- AMPS, the venue engine, the drop-copy
# engine -- via podman compose (or docker compose), with the bind-mounted
# config/, data/ and log/ folders created for you.
#
#   scripts/dropcopy-compose.sh build       bootJar, then build the engine image (QFJ_IMAGE)
#   scripts/dropcopy-compose.sh start       create the folders, start AMPS, wait for it, start both engines
#   scripts/dropcopy-compose.sh stop        stop everything (state kept)
#   scripts/dropcopy-compose.sh down        stop and remove the containers (state kept)
#   scripts/dropcopy-compose.sh restart     down, then start
#   scripts/dropcopy-compose.sh status      compose ps
#   scripts/dropcopy-compose.sh logs [svc] [-f]
#   scripts/dropcopy-compose.sh failover    THE DEMO: stop the drop-copy engine and start
#                                           it again on an EMPTY store directory (the DR
#                                           box's disk), leaving the primary's store in
#                                           place -- the replacement recovers its sequence
#                                           numbers from AMPS and logs on with no resend
#   scripts/dropcopy-compose.sh seqno <venue|dropcopy> <action> [--seqno.sender=N] [--seqno.target=M] [--seqno.session=ID]
#                                           the resequencing tool, in a one-off container
#                                           on the same mounts: show | set-file | set-amps |
#                                           file-to-amps | amps-to-file. Stop the engine
#                                           first for the file actions.
#   scripts/dropcopy-compose.sh reset       down, then DELETE deploy/ -- every store, log
#                                           and the AMPS instance's own state
#   scripts/dropcopy-compose.sh printenv    show what the variables resolved to
#
# Environment:
#   AMPS_IMAGE           REQUIRED for start: an image built from server/Containerfile
#   AMPS_PLATFORM        default linux/amd64 (the AMPS distribution is x86_64 only)
#   AMPS_BIN             the server binary in the image (default /opt/amps/bin/ampServer)
#   QFJ_IMAGE            the engine image (default localhost/quickfixj-v2-demo:latest)
#   QFJ_STACK            compose project / container-name prefix (default qfj2)
#   QFJ_AMPS_PORT, QFJ_AMPS_WS_PORT, QFJ_AMPS_ADMIN_PORT   host ports (9007 / 9008 / 8085)
#   QFJ_VENUE_PORT       host port for the venue's FIX acceptor (default 9876)
#   QFJ_MOCK_INTERVAL_MS, QFJ_MOCK_COUNT   the venue's feed (2000 ms, 0 = forever)
#   CONTAINER_ENGINE     podman | docker (default: podman if present, else docker)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
COMPOSE_FILE="${MODULE_DIR}/compose.yml"
DEPLOY_DIR="${MODULE_DIR}/deploy"

die() { echo "error: $*" >&2; exit 1; }

export QFJ_STACK="${QFJ_STACK:-qfj2}"
export QFJ_IMAGE="${QFJ_IMAGE:-localhost/quickfixj-v2-demo:latest}"
export AMPS_PLATFORM="${AMPS_PLATFORM:-linux/amd64}"
export AMPS_BIN="${AMPS_BIN:-/opt/amps/bin/ampServer}"
export QFJ_AMPS_PORT="${QFJ_AMPS_PORT:-9007}"
export QFJ_AMPS_WS_PORT="${QFJ_AMPS_WS_PORT:-9008}"
export QFJ_AMPS_ADMIN_PORT="${QFJ_AMPS_ADMIN_PORT:-8085}"
export QFJ_VENUE_PORT="${QFJ_VENUE_PORT:-9876}"

# SELinux hosts need :z on bind mounts or the container cannot read them --
# the same rule server/scripts/amps.sh applies. macOS and plain Linux do not.
if [[ "$(uname -s)" == "Linux" ]] && command -v getenforce >/dev/null 2>&1 \
        && [[ "$(getenforce 2>/dev/null)" != "Disabled" ]]; then
    export QFJ_MOUNT_SUFFIX=":z"
    export QFJ_MOUNT_RO_SUFFIX=":ro,z"
else
    export QFJ_MOUNT_SUFFIX=""
    export QFJ_MOUNT_RO_SUFFIX=":ro"
fi

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
        COMPOSE+=(-p "${QFJ_STACK}" -f "${COMPOSE_FILE}")
    fi
}

require_amps_image() {
    [[ -n "${AMPS_IMAGE:-}" ]] || die "AMPS_IMAGE is not set, and there is no public AMPS server image to default to.
Build one from server/Containerfile (see server/scripts/amps.sh), then export AMPS_IMAGE=<the tag>."
}

require_engine_image() {
    "${ENGINE}" image exists "${QFJ_IMAGE}" 2>/dev/null \
        || die "engine image ${QFJ_IMAGE} not found -- run '$0 build' first"
}

prepare_dirs() {
    mkdir -p "${DEPLOY_DIR}/amps/sow" "${DEPLOY_DIR}/amps/journal" "${DEPLOY_DIR}/amps/stats" \
             "${DEPLOY_DIR}/venue/data" "${DEPLOY_DIR}/venue/log" \
             "${DEPLOY_DIR}/dropcopy/data" "${DEPLOY_DIR}/dropcopy/log"
}

# The consumer's store directory (under /app/data, i.e. deploy/dropcopy/data).
# `failover` moves the consumer onto a fresh one and records it here, so every
# later command -- start, seqno -- points the container at the same store.
# Not a rename of the old store: under podman machine a container started
# right after a rename can still be handed the old directory entry.
STORE_DIR_FILE="${DEPLOY_DIR}/dropcopy/.store-dir"
dropcopy_store_dir() {
    if [[ -f "${STORE_DIR_FILE}" ]]; then cat "${STORE_DIR_FILE}"; else echo "data/store"; fi
}
export QFJ_DROPCOPY_STORE_DIR
QFJ_DROPCOPY_STORE_DIR="$(dropcopy_store_dir)"

container_running() {
    [[ "$("${ENGINE}" inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" == "true" ]]
}

# Readiness is the server's own log line, not an open port: the port
# forwarder accepts connections before AMPS has finished recovering.
wait_for_amps() {
    local timeout="${1:-120}" deadline=$(( SECONDS + ${1:-120} ))
    echo -n "waiting for AMPS (${QFJ_STACK}-amps) "
    while (( SECONDS < deadline )); do
        # grep reads to the end on purpose: with pipefail, a `grep -q` that exits
        # early hands the log command a SIGPIPE and the match reads as a failure.
        if "${ENGINE}" logs "${QFJ_STACK}-amps" 2>&1 | grep "initialization completed" >/dev/null; then
            echo " ready"
            return 0
        fi
        if ! container_running "${QFJ_STACK}-amps"; then
            echo
            "${ENGINE}" logs "${QFJ_STACK}-amps" 2>&1 | tail -20
            die "the AMPS container exited; see the log above"
        fi
        echo -n "."
        sleep 2
    done
    echo " not ready after ${timeout}s"
    return 1
}

wait_for_recovery_line() {
    local container="$1" deadline=$(( SECONDS + 90 ))
    echo -n "waiting for ${container} to recover its sequence numbers "
    while (( SECONDS < deadline )); do
        if "${ENGINE}" logs "${container}" 2>&1 | grep "seqno recovery for" >/dev/null; then
            echo
            "${ENGINE}" logs "${container}" 2>&1 | grep -E "seqno recovery for|logged on|Logon|MsgSeqNum too low|ResendRequest|35=2" | tail -8
            return 0
        fi
        echo -n "."
        sleep 2
    done
    echo " no recovery line in 90s; check '$0 logs dropcopy'"
    return 1
}

cmd_build() {
    echo "building the jar"
    (cd "${REPO_ROOT}" && ./gradlew :quickfixj-v2-demo:bootJar -q)
    echo "building ${QFJ_IMAGE}"
    "${ENGINE}" build -f "${MODULE_DIR}/Containerfile" -t "${QFJ_IMAGE}" "${MODULE_DIR}"
}

cmd_start() {
    ensure_compose
    require_amps_image
    require_engine_image
    prepare_dirs
    echo "starting stack ${QFJ_STACK}"
    echo "  AMPS:      ${AMPS_IMAGE} on ${QFJ_AMPS_PORT}/${QFJ_AMPS_WS_PORT}/${QFJ_AMPS_ADMIN_PORT}, data ${DEPLOY_DIR}/amps"
    echo "  engines:   ${QFJ_IMAGE}"
    echo "  venue:     FIX acceptor on ${QFJ_VENUE_PORT}, data ${DEPLOY_DIR}/venue"
    echo "  dropcopy:  FIX initiator -> venue,  data ${DEPLOY_DIR}/dropcopy (store ${QFJ_DROPCOPY_STORE_DIR})"
    "${COMPOSE[@]}" up -d amps
    wait_for_amps || true
    "${COMPOSE[@]}" up -d venue dropcopy
    echo
    cmd_status
    echo
    echo "watch it:   $0 logs dropcopy -f"
    echo "fail over:  $0 failover"
}

# Engines first, AMPS last: a stopping engine records its logout and
# replicates that final checkpoint, which it cannot do once AMPS is gone --
# and then the next start finds the file AHEAD of AMPS.
stop_engines_first() {
    "${COMPOSE[@]}" stop dropcopy venue
}

cmd_stop() {
    ensure_compose
    stop_engines_first
    "${COMPOSE[@]}" stop amps
    echo "stopped (state in ${DEPLOY_DIR} is untouched)"
}

cmd_down() {
    ensure_compose
    stop_engines_first 2>/dev/null || true
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
}

cmd_logs() {
    ensure_compose
    "${COMPOSE[@]}" logs "$@"
}

# The failover demo. The drop-copy engine is stopped and REMOVED (so it is
# recreated with the DR source tag) and a new instance started on an EMPTY
# store directory -- the DR box's disk -- while the primary's store stays
# where it is. The recovery line the replacement logs is the point.
#
# A fresh directory rather than a rename of the old one: under podman machine
# (macOS) a container started right after a rename on the host can still be
# handed the old directory entry, and the "empty" disk quietly is not.
cmd_failover() {
    ensure_compose
    require_amps_image
    local container="${QFJ_STACK}-dropcopy"
    local old_store new_store stamp
    old_store="$(dropcopy_store_dir)"
    stamp="$(date +%Y%m%d-%H%M%S)"
    new_store="data/store-dr-${stamp}"
    echo "1. stopping the primary drop-copy engine (${container})"
    "${ENGINE}" rm -f "${container}" >/dev/null 2>&1 || true
    local old_files=0
    [[ -d "${DEPLOY_DIR}/dropcopy/${old_store}" ]] \
        && old_files="$(ls "${DEPLOY_DIR}/dropcopy/${old_store}" | wc -l | tr -d ' ')"
    echo "2. its disk is lost: the replacement gets an empty store, ${new_store}"
    echo "   (the primary's ${old_store}, ${old_files} files, is left in place as evidence)"
    mkdir -p "${DEPLOY_DIR}/dropcopy/${new_store}"
    echo "${new_store}" > "${STORE_DIR_FILE}"
    QFJ_DROPCOPY_STORE_DIR="${new_store}"
    echo "3. starting the DR instance (source tag: dropcopy-dr)"
    QFJ_DROPCOPY_SOURCE=dropcopy-dr "${COMPOSE[@]}" up -d --no-deps dropcopy
    wait_for_recovery_line "${container}" || true
    echo
    echo "compare with the checkpoint AMPS holds:  $0 seqno dropcopy show"
}

cmd_seqno() {
    ensure_compose
    local role="${1:-}" action="${2:-}"
    [[ "${role}" == venue || "${role}" == dropcopy ]] \
        || die "usage: $0 seqno <venue|dropcopy> <show|set-file|set-amps|file-to-amps|amps-to-file> [--seqno.sender=N] [--seqno.target=M] [--seqno.session=ID]"
    [[ -n "${action}" ]] || die "which action? show | set-file | set-amps | file-to-amps | amps-to-file"
    shift 2
    prepare_dirs
    if container_running "${QFJ_STACK}-${role}" && [[ "${action}" != show && "${action}" != set-amps ]]; then
        die "the ${role} engine is running; it holds the file store open and caches the numbers, so stop it first:
  $0 stop    (or: ${ENGINE} stop ${QFJ_STACK}-${role})"
    fi
    # A name of its own: the service has a fixed container_name, and a one-off
    # run must not collide with the engine that may be using it.
    "${COMPOSE[@]}" run --rm --no-deps --name "${QFJ_STACK}-${role}-seqno-$$" "${role}" \
        "--spring.config.additional-location=file:/app/config/${role}/${role}.yml" \
        "--spring.profiles.active=seqno-admin" \
        "--seqno.action=${action}" "$@"
}

cmd_reset() {
    ensure_compose
    "${COMPOSE[@]}" down >/dev/null 2>&1 || true
    echo "deleting ${DEPLOY_DIR}"
    rm -rf "${DEPLOY_DIR}"
    echo "reset: every store, log and the AMPS state are gone"
}

cmd_printenv() {
    local name
    for name in AMPS_IMAGE AMPS_PLATFORM AMPS_BIN QFJ_IMAGE QFJ_STACK QFJ_AMPS_PORT QFJ_AMPS_WS_PORT \
                QFJ_AMPS_ADMIN_PORT QFJ_VENUE_PORT QFJ_DROPCOPY_STORE_DIR QFJ_MOUNT_SUFFIX QFJ_MOUNT_RO_SUFFIX; do
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
    build)     shift; cmd_build "$@" ;;
    start)     shift; cmd_start "$@" ;;
    stop)      shift; cmd_stop "$@" ;;
    down)      shift; cmd_down "$@" ;;
    restart)   shift; cmd_restart "$@" ;;
    status)    shift; cmd_status "$@" ;;
    logs)      shift; cmd_logs "$@" ;;
    failover)  shift; cmd_failover "$@" ;;
    seqno)     shift; cmd_seqno "$@" ;;
    reset)     shift; cmd_reset "$@" ;;
    printenv)  shift; cmd_printenv "$@" ;;
    ""|-h|--help|help) usage ;;
    *)         die "unknown command '$1' (try --help)" ;;
esac
