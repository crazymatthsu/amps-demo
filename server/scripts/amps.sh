#!/usr/bin/env bash
#
# Lifecycle for the AMPS demo instance.
#
#   ./server/scripts/amps.sh start      run the instance
#   ./server/scripts/amps.sh stop       stop and remove the container
#   ./server/scripts/amps.sh restart    stop, then start again on the same data
#                                       (this is the recovery demo: SOW and
#                                       journal survive because they live in a
#                                       bind-mounted host directory)
#   ./server/scripts/amps.sh status     is it up, and on which ports
#   ./server/scripts/amps.sh logs [-f]  server log
#   ./server/scripts/amps.sh wait       block until the instance accepts clients
#   ./server/scripts/amps.sh reset      stop and DELETE all SOW/journal data
#   ./server/scripts/amps.sh probe      inspect the image: where is ampServer?
#   ./server/scripts/amps.sh validate [config.xml]
#                                       run the server's own config check
#
# Environment overrides:
#   CONTAINER_ENGINE   podman | docker            (default: podman, else docker)
#   AMPS_IMAGE         image reference            (default: docker.io/amps/ce:latest)
#   AMPS_BIN           server binary in the image (default: /opt/amps/bin/ampServer)
#   AMPS_CONFIG        config file name under server/config/
#   AMPS_PORT          host port for the amps protocol      (default: 9007)
#   AMPS_WS_PORT       host port for websocket              (default: 9008)
#   AMPS_ADMIN_PORT    host port for the admin interface    (default: 8085)
#   AMPS_CONTAINER     container name             (default: amps-demo)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_DIR="${SERVER_DIR}/config"
DATA_DIR="${SERVER_DIR}/data"

AMPS_IMAGE="${AMPS_IMAGE:-docker.io/amps/ce:latest}"
AMPS_BIN="${AMPS_BIN:-/opt/amps/bin/ampServer}"
AMPS_CONFIG="${AMPS_CONFIG:-amps-config.xml}"
AMPS_PORT="${AMPS_PORT:-9007}"
AMPS_WS_PORT="${AMPS_WS_PORT:-9008}"
AMPS_ADMIN_PORT="${AMPS_ADMIN_PORT:-8085}"
AMPS_CONTAINER="${AMPS_CONTAINER:-amps-demo}"

# Container paths.
CONTAINER_CONFIG_DIR=/amps/config
CONTAINER_DATA_DIR=/amps/data

die() { echo "error: $*" >&2; exit 1; }

engine() {
    if [[ -n "${CONTAINER_ENGINE:-}" ]]; then
        command -v "${CONTAINER_ENGINE}" >/dev/null 2>&1 \
            || die "CONTAINER_ENGINE=${CONTAINER_ENGINE} is not on PATH"
        echo "${CONTAINER_ENGINE}"
        return
    fi
    # podman is the target runtime for this demo; docker works identically for
    # everything the script does.
    if command -v podman >/dev/null 2>&1; then
        echo podman
    elif command -v docker >/dev/null 2>&1; then
        echo docker
    else
        die "neither podman nor docker found on PATH"
    fi
}

ENGINE="$(engine)"

# SELinux hosts need :z on bind mounts or the container cannot read them. Harmless
# elsewhere, but only podman and docker on Linux understand it.
mount_suffix() {
    if [[ "$(uname -s)" == "Linux" ]]; then echo ":z"; else echo ""; fi
}

container_exists() {
    "${ENGINE}" ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "${AMPS_CONTAINER}"
}

container_running() {
    "${ENGINE}" ps --format '{{.Names}}' 2>/dev/null | grep -qx "${AMPS_CONTAINER}"
}

cmd_start() {
    [[ -f "${CONFIG_DIR}/${AMPS_CONFIG}" ]] \
        || die "no such config: ${CONFIG_DIR}/${AMPS_CONFIG}"

    if container_running; then
        echo "already running: ${AMPS_CONTAINER}"
        cmd_status
        return
    fi
    if container_exists; then
        echo "removing stopped container ${AMPS_CONTAINER}"
        "${ENGINE}" rm "${AMPS_CONTAINER}" >/dev/null
    fi

    # These directories are the whole point of the recovery demos: they live on
    # the host, so `restart` and even `stop`/`start` of a fresh container find
    # the previous instance's state exactly where it was left.
    mkdir -p "${DATA_DIR}/sow" "${DATA_DIR}/journal" "${DATA_DIR}/stats"

    local suffix
    suffix="$(mount_suffix)"

    echo "starting ${AMPS_CONTAINER}"
    echo "  engine: ${ENGINE}"
    echo "  image:  ${AMPS_IMAGE}"
    echo "  config: ${AMPS_CONFIG}"
    echo "  data:   ${DATA_DIR}"

    "${ENGINE}" run -d \
        --name "${AMPS_CONTAINER}" \
        -p "${AMPS_PORT}:9007" \
        -p "${AMPS_WS_PORT}:9008" \
        -p "${AMPS_ADMIN_PORT}:8085" \
        -v "${CONFIG_DIR}:${CONTAINER_CONFIG_DIR}${suffix}" \
        -v "${DATA_DIR}:${CONTAINER_DATA_DIR}${suffix}" \
        -w "${CONTAINER_DATA_DIR}" \
        --entrypoint "${AMPS_BIN}" \
        "${AMPS_IMAGE}" \
        "${CONTAINER_CONFIG_DIR}/${AMPS_CONFIG}" >/dev/null

    echo
    echo "if the container exits immediately, the server binary is somewhere else"
    echo "in this image. Run './server/scripts/amps.sh probe' to find it, then set"
    echo "AMPS_BIN to the path it reports."
    echo
    cmd_wait || true
    cmd_status
}

