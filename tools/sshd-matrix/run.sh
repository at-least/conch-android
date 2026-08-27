#!/bin/sh
# Starts conch-android's OWN SSH test matrix — deliberately independent of
# the conch-ios harness (scripts/sshd-matrix there): separate image tag,
# container name, host ports and keys, so both projects' matrices can run
# side by side.
#
#   host 2233 → container 2223  password + pubkey   pwuser/conch-pw-1, bothuser/conch-pw-2 (keyA)
#   host 2234 → container 2224  pubkey only         keyuser (keyB), bothuser (keyA)
#   host 2235 → container 2225  password + pubkey   forwarding allowed (tunnels, agent)
#
# keyC is generated but never installed — the "unknown client key" scenario.
# lrzsz (rz/sz), tmux and the openssh client are in the image for ZMODEM /
# tmux / agent-forwarding integration tests.
#
# Idempotent: reuses a running container as-is. --rebuild force-recreates it
# (drops active sessions; host keys change). Keys live in
# ${CONCH_ANDROID_MATRIX_KEYS:-~/.cache/conch-android/sshd-matrix/keys},
# generated once and reused.
#
# Opt-in JVM tests run against it with:
#   ./gradlew testFossDebugUnitTest -Dconch.localSshdTest=true \
#       --tests '*.DockerSshdAuthTest' --tests '*.DockerOpenSshIntegrationTest'
set -eu

NAME=conch-android-sshd
IMAGE=conch-android-sshd:latest
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
KEYS_DIR=${CONCH_ANDROID_MATRIX_KEYS:-"${XDG_CACHE_HOME:-$HOME/.cache}/conch-android/sshd-matrix/keys"}

log() { printf '%s\n' "$*"; }

generate_keys() {
    [ -f "$KEYS_DIR/keyA" ] && [ -f "$KEYS_DIR/keyB" ] && [ -f "$KEYS_DIR/keyC" ] && return 0
    mkdir -p "$KEYS_DIR"
    chmod 700 "$KEYS_DIR"
    for k in keyA keyB keyC; do
        [ -f "$KEYS_DIR/$k" ] && continue
        log "generating $KEYS_DIR/$k (ed25519)"
        ssh-keygen -q -t ed25519 -N '' -C "conch-android-test-$k" -f "$KEYS_DIR/$k"
    done
}

running_mount_source() {
    docker inspect "$NAME" --format '{{range .Mounts}}{{if eq .Destination "/keys"}}{{.Source}}{{end}}{{end}}' 2>/dev/null
}

wait_ready() {
    i=0
    while [ "$i" -lt 50 ]; do
        if docker exec "$NAME" sh -c \
            'pgrep -f sshd_config_pwpub >/dev/null && pgrep -f sshd_config_keyonly >/dev/null && pgrep -f sshd_config_fwd >/dev/null'; then
            return 0
        fi
        i=$((i + 1))
        sleep 0.2
    done
    return 1
}

start_container() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    log "starting container $NAME with keys from $KEYS_DIR"
    docker run -d --init --name "$NAME" \
        -p 127.0.0.1:2233-2235:2223-2225 \
        -v "$KEYS_DIR":/keys:ro \
        "$IMAGE" >/dev/null
    if wait_ready; then
        log "ready:"
        log "  127.0.0.1:2233  pwuser/conch-pw-1 | bothuser: pw conch-pw-2 or keyA"
        log "  127.0.0.1:2234  key-only: keyuser with keyB, bothuser with keyA"
        log "  127.0.0.1:2235  forwarding allowed (same users as :2233)"
        log "  rejected-by-design: any password on :2234, keyC anywhere, forwarding on :2233/:2234"
    else
        log "container did not become ready; logs:"
        docker logs "$NAME" || true
        exit 1
    fi
}

generate_keys
log "building $IMAGE"
docker build -q -t "$IMAGE" "$HERE" >/dev/null

if [ "${1:-}" = "--rebuild" ]; then
    start_container
    exit 0
fi

if docker inspect "$NAME" >/dev/null 2>&1 && [ "$(docker inspect "$NAME" --format '{{.State.Running}}' 2>/dev/null)" = "true" ]; then
    mounted=$(running_mount_source)
    if [ "$mounted" = "$KEYS_DIR" ]; then
        log "reusing running container $NAME (ports 127.0.0.1:2233-2235)"
    else
        log "WARNING: running $NAME mounts keys from '${mounted:-none}', not '$KEYS_DIR'."
        log "         Fix with: $0 --rebuild"
    fi
    exit 0
fi

start_container
