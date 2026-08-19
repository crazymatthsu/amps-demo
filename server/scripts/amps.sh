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
#   ./server/scripts/amps.sh validate [flow]
#                                       run the server's own config check
#   ./server/scripts/amps.sh modules    list the action modules this build has
#   ./server/scripts/amps.sh flows      list the business flows under server/config/flows/
#
# Environment overrides:
#   CONTAINER_ENGINE   podman | docker            (default: podman, else docker)
#   AMPS_IMAGE         image reference            (REQUIRED - see below)
#   AMPS_PLATFORM      image platform             (default: linux/amd64; set
#                                                  empty to let the engine pick)
#   AMPS_BIN           server binary in the image (default: /opt/amps/bin/ampServer)
#   AMPS_FLOW          business flow: a folder under server/config/flows/
#                                                  (default: default). Each flow folder holds
#                                                  exactly one amps-config.xml. 'amps.sh flows'
#                                                  lists what is available.
#   AMPS_PORT          host port for the amps protocol      (default: 9007)
#   AMPS_WS_PORT       host port for websocket              (default: 9008)
#   AMPS_ADMIN_PORT    host port for the admin interface    (default: 8085)
#   AMPS_CONTAINER     container name             (default: amps-demo)
#   AMPS_TZ            server timezone            (default: the image's, UTC)
#                                                  scheduled <Actions> fire on
#                                                  the server clock, so set this
#                                                  if a schedule must mean local
#                                                  time
#
# There is no public AMPS server image. 60East distributes the server as a
# release tarball behind the evaluation sign-up at crankuptheamps.com; build an
# image from it with server/Containerfile and point AMPS_IMAGE at the result:
#
#   podman build --platform linux/amd64 -f server/Containerfile -t amps-demo:5.3 \
#       --build-arg AMPS_TARBALL=<exact-filename>.tar.gz server
#   export AMPS_IMAGE=amps-demo:5.3
#
# (docker.io/amps/ce is NOT this AMPS. It is an unrelated Apache/MySQL/PHP
# product that shares the acronym - it contains no ampServer binary.)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_DIR="${SERVER_DIR}/config"
DATA_DIR="${SERVER_DIR}/data"

AMPS_IMAGE="${AMPS_IMAGE:-}"
# The AMPS server distribution is Linux x86_64 only, so the image is always
# amd64 - on Apple Silicon that means emulation, which podman's machine provides.
# Harmless on an x86_64 host. Set AMPS_PLATFORM= (empty) to let the engine pick.
AMPS_PLATFORM="${AMPS_PLATFORM-linux/amd64}"
AMPS_BIN="${AMPS_BIN:-/opt/amps/bin/ampServer}"
AMPS_FLOW="${AMPS_FLOW:-default}"
FLOWS_DIR="${CONFIG_DIR}/flows"
AMPS_PORT="${AMPS_PORT:-9007}"
AMPS_WS_PORT="${AMPS_WS_PORT:-9008}"
AMPS_ADMIN_PORT="${AMPS_ADMIN_PORT:-8085}"
AMPS_CONTAINER="${AMPS_CONTAINER:-amps-demo}"
# Scheduled actions (see amps-config-maintenance.xml) fire on the server's
# clock, and the container's clock is UTC unless TZ says otherwise - so
# "every night at 20:30" means 20:30 UTC by default. Empty leaves the image
# as it is.
AMPS_TZ="${AMPS_TZ:-}"

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

# macOS ships bash 3.2, where "${arr[@]}" on an empty array trips `set -u`;
# ${arr[@]+"${arr[@]}"} is the portable way to expand "zero or more args".
PLATFORM_ARGS=()
if [[ -n "${AMPS_PLATFORM}" ]]; then
    PLATFORM_ARGS=(--platform "${AMPS_PLATFORM}")
fi

TZ_ARGS=()
if [[ -n "${AMPS_TZ}" ]]; then
    TZ_ARGS=(-e "TZ=${AMPS_TZ}")
fi

# Every command that runs a container needs a real AMPS image, and there is no
# public one to fall back to. Checked in one place so the guidance is identical
# wherever you hit it.
require_image() {
    [[ -n "${AMPS_IMAGE}" ]] || die "AMPS_IMAGE is not set, and there is no public AMPS server image to default to.

60East distributes the server as a release tarball behind the evaluation
sign-up at https://www.crankuptheamps.com/evaluate/. Once you have it:

  cp AMPS-<version>-Release-Linux.tar.gz server/vendor/
  podman build --platform linux/amd64 -f server/Containerfile -t amps-demo:5.3 \\
      --build-arg AMPS_TARBALL=AMPS-<version>-Release-Linux.tar.gz server
  export AMPS_IMAGE=amps-demo:5.3"

    case "${AMPS_IMAGE}" in
        amps/ce*|amps/pro*|docker.io/amps/ce*|docker.io/amps/pro*)
            die "${AMPS_IMAGE} is not 60East AMPS.

