#!/usr/bin/env bash
#
# AMPS lifecycle via podman-compose / podman compose / docker compose, driven
# by two selectors and a layered .env tree instead of amps.sh's single
# AMPS_CONFIG filename.
#
#   ./server/scripts/amps-compose.sh start      up -d, then wait for readiness
#   ./server/scripts/amps-compose.sh stop       stop the container (data kept)
#   ./server/scripts/amps-compose.sh restart    down, then start again
#   ./server/scripts/amps-compose.sh down       stop and remove the container
#   ./server/scripts/amps-compose.sh status     compose ps
#   ./server/scripts/amps-compose.sh logs [-f]  compose logs
#   ./server/scripts/amps-compose.sh wait       block until ready
#   ./server/scripts/amps-compose.sh config     print the resolved compose YAML
#   ./server/scripts/amps-compose.sh printenv   print the merged AMPS_* values
#                                                and exit -- no container touched
#
# Selectors (environment variables, matching amps.sh's own style):
#   AMPS_ENV     which envs/ folder to layer in    (default: local)
#   AMPS_FLOW    which flows/ folder to run        (default: default)
#
#   AMPS_ENV=dev AMPS_FLOW=market-data ./server/scripts/amps-compose.sh start
#
# Four env-file layers are sourced in order, each overriding the keys of the
# one before it; a variable already exported in your shell overrides all four.
# See docs/src/server-env-layering.md for the full explanation:
#
#   1. server/config/default.env
#   2. server/config/envs/<AMPS_ENV>/<AMPS_ENV>.env
#   3. server/config/flows/<AMPS_FLOW>/flow.env
#   4. server/config/envs/<AMPS_ENV>/<AMPS_FLOW>.local.env   (optional, gitignored)
#
# AMPS_IMAGE is not defaulted by any layer -- there is no public AMPS server
# image; see server/Containerfile and server/scripts/amps.sh for how to build
# one. This script does not replace amps.sh: reach for amps.sh's
# probe/modules/validate for anything that inspects an image rather than runs
# it, and for a quick single-flow run with no env/flow ceremony.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_ROOT="${SERVER_DIR}/config"
COMPOSE_FILE="${SERVER_DIR}/docker-compose.yml"

die() { echo "error: $*" >&2; exit 1; }

AMPS_ENV="${AMPS_ENV:-local}"
AMPS_FLOW="${AMPS_FLOW:-default}"

ENV_DIR="${CONFIG_ROOT}/envs/${AMPS_ENV}"
FLOW_DIR="${CONFIG_ROOT}/flows/${AMPS_FLOW}"

[[ -f "${ENV_DIR}/${AMPS_ENV}.env" ]] \
    || die "no such environment '${AMPS_ENV}': expected ${ENV_DIR}/${AMPS_ENV}.env
available environments: $(ls "${CONFIG_ROOT}/envs" 2>/dev/null | tr '\n' ' ')"
[[ -f "${FLOW_DIR}/amps-config.xml" ]] \
    || die "no such flow '${AMPS_FLOW}': expected ${FLOW_DIR}/amps-config.xml
available flows: $(ls "${CONFIG_ROOT}/flows" 2>/dev/null | tr '\n' ' ')"

# --- layer the .env files -----------------------------------------------
#
# Files layer over each other in the order they are sourced (later wins), but
# anything the operator already exported before this script ran must win over
# every file, in either direction. Capture those names and values first, layer
# the files, then stamp the originals back on top.
declare -A PRESET=()
while IFS= read -r name; do
    PRESET["${name}"]="${!name}"
done < <(compgen -e)

load_layer() {
    local file="$1" required="$2"
    if [[ -f "${file}" ]]; then
        set -a
        # shellcheck disable=SC1090
        source "${file}"
        set +a
    elif [[ "${required}" == required ]]; then
        die "missing env layer: ${file}"
    fi
}

load_layer "${CONFIG_ROOT}/default.env"                       required
load_layer "${ENV_DIR}/${AMPS_ENV}.env"                       required
load_layer "${FLOW_DIR}/flow.env"                              required
load_layer "${ENV_DIR}/${AMPS_FLOW}.local.env"                 optional

for name in "${!PRESET[@]}"; do
    export "${name}=${PRESET[${name}]}"
done

# --- derived values, not layered: these come from AMPS_ENV/AMPS_FLOW rather
# than from a hand-edited file, so there is nothing to keep in sync when a
# flow or environment is added. ------------------------------------------
AMPS_CONTAINER_PREFIX="${AMPS_CONTAINER_PREFIX:-amps-demo}"
export AMPS_CONTAINER_NAME="${AMPS_CONTAINER_PREFIX}-${AMPS_ENV}-${AMPS_FLOW}"

DATA_DIR="${SERVER_DIR}/data/${AMPS_ENV}/${AMPS_FLOW}"
mkdir -p "${DATA_DIR}/sow" "${DATA_DIR}/journal" "${DATA_DIR}/stats"

# SELinux hosts need :z on bind mounts or the container cannot read them --
# same rule amps.sh applies to its own `podman run` mounts.
mount_suffix() { [[ "$(uname -s)" == "Linux" ]] && echo ":z" || echo ""; }
suffix="$(mount_suffix)"
export AMPS_CONFIG_VOLUME="${FLOW_DIR}:/amps/config${suffix}"
export AMPS_DATA_VOLUME="${DATA_DIR}:/amps/data${suffix}"

