#!/usr/bin/env bash
# XUI Manager deploy runner.
#
# Started detached (setsid nohup) as root by the app and talks to it only through files in its own directory:
#   log         installer output with credentials masked
#   stage       name of the current stage
#   exit        "0" on success or an E_* error code, written last
#   result.env  ports, certificate fingerprint and API token (mode 600, owned by the login user)
#   x25519.out  output of `xray x25519` (mode 600)
# Shared functions live in lib.sh; with SELFSTEAL=1 the decoy site from selfsteal.sh is set up as well.
# Secrets arrive in secrets.env, which is sourced and deleted before anything else happens.

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

# The overrides exist only for the app's script tests; deployments always use the defaults.
XUI_DIR="${XUI_MANAGER_XUI_DIR:-/usr/local/x-ui}"
XUI_BIN="$XUI_DIR/x-ui"
# Same locations install.sh uses, so its acme.sh renewals and ours are one and the same.
CERT_ROOT="${XUI_MANAGER_CERT_ROOT:-/root/cert}"
ACME="${XUI_MANAGER_ACME:-/root/.acme.sh/acme.sh}"
ESC=$'\033'
FINISHED=0
IPTABLES_CHANGED=0

# shellcheck source=lib.sh
. "$JOB_DIR/lib.sh"
trap on_exit EXIT
trap 'fail E_INTERRUPTED "terminated by signal"' TERM INT

# ---------------------------------------------------------------------------------------------------------

stage PREPARE
[ "$(id -u)" = 0 ] || fail E_NOT_ROOT "root privileges are required"
command -v systemctl >/dev/null 2>&1 || fail E_UNSUPPORTED "systemd is required"
ensure_cmd curl curl || fail E_DEPS "curl is not available"
ensure_cmd openssl openssl || fail E_DEPS "openssl is not available"
ensure_cmd sha256sum coreutils || fail E_DEPS "sha256sum is not available"
if ! command -v ss >/dev/null 2>&1 && ! command -v netstat >/dev/null 2>&1; then
    ensure_cmd ss iproute2 || ensure_cmd ss iproute || fail E_DEPS "neither ss nor netstat is available"
fi
log "OS: $( (. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-$ID}") || uname -s), arch: $(uname -m)"

stage EXISTING
EXISTED=0
ADOPT=0
if [ -x "$XUI_BIN" ]; then
    EXISTED=1
    log "Found 3x-ui $("$XUI_BIN" -v 2>/dev/null | head -n 1)"
    case "$EXISTING_ACTION" in
        update)
            log "Updating in place: inbounds and clients are kept, login, password and path are replaced"
            ;;
        clean)
            for f in /etc/default/x-ui /etc/sysconfig/x-ui /etc/conf.d/x-ui; do
                if [ -f "$f" ] && grep -q 'XUI_DB_TYPE=postgres' "$f"; then
                    fail E_CLEAN_POSTGRES "clean reinstall is not supported for PostgreSQL-backed panels"
                fi
            done
            systemctl stop x-ui >>"$LOG" 2>&1 || true
            if [ -f /etc/x-ui/x-ui.db ]; then
                mkdir -p /root/xui-backup && chmod 700 /root/xui-backup
                backup="/root/xui-backup/x-ui-$(date +%Y%m%d-%H%M%S).db"
                cp -a /etc/x-ui/x-ui.db "$backup" || fail "E_INSTALL:backup" "could not back up x-ui.db"
                rm -f /etc/x-ui/x-ui.db /etc/x-ui/x-ui.db-wal /etc/x-ui/x-ui.db-shm
                log "Database backed up to $backup"
            fi
            ;;
        adopt)
            ADOPT=1
            log "Taking over the installed panel: version, port, path, inbounds and subscriptions stay as they are"
            ;;
        *)
            fail E_ALREADY_INSTALLED "3x-ui is already installed"
            ;;
    esac
else
    [ "$EXISTING_ACTION" = adopt ] && fail "E_INSTALL:missing" "there is no installed panel to take over"
    log "No existing installation"
fi