The 'amps' account on Docker Hub publishes an unrelated Apache/MySQL/PHP
product that shares the acronym; the image contains no ampServer binary, so
every command here would fail in a confusing way. Build an image from an
official release tarball instead - see server/Containerfile."
            ;;
    esac
}

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
    require_image
    local flow_dir="${FLOWS_DIR}/${AMPS_FLOW}"
    [[ -f "${flow_dir}/amps-config.xml" ]] \
        || die "no such flow '${AMPS_FLOW}': expected ${flow_dir}/amps-config.xml
available flows: $(ls "${FLOWS_DIR}" 2>/dev/null | tr '\n' ' ')"

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
    echo "  flow:   ${AMPS_FLOW}  (${flow_dir}/amps-config.xml)"
    echo "  data:   ${DATA_DIR}"
    [[ -n "${AMPS_TZ}" ]] && echo "  tz:     ${AMPS_TZ}"

    "${ENGINE}" run -d \
        ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
        ${TZ_ARGS[@]+"${TZ_ARGS[@]}"} \
        --name "${AMPS_CONTAINER}" \
        -p "${AMPS_PORT}:9007" \
        -p "${AMPS_WS_PORT}:9008" \
        -p "${AMPS_ADMIN_PORT}:8085" \
        -v "${flow_dir}:${CONTAINER_CONFIG_DIR}${suffix}" \
        -v "${DATA_DIR}:${CONTAINER_DATA_DIR}${suffix}" \
        -w "${CONTAINER_DATA_DIR}" \
        --entrypoint "${AMPS_BIN}" \
        "${AMPS_IMAGE}" \
        "${CONTAINER_CONFIG_DIR}/amps-config.xml" >/dev/null

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
        # The clock a scheduled <Action> fires on. Worth printing: it is the
        # server's, not yours, and by default it is UTC.
        local server_now
        server_now="$("${ENGINE}" exec "${AMPS_CONTAINER}" date 2>/dev/null || true)"
        [[ -n "${server_now}" ]] && echo "  clock       ${server_now} (server)"
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

# Blocks until AMPS is actually ready to serve clients, or times out.
#
# "The port accepts a connection" is NOT ready. AMPS binds its transports early
# and finishes initialising afterwards - recovering the SOW and reconciling the
# journal, which on a restart with real data takes seconds. A client that
# connects in that window gets "Socket error while sending message", which is
# what made the recovery demo fail intermittently. So wait for the port AND for
# the server to say it has finished starting up.
cmd_wait() {
    local timeout="${1:-60}"
    local port_open=0
    local deadline=$(( SECONDS + timeout ))
    local log
    echo -n "waiting for AMPS on port ${AMPS_PORT} "
    # Deadline is wall-clock, not an iteration count: each pass shells out to the
    # container engine and is nowhere near a fixed 1s.
    while (( SECONDS < deadline )); do
        if (exec 3<>"/dev/tcp/127.0.0.1/${AMPS_PORT}") 2>/dev/null; then
            exec 3<&- 2>/dev/null || true
            port_open=1
            # Capture, then match with a glob. Piping into `grep -q` under
            # `set -o pipefail` reports failure even on a match: grep exits at
            # the first hit and the engine dies of SIGPIPE, so the pipeline
            # status is 141 and the check never passes.
            log="$("${ENGINE}" logs --tail 400 "${AMPS_CONTAINER}" 2>&1 || true)"
            if [[ "${log}" == *"initialization completed"* ]]; then
                echo " ready"
                return 0
            fi
        fi
        if container_exists && ! container_running; then
            echo " container exited"
            "${ENGINE}" logs --tail 30 "${AMPS_CONTAINER}" 2>&1 | sed 's/^/  /'
            return 1
        fi
        echo -n "."
        sleep 1
    done

    # Never saw the marker. If the port is open the instance is probably fine
    # and this build just words its startup line differently - say so rather
    # than fail, but do not claim it is ready.
    if (( port_open )); then
        echo " port is open but no startup-complete line appeared in ${timeout}s"
        echo "  (continuing; if clients fail to connect, check 'amps.sh logs')"
        return 0
    fi
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
    require_image
    echo "image: ${AMPS_IMAGE}"
    echo
    echo "--- declared entrypoint / cmd ---"
    "${ENGINE}" inspect --format 'Entrypoint: {{.Config.Entrypoint}}{{"\n"}}Cmd:        {{.Config.Cmd}}{{"\n"}}WorkingDir: {{.Config.WorkingDir}}' \
        "${AMPS_IMAGE}" 2>/dev/null || echo "(image not pulled yet: ${ENGINE} pull ${AMPS_IMAGE})"
    echo
    echo "--- searching for the server binary ---"
    "${ENGINE}" run --rm ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
        --entrypoint sh "${AMPS_IMAGE}" -c \
        'find / -maxdepth 5 -type f \( -name "ampServer" -o -name "amps_server" \) 2>/dev/null; echo "--- /opt ---"; ls -1 /opt 2>/dev/null' \
        || echo "(could not run a shell in this image)"
    echo
    echo "set AMPS_BIN to whichever path this printed."
}

