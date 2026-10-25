#!/usr/bin/env bash
# XUI Manager restore: reconnects the app to a panel that is already set up (a new phone, a reinstalled app) without
# deploying anything again. Inbounds, clients, subscriptions, certificates, ports, the firewall and the decoy site stay
# as they are; only the panel login and password are replaced and the app's API token is issued anew, so whatever
# device held the old ones loses access. x-ui is not restarted, so connected clients are not dropped.
#
# Started like run.sh with params.env (OWNER_UID, OWNER_GID, PREFERRED_HOST) and secrets.env (PANEL_USERNAME,
# PANEL_PASSWORD), which is sourced and deleted first. Writes result.env with PANEL_PORT, WEB_BASE_PATH,
# PANEL_VERSION, API_TOKEN, CERT_HOST (the name the panel certificate covers) and SELFSTEAL_PORT.

set -u
set -o pipefail

JOB_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$JOB_DIR" || exit 90
LOG="$JOB_DIR/log"
echo $$ > "$JOB_DIR/pid"

# shellcheck source=/dev/null
. "$JOB_DIR/params.env"
if [ -f "$JOB_DIR/secrets.env" ]; then
    # shellcheck source=/dev/null
    . "$JOB_DIR/secrets.env"
    rm -f "$JOB_DIR/secrets.env"
fi

# The overrides exist only for the app's script tests.
XUI_DIR="${XUI_MANAGER_XUI_DIR:-/usr/local/x-ui}"
XUI_BIN="$XUI_DIR/x-ui"
SS_ETC="${XUI_MANAGER_SS_ETC:-/etc/xui-manager/selfsteal}"
ESC=$'\033'
FINISHED=0
IPTABLES_CHANGED=0

# shellcheck source=lib.sh
. "$JOB_DIR/lib.sh"
trap on_exit EXIT
trap 'fail E_INTERRUPTED "terminated by signal"' TERM INT

# cert_names FILE: the names the certificate covers, DNS names first, one per line.
cert_names() {
    local sans
    sans=$(openssl x509 -in "$1" -noout -text 2>/dev/null | sed -n '/Subject Alternative Name/{n;p;}' | tr ',' '\n' |
        sed 's/^[[:space:]]*//')
    printf '%s\n' "$sans" | sed -n 's/^DNS://p' | grep -v '^\*' || true
    printf '%s\n' "$sans" | sed -n 's/^IP Address://p' || true
}

stage PREPARE
[ "$(id -u)" = 0 ] || fail E_NOT_ROOT "root privileges are required"
ensure_cmd openssl openssl || fail E_DEPS "openssl is not available"
[ -x "$XUI_BIN" ] || fail E_RESTORE_MISSING "3x-ui is not installed on this server"
PANEL_VERSION=$("$XUI_BIN" -v 2>/dev/null | head -n 1)
log "Found 3x-ui $PANEL_VERSION"

stage EXISTING
read_panel_settings
log "Panel port $PANEL_PORT"
read_panel_cert
CERT_HOST=""
if [ -n "$KEY_FILE" ]; then
    for name in "${PREFERRED_HOST:-}" $(cert_names "$CERT_FILE"); do
        [ -n "$name" ] || continue
        SERVER_HOST="$name"
        if cert_trusted "$CERT_FILE"; then
            CERT_HOST="$name"
            break
        fi
    done
fi
[ -n "$CERT_HOST" ] || fail E_RESTORE_CERT "the panel has no valid Let's Encrypt certificate"
log "The panel certificate is valid for $CERT_HOST"

SELFSTEAL_PORT=""
if [ -f "$SS_ETC/Caddyfile" ]; then
    SELFSTEAL_PORT=$(sed -n 's#^https://[^:]*:\([0-9][0-9]*\) {#\1#p' "$SS_ETC/Caddyfile" | head -n 1)
fi
if [ -n "$SELFSTEAL_PORT" ]; then
    log "Decoy site found on 127.0.0.1:$SELFSTEAL_PORT"
else
    log "No decoy site"
fi

stage CONFIGURE
"$XUI_BIN" setting -username "$PANEL_USERNAME" -password "$PANEL_PASSWORD" 2>&1 | mask >> "$LOG"
[ "${PIPESTATUS[0]}" -eq 0 ] || fail "E_INSTALL:setting" "x-ui setting failed"
log "Panel login and password replaced"
issue_api_token

(
    umask 077
    {
        echo "PANEL_PORT=$PANEL_PORT"
        echo "WEB_BASE_PATH=$WEB_BASE_PATH"
        echo "PANEL_VERSION=$PANEL_VERSION"
        echo "API_TOKEN=$API_TOKEN"
        echo "CERT_HOST=$CERT_HOST"
        echo "SELFSTEAL_PORT=$SELFSTEAL_PORT"
    } > "$JOB_DIR/result.env.tmp"
)
own "$JOB_DIR/result.env.tmp"
mv -f "$JOB_DIR/result.env.tmp" "$JOB_DIR/result.env"

stage DONE
log "Nothing was reinstalled"
finish 0
