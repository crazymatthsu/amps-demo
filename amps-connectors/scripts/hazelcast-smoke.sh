#!/usr/bin/env bash
# hazelcast-smoke.sh -- the positions-hazelcast application, end to end, on podman.
#
# Three containers on one network -- AMPS, a Hazelcast member, and the REAL
# localhost/amps-connector-app:local image running config/local/cache/positions-hazelcast --
# then a host-side driver writes the cache and reads the SOW back. What it proves is the
# thing an in-process integration test cannot: that the published image, the two mounted
# configuration directories, the ${HAZELCAST_HOST}/${AMPS_HOST} placeholders and the
# container's own healthcheck all agree with each other.
#
#   amps-connectors/scripts/hazelcast-smoke.sh up       network + AMPS + Hazelcast + connector
#   amps-connectors/scripts/hazelcast-smoke.sh feed     put 3 positions, update 1, remove 1
#   amps-connectors/scripts/hazelcast-smoke.sh verify   assert the SOW holds the 2 survivors
#   amps-connectors/scripts/hazelcast-smoke.sh dump     amps_sow_dump, in the AMPS container
#   amps-connectors/scripts/hazelcast-smoke.sh down     remove the containers, network, data
#   amps-connectors/scripts/hazelcast-smoke.sh run      all of the above; down also on failure
#
# Environment:
#   AMPS_IMAGE        REQUIRED: an image built from server/Containerfile (no public one exists)
#   AMPS_PLATFORM     default linux/amd64 -- the AMPS distribution is x86_64 only
#   AMPS_BIN          the server binary in the image (default /opt/amps/bin/ampServer)
#   HZ_IMAGE          default docker.io/hazelcast/hazelcast:5.5.0
#   MC_IMAGE          default docker.io/hazelcast/management-center:5.5.0 -- Hazelcast's own
#                     web UI, started beside the member so the cache can be browsed too:
#                     http://localhost:28080 after `up` (SMOKE_HZ_MC_PORT), Cluster -> Maps
#                     -> positions for the map, and the SQL Browser for its entries. Free for
#                     a cluster this size (no licence needed); SMOKE_HZ_MC=0 leaves it out
#   SMOKE_AMPS_PORT   host port for AMPS      (default 29007 -- never the demo's own 9007)
#   SMOKE_AMPS_ADMIN_PORT  host port for the AMPS admin web UI, Galvanometer (default 28085):
#                     http://localhost:28085 after `up`, to browse topics, the SOW and stats
#   SMOKE_AMPS_WS_PORT  host port for the AMPS websocket transport. The web UI's SQL page
#                     opens ws://<page host>:9008 -- the port number the SERVER config names,
#                     whatever the host mapping is (verified: any other host port shows
#                     "Error: Connection Failed" on that page). So the default is 9008 when
#                     nothing on this host listens there, and 29008 with a warning when
#                     something does (the demo's own amps-demo container, usually); the rest
#                     of the UI works either way, only the SQL page needs the real port
#   SMOKE_HZ_PORT     host port for Hazelcast (default 25701 -- never a member's own 5701)
#   SMOKE_REBUILD     1 to rebuild localhost/amps-connector-app:local even when it exists
#   PODMAN            container tool (default podman)
#
# Two notes worth having before something looks broken:
#
#   * The CONNECTOR reaches the member at hazelcast:5701 over the shared network, so no
#     HZ_NETWORK_PUBLICADDRESS is needed and none is set. The member advertises its container
#     address, which is exactly right for everything on that network.
#   * The HOST cannot reach that advertised address, so the feed's Hazelcast client runs
#     UNISOCKET (smart routing off). A smart client would connect to the published port, ask
#     for the member list, learn 10.89.x.y:5701 and hang. See HazelcastSmoke's class comment.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"                    # amps-connectors/
REPO_ROOT="$(dirname "$ROOT")"                     # the gradle build root
PODMAN="${PODMAN:-podman}"

NETWORK="amps-connectors-smoke"
AMPS_CONTAINER="amps-connectors-smoke-amps"
HZ_CONTAINER="amps-connectors-smoke-hazelcast"
APP_CONTAINER="amps-connectors-smoke-connector"