cmd_stop() {
    if container_running; then
        echo "stopping ${AMPS_CONTAINER}"
        "${ENGINE}" stop "${AMPS_CONTAINER}" >/dev/null
    fi
    if container_exists; then
        "${ENGINE}" rm "${AMPS_CONTAINER}" >/dev/null
    fi
    echo "stopped (data in ${DATA_DIR} is untouched)"
}

cmd_restart() {
    cmd_stop
    cmd_start
}

cmd_status() {
    if container_running; then
        echo "AMPS is running:"
        echo "  amps/json   tcp://127.0.0.1:${AMPS_PORT}/amps/json"
        echo "  websocket   ws://127.0.0.1:${AMPS_WS_PORT}/amps/json"
        echo "  admin UI    http://127.0.0.1:${AMPS_ADMIN_PORT}/"
        echo "  data        ${DATA_DIR}"
    else
        echo "AMPS is not running"
        if container_exists; then
            echo "(a stopped container named ${AMPS_CONTAINER} exists; last log lines:)"
            "${ENGINE}" logs --tail 20 "${AMPS_CONTAINER}" 2>&1 | sed 's/^/  /'
        fi
        return 1
    fi
}

cmd_logs() {
    container_exists || die "no container named ${AMPS_CONTAINER}"
    "${ENGINE}" logs "$@" "${AMPS_CONTAINER}"
}

# Blocks until the amps port accepts a TCP connection, or times out.
cmd_wait() {
    local timeout="${1:-30}"
    local waited=0
    echo -n "waiting for AMPS on port ${AMPS_PORT} "
    while (( waited < timeout )); do
        if (exec 3<>"/dev/tcp/127.0.0.1/${AMPS_PORT}") 2>/dev/null; then
            exec 3<&- 2>/dev/null || true
            echo " ready"
            return 0
        fi
        if container_exists && ! container_running; then
            echo " container exited"
            "${ENGINE}" logs --tail 30 "${AMPS_CONTAINER}" 2>&1 | sed 's/^/  /'
            return 1
        fi
        echo -n "."
        sleep 1
        waited=$(( waited + 1 ))
    done
    echo " timed out after ${timeout}s"
    return 1
}

cmd_reset() {
    cmd_stop
    echo "deleting ${DATA_DIR}"
    rm -rf "${DATA_DIR}"
    echo "next start begins from an empty SOW and an empty transaction log"
}

# The public amps/ce image dates from 2017 and other builds lay the distribution
# out differently, so rather than guess, look.
cmd_probe() {
    echo "image: ${AMPS_IMAGE}"
    echo
    echo "--- declared entrypoint / cmd ---"
    "${ENGINE}" inspect --format 'Entrypoint: {{.Config.Entrypoint}}{{"\n"}}Cmd:        {{.Config.Cmd}}{{"\n"}}WorkingDir: {{.Config.WorkingDir}}' \
        "${AMPS_IMAGE}" 2>/dev/null || echo "(image not pulled yet: ${ENGINE} pull ${AMPS_IMAGE})"
    echo
    echo "--- searching for the server binary ---"
    "${ENGINE}" run --rm --entrypoint sh "${AMPS_IMAGE}" -c \
        'find / -maxdepth 5 -type f \( -name "ampServer" -o -name "amps_server" \) 2>/dev/null; echo "--- /opt ---"; ls -1 /opt 2>/dev/null' \
        || echo "(could not run a shell in this image)"
    echo
    echo "set AMPS_BIN to whichever path this printed."
}

# Ask the server to parse a config without starting the instance. The flag name
# has varied; try the ones AMPS has used and report whichever answers.
cmd_validate() {
    local config="${1:-${AMPS_CONFIG}}"
    [[ -f "${CONFIG_DIR}/${config}" ]] || die "no such config: ${CONFIG_DIR}/${config}"

    local suffix
    suffix="$(mount_suffix)"

    for flag in --test-config -t; do
        echo "--- ${AMPS_BIN} ${flag} ${config} ---"
        if "${ENGINE}" run --rm \
                -v "${CONFIG_DIR}:${CONTAINER_CONFIG_DIR}${suffix}" \
                -w "${CONTAINER_DATA_DIR}" \
                --entrypoint "${AMPS_BIN}" \
                "${AMPS_IMAGE}" "${flag}" "${CONTAINER_CONFIG_DIR}/${config}"; then
            echo "config accepted"
            return 0
        fi
        echo
    done
    echo "neither flag was accepted; check 'ampServer --help' in your AMPS version" >&2
    return 1
}

usage() {
    # Print the header comment block: every line from the second until the first
    # line that is not a comment.
    awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
    start)    shift; cmd_start "$@" ;;
    stop)     shift; cmd_stop "$@" ;;
    restart)  shift; cmd_restart "$@" ;;
    status)   shift; cmd_status "$@" ;;
    logs)     shift; cmd_logs "$@" ;;
    wait)     shift; cmd_wait "$@" ;;
    reset)    shift; cmd_reset "$@" ;;
    probe)    shift; cmd_probe "$@" ;;
    validate) shift; cmd_validate "$@" ;;
    ""|-h|--help|help) usage ;;
    *)        die "unknown command '$1' (try --help)" ;;
esac