stage PORTS
if [ "$ADOPT" = 1 ]; then
    # Some 2.x releases print the current password here too: the output is parsed in memory and never logged.
    current_settings=$("$XUI_BIN" setting -show true 2>/dev/null)
    PANEL_PORT=$(printf '%s\n' "$current_settings" | sed -n 's/^port:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
    WEB_BASE_PATH=$(printf '%s\n' "$current_settings" | sed -n 's/^webBasePath:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
    unset current_settings
    [[ "$PANEL_PORT" =~ ^[0-9]+$ ]] || fail "E_INSTALL:settings" "could not read the current panel port"
    SUB_PORT=0
    INBOUND_PORT=0
    log "Panel port $PANEL_PORT is kept"
else
    PANEL_PORT=$(pick_port "$PANEL_PORT") || fail "E_PORT_BUSY:$PANEL_PORT" "no free port for the panel"
    SUB_PORT=$(pick_port "$SUB_PORT" "$PANEL_PORT") || fail "E_PORT_BUSY:$SUB_PORT" "no free port for subscriptions"
    if port_busy "$INBOUND_PORT" || in_list "$INBOUND_PORT" "$PANEL_PORT" "$SUB_PORT"; then
        if [ "$INBOUND_PORT_STRICT" = 1 ]; then
            fail "E_PORT_BUSY:$INBOUND_PORT" "port $INBOUND_PORT is already in use"
        fi
        log "Port $INBOUND_PORT is in use, choosing another one for the inbound"
        INBOUND_PORT=$(pick_port "$(random_port)" "$PANEL_PORT" "$SUB_PORT") ||
            fail "E_PORT_BUSY:$INBOUND_PORT" "no free port for the inbound"
    fi
    log "Panel port $PANEL_PORT, subscription port $SUB_PORT, inbound port $INBOUND_PORT"
    [ "$SSL_MODE" != none ] || fail E_TLS_UNAVAILABLE "Let's Encrypt needs a public IPv4 address or a domain name"
    port_busy 80 && fail E_TLS_PORT80 "port 80 is in use; Let's Encrypt needs it to validate this server"
fi

stage FIREWALL
open_port "$PANEL_PORT"
if [ "$ADOPT" != 1 ]; then
    open_port "$SUB_PORT"
    open_port "$INBOUND_PORT"
    if [ "$SSL_MODE" != none ]; then open_port 80; fi
fi
persist_iptables

if [ "$ADOPT" != 1 ]; then
    stage DOWNLOAD
    curl -fsSL --retry 3 --connect-timeout 20 -o "$JOB_DIR/install.sh" "$INSTALLER_URL" >>"$LOG" 2>&1 ||
        fail E_DOWNLOAD "could not download $INSTALLER_URL"
    actual_sha=$(sha256sum "$JOB_DIR/install.sh" | awk '{print $1}')
    [ "$actual_sha" = "$INSTALLER_SHA256" ] || fail E_INSTALLER_HASH "install.sh checksum mismatch: $actual_sha"
    log "install.sh $XUI_VERSION verified, sha256 $actual_sha"

    stage INSTALL
    (
        export XUI_NONINTERACTIVE=1
        export XUI_SSL_MODE="$SSL_MODE"
        export XUI_USERNAME="$PANEL_USERNAME"
        export XUI_PASSWORD="$PANEL_PASSWORD"
        export XUI_PANEL_PORT="$PANEL_PORT"
        export XUI_WEB_BASE_PATH="$WEB_BASE_PATH"
        if [ "$SSL_MODE" = domain ]; then export XUI_DOMAIN="$SERVER_HOST"; fi
        if [ "$SSL_MODE" = ip ]; then export XUI_SERVER_IP="$SERVER_HOST"; fi
        bash "$JOB_DIR/install.sh" "$XUI_VERSION" </dev/null 2>&1
    ) | mask >> "$LOG"
    install_rc=${PIPESTATUS[0]}
    rm -f "$JOB_DIR/install.sh"
    [ "$install_rc" -eq 0 ] || fail "E_INSTALL:$install_rc" "install.sh exited with code $install_rc"
    [ -x "$XUI_BIN" ] || fail "E_INSTALL:missing" "x-ui binary not found after installation"
fi

stage CONFIGURE
if [ "$ADOPT" = 1 ]; then
    "$XUI_BIN" setting -username "$PANEL_USERNAME" -password "$PANEL_PASSWORD" 2>&1 | mask >> "$LOG"
    [ "${PIPESTATUS[0]}" -eq 0 ] || fail "E_INSTALL:setting" "x-ui setting failed"
else
    "$XUI_BIN" setting -username "$PANEL_USERNAME" -password "$PANEL_PASSWORD" -port "$PANEL_PORT" \
        -webBasePath "$WEB_BASE_PATH" 2>&1 | mask >> "$LOG"
    [ "${PIPESTATUS[0]}" -eq 0 ] || fail "E_INSTALL:setting" "x-ui setting failed"
fi

current=$("$XUI_BIN" setting -getCert true 2>/dev/null)
CERT_FILE=$(printf '%s\n' "$current" | sed -n 's/^cert:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
KEY_FILE=$(printf '%s\n' "$current" | sed -n 's/^key:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
if [ -n "$KEY_FILE" ] && cert_trusted "$CERT_FILE"; then
    log "The panel already has a trusted certificate for $SERVER_HOST"
else
    issue_cert
    "$XUI_BIN" cert -webCert "$CERT_FILE" -webCertKey "$KEY_FILE" >>"$LOG" 2>&1 ||
        fail E_CERT "could not apply the certificate to the panel"
    cert_trusted "$CERT_FILE" || fail E_TLS "the issued certificate does not verify for $SERVER_HOST"
    log "Panel certificate issued by Let's Encrypt"
fi

API_TOKEN=""
# Captured first: grepping the live output with -q under pipefail can SIGPIPE x-ui and read as "no tokens".
setting_help=$("$XUI_BIN" setting -h 2>&1)
if printf '%s\n' "$setting_help" | grep getApiToken >/dev/null; then
    API_TOKEN=$("$XUI_BIN" setting -getApiToken -tokenName xui-manager 2>/dev/null | sed -n 's/^apiToken:[[:space:]]*//p' | tail -n 1)
    [ -n "$API_TOKEN" ] || fail E_TOKEN "could not issue an API token"
    log "API token \"xui-manager\" issued"
else
    # 3.x always has tokens; without one the app could not reach the panel at all.
    case "$("$XUI_BIN" -v 2>/dev/null | head -n 1)" in
        v[3-9]* | [3-9]*) fail E_TOKEN "this 3.x panel does not offer API tokens" ;;
    esac
    log "This panel version has no API tokens; the app will sign in with the new login and password"
fi

SELFSTEAL_PORT=""
if [ "$ADOPT" != 1 ] && [ "${SELFSTEAL:-0}" = 1 ]; then
    stage SELFSTEAL
    DOMAIN="$SERVER_HOST"
    # shellcheck source=selfsteal.sh
    SELFSTEAL_LIB=1 . "$JOB_DIR/selfsteal.sh"
    selfsteal_install
    SELFSTEAL_PORT="$SS_PORT"
fi

SUB_CERT=""
SUB_KEY=""
if [ "$ADOPT" != 1 ]; then
    # Subscriptions are served with the same trusted certificate as the panel.
    SUB_CERT="$CERT_FILE"
    SUB_KEY="$KEY_FILE"
fi
PANEL_VERSION=$("$XUI_BIN" -v 2>/dev/null | head -n 1)

if [ "$ADOPT" != 1 ]; then
    stage KEYS
    XRAY_BIN=$(ls "$XUI_DIR"/bin/xray-linux-* 2>/dev/null | grep -v '\.sha256$' | head -n 1)
    if [ -n "$XRAY_BIN" ]; then
        (umask 077 && "$XRAY_BIN" x25519 > "$JOB_DIR/x25519.out" 2>&1)
        own "$JOB_DIR/x25519.out"
        log "Reality key pair generated with $(basename "$XRAY_BIN") x25519"
    else
        log "xray binary not found; the panel will generate the Reality key pair"
    fi
fi

stage RESTART
systemctl restart x-ui >>"$LOG" 2>&1 || fail E_PANEL_START "could not restart x-ui"
started=0
for _ in $(seq 1 30); do
    if port_busy "$PANEL_PORT"; then
        started=1
        break
    fi
    sleep 2
done
[ "$started" = 1 ] || fail E_PANEL_START "the panel is not listening on port $PANEL_PORT"
log "Panel is listening on port $PANEL_PORT"

(
    umask 077
    {
        echo "PANEL_PORT=$PANEL_PORT"
        echo "API_TOKEN=$API_TOKEN"
        echo "SUB_PORT=$SUB_PORT"
        echo "SUB_CERT=$SUB_CERT"
        echo "SUB_KEY=$SUB_KEY"
        echo "INBOUND_PORT=$INBOUND_PORT"
        echo "PANEL_VERSION=$PANEL_VERSION"
        echo "EXISTED=$EXISTED"
        echo "SELFSTEAL_PORT=$SELFSTEAL_PORT"
        if [ "$ADOPT" = 1 ]; then echo "WEB_BASE_PATH=$WEB_BASE_PATH"; fi
    } > "$JOB_DIR/result.env.tmp"
)
own "$JOB_DIR/result.env.tmp"
mv -f "$JOB_DIR/result.env.tmp" "$JOB_DIR/result.env"

stage DONE
finish 0