# Which flag makes this build parse a config and exit?
#
# Do NOT guess by trying flags in turn. ampServer ignores an option it does not
# recognise and falls through to "start the instance with this config", so a
# wrong guess does not fail - it launches a server that runs until something
# kills it. Read the advertised options instead and use what is actually there.
# (5.3.x: --verify-config. Older builds documented --test-config.)
validate_flag() {
    # 2>&1: ampServer prints its usage on stderr.
    local help
    help="$("${ENGINE}" run --rm ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
        --entrypoint "${AMPS_BIN}" "${AMPS_IMAGE}" --help 2>&1 || true)"

    local candidate
    for candidate in --verify-config --test-config; do
        if echo "${help}" | grep -q -- "${candidate}"; then
            echo "${candidate}"
            return 0
        fi
    done
    return 1
}

# Ask the server to parse a config without starting the instance.
cmd_validate() {
    require_image
    local flow="${1:-${AMPS_FLOW}}"
    local flow_dir="${FLOWS_DIR}/${flow}"
    [[ -f "${flow_dir}/amps-config.xml" ]] \
        || die "no such flow '${flow}': expected ${flow_dir}/amps-config.xml
available flows: $(ls "${FLOWS_DIR}" 2>/dev/null | tr '\n' ' ')"

    local suffix
    suffix="$(mount_suffix)"

    local flag
    flag="$(validate_flag)" || die "this ampServer advertises no config-verify flag.
Run '${AMPS_BIN} --help' in the image and check what it offers:
  ${ENGINE} run --rm --entrypoint ${AMPS_BIN} ${AMPS_IMAGE} --help"

    echo "--- ${AMPS_BIN} ${flag} ${flow}/amps-config.xml ---"
    if "${ENGINE}" run --rm \
            ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
            -v "${flow_dir}:${CONTAINER_CONFIG_DIR}${suffix}" \
            -w "${CONTAINER_DATA_DIR}" \
            --entrypoint "${AMPS_BIN}" \
            "${AMPS_IMAGE}" "${flag}" "${CONTAINER_CONFIG_DIR}/amps-config.xml"; then
        echo "config accepted"
        return 0
    fi
    echo "config REJECTED (see the error above)" >&2
    return 1
}

cmd_flows() {
    echo "business flows under ${FLOWS_DIR}:"
    local flow
    for flow in "${FLOWS_DIR}"/*/; do
        flow="$(basename "${flow}")"
        if [[ "${flow}" == "${AMPS_FLOW}" ]]; then
            echo "  ${flow}  (default)"
        else
            echo "  ${flow}"
        fi
    done
}

# Which action modules does this build actually have?
#
# Module names have moved between AMPS releases, and a wrong one is rejected at
# startup with no hint as to the right one - this repository already shipped a
# journal-ageing module that does not exist. The names are registered inside the
# server, so read them out of the binary rather than guessing again.
#
# Option names (<Age>, <Every>, <Filter> ...) are too generic to extract this
# way; for those, put your config in front of 'amps.sh validate' and let the
# server's rejection message name the one it did not like.
cmd_modules() {
    require_image
    local pattern='amps-action-(on|do)-[a-z0-9-]+'
    echo "--- action modules found in ${AMPS_IMAGE} ---"
    "${ENGINE}" run --rm ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
        --entrypoint sh "${AMPS_IMAGE}" -c "
            bin='${AMPS_BIN}'
            root=\$(dirname \$(dirname \"\$bin\"))
            {
                grep -haoE '${pattern}' \"\$bin\" 2>/dev/null
                find \"\$root\" -name '*.so' -exec grep -haoE '${pattern}' {} + 2>/dev/null
            } | sort -u
        " || die "could not run a shell in this image (try 'amps.sh probe')"
    echo
    echo "empty output does not prove the build has no actions - it can also mean"
    echo "AMPS_BIN points somewhere else. Check with 'amps.sh probe'."
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
    modules)  shift; cmd_modules "$@" ;;
    flows)    shift; cmd_flows "$@" ;;
    ""|-h|--help|help) usage ;;
    *)        die "unknown command '$1' (try --help)" ;;
esac