AMPS_PLATFORM="${AMPS_PLATFORM:-linux/amd64}"
AMPS_BIN="${AMPS_BIN:-/opt/amps/bin/ampServer}"
HZ_IMAGE="${HZ_IMAGE:-docker.io/hazelcast/hazelcast:5.5.0}"
MC_CONTAINER="amps-connectors-smoke-mc"
MC_IMAGE="${MC_IMAGE:-docker.io/hazelcast/management-center:5.5.0}"
SMOKE_HZ_MC="${SMOKE_HZ_MC:-1}"
SMOKE_HZ_MC_PORT="${SMOKE_HZ_MC_PORT:-28080}"
APP_IMAGE="localhost/amps-connector-app:local"
SMOKE_AMPS_PORT="${SMOKE_AMPS_PORT:-29007}"
SMOKE_AMPS_ADMIN_PORT="${SMOKE_AMPS_ADMIN_PORT:-28085}"
# 9008 if it is free, because that is the only port the web UI's SQL page will dial; see the
# header. bash's /dev/tcp is the probe, so the script needs neither nc nor lsof.
ws_port_default() {
    if (exec 3<>/dev/tcp/127.0.0.1/9008) 2>/dev/null; then
        exec 3>&-
        echo 29008
    else
        echo 9008
    fi
}
SMOKE_AMPS_WS_PORT="${SMOKE_AMPS_WS_PORT:-$(ws_port_default)}"
SMOKE_HZ_PORT="${SMOKE_HZ_PORT:-25701}"

SMOKE_DIR="$ROOT/build/smoke"
AMPS_DATA="$SMOKE_DIR/amps"
FLOW_DIR="$REPO_ROOT/server/config/flows/amps-connectors"
COMMON_DIR="$ROOT/config/local/common"
INSTANCE_DIR="$ROOT/config/local/cache/positions-hazelcast"

# SELinux hosts need :z on a bind mount or the container cannot read it -- the same rule
# server/scripts/amps.sh and the test harness apply. macOS and plain Linux do not.
if [[ "$(uname -s)" == "Linux" ]] && command -v getenforce >/dev/null 2>&1 \
        && [[ "$(getenforce 2>/dev/null)" != "Disabled" ]]; then
    MOUNT_SUFFIX=":z"
    MOUNT_RO_SUFFIX=":ro,z"
else
    MOUNT_SUFFIX=""
    MOUNT_RO_SUFFIX=":ro"
fi

die() { echo "error: $*" >&2; exit 1; }