# --- which compose implementation is on this box? ------------------------
#
# podman >= 4 has a native `podman compose` subcommand that shells out to
# podman-compose or an internal implementation depending on the install;
# older podman needs the separate podman-compose package. docker's modern
# `docker compose` subcommand and the legacy docker-compose binary are the
# fallbacks, in that order, mirroring amps.sh's own podman-then-docker
# preference.
compose_command() {
    if command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
        echo "podman compose"; return
    fi
    if command -v podman-compose >/dev/null 2>&1; then
        echo "podman-compose"; return
    fi
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        echo "docker compose"; return
    fi
    if command -v docker-compose >/dev/null 2>&1; then
        echo "docker-compose"; return
    fi
    die "no compose implementation found on PATH (tried: podman compose, podman-compose, docker compose, docker-compose)"
}
# Resolved lazily, only by commands that actually shell out to compose --
# `printenv` and `--help` have to work even with no compose tool installed,
# since printenv's whole point is checking the layering before you have one.
COMPOSE=()
ensure_compose() {
    if [[ ${#COMPOSE[@]} -eq 0 ]]; then
        read -ra COMPOSE <<< "$(compose_command)"
        COMPOSE+=(-p "${AMPS_CONTAINER_NAME}" -f "${COMPOSE_FILE}")
    fi
}

cmd_start() {
    ensure_compose
    [[ -n "${AMPS_IMAGE:-}" ]] || die "AMPS_IMAGE is not set, and there is no public AMPS server image to default to.
60East distributes the server as a release tarball behind the evaluation
sign-up at https://www.crankuptheamps.com/evaluate/. Build one with
server/Containerfile, then export AMPS_IMAGE=<the tag you built> -- see
server/scripts/amps.sh for the exact build command."
    echo "starting ${AMPS_CONTAINER_NAME}"
    echo "  env:    ${AMPS_ENV}  (${ENV_DIR}/${AMPS_ENV}.env)"
    echo "  flow:   ${AMPS_FLOW}  (${FLOW_DIR}/amps-config.xml)"
    echo "  image:  ${AMPS_IMAGE}"
    echo "  ports:  ${AMPS_PORT}/${AMPS_WS_PORT}/${AMPS_ADMIN_PORT}"
    echo "  data:   ${DATA_DIR}"
    [[ -n "${AMPS_TZ:-}" ]] && echo "  tz:     ${AMPS_TZ}"
    "${COMPOSE[@]}" up -d
    echo
    echo "if the container exits immediately, the server binary is somewhere else"
    echo "in this image -- run './server/scripts/amps.sh probe' to find it, then set"
    echo "AMPS_BIN (in the relevant env/flow layer, or exported) to the path it reports."
    echo
    cmd_wait || true
    cmd_status
}

cmd_stop() {
    ensure_compose
    "${COMPOSE[@]}" stop
    echo "stopped (data in ${DATA_DIR} is untouched)"
}

cmd_down() {
    ensure_compose
    "${COMPOSE[@]}" down
    echo "removed (data in ${DATA_DIR} is untouched)"
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

# Same reasoning as amps.sh's cmd_wait: the port accepting a connection is not
# the same as AMPS having finished recovering the SOW and reconciling the
# journal, so wait for the port AND the startup-complete log line.
cmd_wait() {
    ensure_compose
    local timeout="${1:-60}"
    local port_open=0
    local deadline=$(( SECONDS + timeout ))
    local log
    echo -n "waiting for AMPS on port ${AMPS_PORT} "
    while (( SECONDS < deadline )); do
        if (exec 3<>"/dev/tcp/127.0.0.1/${AMPS_PORT}") 2>/dev/null; then
            exec 3<&- 2>/dev/null || true
            port_open=1
            log="$("${COMPOSE[@]}" logs --tail 400 2>&1 || true)"
            if [[ "${log}" == *"initialization completed"* ]]; then
                echo " ready"
                return 0
            fi
        fi
        echo -n "."
        sleep 1
    done
    if (( port_open )); then
        echo " port is open but no startup-complete line appeared in ${timeout}s"
        echo "  (continuing; if clients fail to connect, check 'amps-compose.sh logs')"
        return 0
    fi
    echo " timed out after ${timeout}s"
    return 1
}

cmd_config() {
    ensure_compose
    "${COMPOSE[@]}" config
}

# Prints what every layer resolved to, without starting anything -- the way
# to check the layering did what you expected before committing to `start`.
cmd_printenv() {
    echo "AMPS_ENV=${AMPS_ENV}      (${ENV_DIR}/${AMPS_ENV}.env)"
    echo "AMPS_FLOW=${AMPS_FLOW}    (${FLOW_DIR}/flow.env)"
    [[ -f "${ENV_DIR}/${AMPS_FLOW}.local.env" ]] \
        && echo "instance override: ${ENV_DIR}/${AMPS_FLOW}.local.env" \
        || echo "instance override: (none) ${ENV_DIR}/${AMPS_FLOW}.local.env"
    echo
    local name
    for name in AMPS_IMAGE AMPS_PLATFORM AMPS_BIN AMPS_PORT AMPS_WS_PORT \
                AMPS_ADMIN_PORT AMPS_TZ AMPS_CONTAINER_NAME \
                AMPS_CONFIG_VOLUME AMPS_DATA_VOLUME; do
        echo "${name}=${!name:-}"
    done
}

usage() {
    awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
    start)     shift; cmd_start "$@" ;;
    stop)      shift; cmd_stop "$@" ;;
    restart)   shift; cmd_restart "$@" ;;
    down)      shift; cmd_down "$@" ;;
    status)    shift; cmd_status "$@" ;;
    logs)      shift; cmd_logs "$@" ;;
    wait)      shift; cmd_wait "$@" ;;
    config)    shift; cmd_config "$@" ;;
    printenv)  shift; cmd_printenv "$@" ;;
    ""|-h|--help|help) usage ;;
    *)         die "unknown command '$1' (try --help)" ;;
esac