usage() {
    awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

require_amps_image() {
    [[ -n "${AMPS_IMAGE:-}" ]] || die "AMPS_IMAGE is not set, and there is no public AMPS server
image to default to. Build one from server/Containerfile (see server/scripts/amps.sh), then:
  AMPS_IMAGE=localhost/amps-demo:5.3.5.135 $0 run"
}

container_running() {
    [[ "$("$PODMAN" inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" == "true" ]]
}

health_of() {
    "$PODMAN" inspect --format '{{.State.Health.Status}}' "$1" 2>/dev/null || true
}

# ---- up ------------------------------------------------------------------------

ensure_network() {
    "$PODMAN" network exists "$NETWORK" 2>/dev/null \
        || "$PODMAN" network create "$NETWORK" >/dev/null
    echo "network  $NETWORK"
}

# The same container the test harness starts (ContainerCliAmpsServer): the flow's config
# directory mounted at /amps/config, an empty data directory at /amps/data, the working
# directory there so AMPS resolves ./sow and ./journal inside it, and ampServer as the
# entrypoint rather than whatever the image would have run.
start_amps() {
    mkdir -p "$AMPS_DATA/sow" "$AMPS_DATA/journal" "$AMPS_DATA/stats"
    "$PODMAN" run -d \
        --name "$AMPS_CONTAINER" \
        --platform "$AMPS_PLATFORM" \
        --network "$NETWORK" --network-alias amps \
        -p "${SMOKE_AMPS_PORT}:9007" \
        -p "${SMOKE_AMPS_ADMIN_PORT}:8085" \
        -p "${SMOKE_AMPS_WS_PORT}:9008" \
        -v "${FLOW_DIR}:/amps/config${MOUNT_SUFFIX}" \
        -v "${AMPS_DATA}:/amps/data${MOUNT_SUFFIX}" \
        -w /amps/data \
        --entrypoint "$AMPS_BIN" \
        "$AMPS_IMAGE" /amps/config/amps-config.xml >/dev/null
}

# Readiness is the server's own log line, not an open port: the port forwarder accepts
# connections before AMPS has finished initialising, and a client that races it is dropped
# mid-logon.
wait_for_amps() {
    local timeout="$1" deadline=$(( SECONDS + $1 ))
    echo -n "waiting for AMPS ($AMPS_CONTAINER) "
    while (( SECONDS < deadline )); do
        # grep reads to the end on purpose: under pipefail a `grep -q` that exits early
        # hands the log command a SIGPIPE and the match reads as a failure.
        if "$PODMAN" logs "$AMPS_CONTAINER" 2>&1 | grep "initialization completed" >/dev/null; then
            echo " ready on localhost:${SMOKE_AMPS_PORT}"
            return 0
        fi
        if ! container_running "$AMPS_CONTAINER"; then
            echo
            "$PODMAN" logs "$AMPS_CONTAINER" 2>&1 | tail -20
            die "the AMPS container exited; see the log above"
        fi
        echo -n "."
        sleep 2
    done
    echo " not ready after ${timeout}s"
    return 1
}

start_hazelcast() {
    "$PODMAN" run -d \
        --name "$HZ_CONTAINER" \
        --network "$NETWORK" --network-alias hazelcast \
        -p "${SMOKE_HZ_PORT}:5701" \
        -e HZ_CLUSTERNAME=dev \
        "$HZ_IMAGE" >/dev/null
}

wait_for_hazelcast() {
    local deadline=$(( SECONDS + 120 ))
    echo -n "waiting for Hazelcast ($HZ_CONTAINER) "
    while (( SECONDS < deadline )); do
        if "$PODMAN" logs "$HZ_CONTAINER" 2>&1 | grep "is STARTED" >/dev/null; then
            echo " ready on localhost:${SMOKE_HZ_PORT}"
            return 0
        fi
        if ! container_running "$HZ_CONTAINER"; then
            echo
            "$PODMAN" logs "$HZ_CONTAINER" 2>&1 | tail -20
            die "the Hazelcast container exited; see the log above"
        fi
        echo -n "."
        sleep 2
    done
    echo
    die "Hazelcast did not report STARTED within 120s"
}

# Management Center on the same network, pre-pointed at the member: MC_DEFAULT_CLUSTER and
# MC_DEFAULT_CLUSTER_MEMBERS are the image's own knobs for "connect to this cluster on start",
# so the UI opens on the cluster rather than on a connection form. A fresh Management Center
# first asks which security provider to use; MC_INIT_CMD runs its own configuration tool
# before the web app starts, and "dev-mode configure" answers that question with the
# no-login mode meant for a throwaway cluster like this one (verified: the page then lands
# on the cluster directly). It is a browsing aid for a person, nothing in feed/verify depends
# on it, hence SMOKE_HZ_MC=0 to leave it out.
start_mc() {
    "$PODMAN" run -d \
        --name "$MC_CONTAINER" \
        --network "$NETWORK" --network-alias mc \
        -p "${SMOKE_HZ_MC_PORT}:8080" \
        -e MC_DEFAULT_CLUSTER=dev \
        -e MC_DEFAULT_CLUSTER_MEMBERS=hazelcast:5701 \
        -e MC_INIT_CMD='./bin/mc-conf.sh dev-mode configure' \
        "$MC_IMAGE" >/dev/null
}

# Readiness is the UI answering on its port: the log has no single marker worth keying on,
# and a person is the only consumer, so "the page loads" is exactly the right test.
wait_for_mc() {
    local deadline=$(( SECONDS + 180 ))
    echo -n "waiting for Management Center ($MC_CONTAINER) "
    while (( SECONDS < deadline )); do
        if curl -s -o /dev/null "http://localhost:${SMOKE_HZ_MC_PORT}/"; then
            echo " ready on http://localhost:${SMOKE_HZ_MC_PORT}"
            return 0
        fi
        if ! container_running "$MC_CONTAINER"; then
            echo
            "$PODMAN" logs "$MC_CONTAINER" 2>&1 | tail -20
            die "the Management Center container exited; see the log above"
        fi
        echo -n "."
        sleep 3
    done
    echo
    die "Management Center did not answer on ${SMOKE_HZ_MC_PORT} within 180s"
}

ensure_app_image() {
    if [[ "${SMOKE_REBUILD:-0}" != "1" ]] && "$PODMAN" image exists "$APP_IMAGE" 2>/dev/null; then
        echo "image    $APP_IMAGE (already built; SMOKE_REBUILD=1 to rebuild)"
        return
    fi
    echo "building $APP_IMAGE"
    # CONTAINER_ENGINE as an absolute path: dockerBuildLocal is an Exec task, so it resolves
    # its binary against the Gradle DAEMON's PATH rather than this shell's -- and a daemon
    # started with a minimal PATH cannot find a podman under /opt/podman/bin.
    ( cd "$REPO_ROOT" && CONTAINER_ENGINE="$(command -v "$PODMAN")" \
        ./gradlew :amps-connectors:connector-app:dockerBuildLocal --console=plain -q )
}

# The deployed shape exactly: nothing but the two config mounts and the endpoint
# placeholders the tree writes -- ${AMPS_HOST}/${AMPS_PORT} from config/local/common and
# ${HAZELCAST_HOST}:5701 from the instance file, resolved to the network aliases.
start_connector() {
    "$PODMAN" run -d \
        --name "$APP_CONTAINER" \
        --network "$NETWORK" \
        -v "${COMMON_DIR}:/app/config/common${MOUNT_RO_SUFFIX}" \
        -v "${INSTANCE_DIR}:/app/config/instance${MOUNT_RO_SUFFIX}" \
        -e AMPS_HOST=amps \
        -e AMPS_PORT=9007 \
        -e HAZELCAST_HOST=hazelcast \
        -e SPRING_APPLICATION_NAME=positions-hazelcast \
        "$APP_IMAGE" >/dev/null
}

# The image's own HEALTHCHECK (actuator readiness) is the readiness signal -- the same one
# compose waits on, which is why dockerBuildLocal builds --format docker: the OCI format
# drops it and this loop would wait for a status that never appears.
wait_for_connector() {
    local deadline=$(( SECONDS + 180 )) status
    echo -n "waiting for the connector ($APP_CONTAINER) "
    while (( SECONDS < deadline )); do
        status="$(health_of "$APP_CONTAINER")"
        if [[ "$status" == "healthy" ]]; then
            echo " healthy"
            "$PODMAN" logs "$APP_CONTAINER" 2>&1 \
                | grep -E "subscribed to map|read [0-9]+ entries" | tail -3 || true
            return 0
        fi
        if ! container_running "$APP_CONTAINER"; then
            echo
            "$PODMAN" logs "$APP_CONTAINER" 2>&1 | tail -30
            die "the connector container exited; see the log above"
        fi
        echo -n "."
        sleep 3
    done
    echo
    "$PODMAN" logs "$APP_CONTAINER" 2>&1 | tail -30
    die "the connector never reported healthy (last status: '${status:-none}')"
}

cmd_up() {
    require_amps_image
    ensure_network

    start_amps
    # A known podman quirk on this machine: the amd64 image occasionally stalls at startup
    # and never logs the marker. Removing the container and starting it again clears it,
    # so the first wait is short and gets exactly one retry.
    if ! wait_for_amps 90; then
        echo "AMPS never announced readiness -- removing the container and retrying once"
        "$PODMAN" rm -f "$AMPS_CONTAINER" >/dev/null 2>&1 || true
        rm -rf "$AMPS_DATA"
        start_amps
        wait_for_amps 180 || { "$PODMAN" logs "$AMPS_CONTAINER" 2>&1 | tail -30; \
            die "AMPS did not start on the second attempt either"; }
    fi

    start_hazelcast
    wait_for_hazelcast
    if [ "$SMOKE_HZ_MC" = 1 ]; then
        start_mc
        wait_for_mc
    fi

    ensure_app_image
    start_connector
    wait_for_connector

    echo
    echo "up: AMPS on localhost:${SMOKE_AMPS_PORT}, Hazelcast on localhost:${SMOKE_HZ_PORT},"
    echo "    connector 'positions-hazelcast' bridging map positions -> sow/connectors/positions"
    if [ "$SMOKE_HZ_MC" = 1 ]; then
        echo "    Hazelcast Management Center: http://localhost:${SMOKE_HZ_MC_PORT}"
    fi
    echo "    AMPS admin web UI: http://localhost:${SMOKE_AMPS_ADMIN_PORT}"
    if [ "$SMOKE_AMPS_WS_PORT" = 9008 ]; then
        echo "    (websocket published on 9008, so the UI's SQL page can query the SOW)"
    else
        echo "    WARNING: websocket published on ${SMOKE_AMPS_WS_PORT}, not 9008, because 9008 is"
        echo "    taken on this host: the UI's SQL page will report 'Connection Failed'. Free"
        echo "    9008 (stop whatever holds it) and rerun, or use 'dump' / 'verify' instead."
    fi
}

# ---- feed / verify / dump ------------------------------------------------------

smoke_driver() { # subcommand
    ( cd "$REPO_ROOT" && ./gradlew :amps-connectors:connector-app:hazelcastSmoke \
        --console=plain -q \
        --args="$1 --hazelcast localhost:${SMOKE_HZ_PORT} --amps localhost:${SMOKE_AMPS_PORT}" )
}

cmd_feed() {
    echo
    echo "== feed: writing the Hazelcast map =="
    smoke_driver feed
}

cmd_verify() {
    echo
    echo "== verify: reading sow/connectors/positions back =="
    smoke_driver verify
}

# 60East's own tool, inside the container, so the listing is the server's rendering of the
# records rather than this repo's -- the SOW file as AMPS wrote it, key and all.
#
# NOT `spark sow`: the image built from server/Containerfile carries no JVM (no `java`, and
# not even `which` for the launcher to look it up with), so /opt/amps/bin/spark exits with
# "Java was not found in your $PATH or $JAVA_HOME" before it opens a connection.
# amps_sow_dump is a native binary and reads the file the server is serving from.
cmd_dump() {
    echo
    echo "== dump: amps_sow_dump, inside $AMPS_CONTAINER =="
    "$PODMAN" exec "$AMPS_CONTAINER" /opt/amps/bin/amps_sow_dump -v \
        /amps/data/sow/connectors-positions.sow
}

# ---- down ----------------------------------------------------------------------

cmd_down() {
    local name
    for name in "$APP_CONTAINER" "$MC_CONTAINER" "$HZ_CONTAINER" "$AMPS_CONTAINER"; do
        "$PODMAN" rm -f "$name" >/dev/null 2>&1 || true
    done
    "$PODMAN" network rm "$NETWORK" >/dev/null 2>&1 || true
    rm -rf "$SMOKE_DIR"
    echo "down: containers, network $NETWORK and $SMOKE_DIR are gone"
}

# ---- run -----------------------------------------------------------------------

# The teardown is an EXIT trap rather than the last line of cmd_run, so a failure anywhere
# in the middle leaves no containers behind either -- which is the difference between a
# smoke test you can run twice and one you have to clean up after.
smoke_teardown() {
    local rc=$?
    trap - EXIT
    if (( rc != 0 )); then
        echo
        echo "smoke: FAILED (exit ${rc}) -- tearing the stack down"
    fi
    cmd_down || true
    if (( rc == 0 )); then
        echo
        echo "smoke: PASS"
    fi
    exit "$rc"
}

cmd_run() {
    trap smoke_teardown EXIT
    cmd_up
    cmd_feed
    cmd_verify
    cmd_dump
}

case "${1:-}" in
    up)      shift; cmd_up "$@" ;;
    feed)    shift; cmd_feed "$@" ;;
    verify)  shift; cmd_verify "$@" ;;
    dump)    shift; cmd_dump "$@" ;;
    down)    shift; cmd_down "$@" ;;
    run)     shift; cmd_run "$@" ;;
    ""|-h|--help|help) usage ;;
    *)       die "unknown command '$1' (try --help)" ;;
esac
