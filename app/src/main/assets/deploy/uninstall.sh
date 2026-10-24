#!/usr/bin/env bash
# XUI Manager: removes from the server everything the app (and the 3x-ui installer it runs) put there.
#
#   3x-ui and Xray (service, binaries, database, logs, env file) and its fail2ban IP Limit jail, the self-steal decoy
#   site, the RoscomVPN geodata timer, acme.sh with its cron job and all certificates, database backups, the firewall
#   rules for the ports the panel used and the app's job directories. System packages (curl, socat, cron, fail2ban…)
#   stay, and the SSH port is never closed.
#
# Every step may find its part already gone, so a second run on a clean server ends with "0" as well.
# Started like run.sh with params.env (OWNER_UID, OWNER_GID, SSH_PORT, PORTS separated by colons); talks through log/stage/exit.

set -u
set -o pipefail

JOB_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$JOB_DIR" || exit 90
LOG="$JOB_DIR/log"
echo $$ > "$JOB_DIR/pid"
# shellcheck source=/dev/null
. "$JOB_DIR/params.env"

# The overrides exist only for the app's script tests.
XUI_DIR="${XUI_MANAGER_XUI_DIR:-/usr/local/x-ui}"
ETC="${XUI_MANAGER_ETC:-/etc}"
XUI_CLI="${XUI_MANAGER_XUI_CLI:-/usr/bin/x-ui}"
XUI_LOG_DIR="${XUI_MANAGER_XUI_LOG:-/var/log/x-ui}"
UNIT_DIR="${XUI_MANAGER_UNIT_DIR:-/etc/systemd/system}"
BIN_DIR="${XUI_MANAGER_BIN_DIR:-/usr/local/bin}"
CERT_ROOT="${XUI_MANAGER_CERT_ROOT:-/root/cert}"
ACME_HOME="${XUI_MANAGER_ACME_HOME:-/root/.acme.sh}"
ACME="$ACME_HOME/acme.sh"
BACKUP_DIR="${XUI_MANAGER_BACKUP_DIR:-/root/xui-backup}"
GEODATA_UNIT=xui-roscomvpn-geodata
ESC=$'\033'
FINISHED=0
IPTABLES_CHANGED=0

# shellcheck source=lib.sh
. "$JOB_DIR/lib.sh"
# shellcheck source=selfsteal.sh
SELFSTEAL_LIB=1 . "$JOB_DIR/selfsteal.sh"
trap on_exit EXIT
trap 'fail E_INTERRUPTED "terminated by signal"' TERM INT

# listening_ports PATTERN: TCP and UDP ports that processes whose name matches PATTERN listen on, loopback excluded.
listening_ports() {
    command -v ss >/dev/null 2>&1 || return 0
    ss -tulnp 2>/dev/null | awk -v re="$1" '
        NR > 1 && $NF ~ re {
            local_addr = $5
            if (local_addr ~ /^(127\.|\[::1\]|::1|\[::ffff:127\.)/) next
            sub(/.*:/, "", local_addr)
            if (local_addr ~ /^[0-9]+$/) print local_addr
        }' | sort -un
}

xui_active() { systemctl is-active x-ui >/dev/null 2>&1; }

stage PREPARE
[ "$(id -u)" = 0 ] || fail E_NOT_ROOT "root privileges are required"
command -v systemctl >/dev/null 2>&1 || fail E_UNSUPPORTED "systemd is required"

stage PORTS
# Read before the services stop: afterwards nothing tells which ports the panel and its inbounds used.
KEEP=" 22 ${SSH_PORT:-22} $(listening_ports '"sshd"' | tr '\n' ' ') "
CLOSE=""
for port in $(printf '%s\n' "${PORTS:-}" | tr ':' '\n') $(listening_ports '"(x-ui|xray[^"]*|xui-caddy)"') 80; do
    [[ "$port" =~ ^[0-9]+$ ]] || continue
    case "$KEEP" in *" $port "*) continue ;; esac
    case " $CLOSE " in *" $port "*) continue ;; esac
    CLOSE="${CLOSE:+$CLOSE }$port"
done
log "Ports to close: ${CLOSE:-none}; never closed: $(echo $KEEP)"

stage SELFSTEAL
if [ -f "$SS_UNIT_DIR/$SS_UNIT.service" ] || [ -d "$SS_ETC" ] || [ -x "$SS_CADDY" ]; then
    selfsteal_remove
    rmdir "$(dirname "$SS_ETC")" 2>/dev/null || true
else
    log "No decoy site"
fi

stage GEODATA
if [ -f "$UNIT_DIR/$GEODATA_UNIT.timer" ] || [ -x "$BIN_DIR/$GEODATA_UNIT" ]; then
    systemctl disable --now "$GEODATA_UNIT.timer" >>"$LOG" 2>&1 || true
    rm -f "$UNIT_DIR/$GEODATA_UNIT.service" "$UNIT_DIR/$GEODATA_UNIT.timer" "$BIN_DIR/$GEODATA_UNIT"
    systemctl daemon-reload >>"$LOG" 2>&1 || true
    log "RoscomVPN geodata timer removed"
else
    log "No RoscomVPN geodata timer"
fi

stage PANEL
for f in "$ETC/default/x-ui" "$ETC/sysconfig/x-ui" "$ETC/conf.d/x-ui"; do
    if [ -f "$f" ] && grep '^XUI_DB_TYPE=postgres' "$f" >/dev/null; then
        log "WARNING: the panel kept its data in PostgreSQL; the PostgreSQL server and its database are left in place"
    fi
done
if xui_active; then
    systemctl stop x-ui >>"$LOG" 2>&1
    xui_active && fail E_UNINSTALL "could not stop x-ui"
fi
if [ -f "$UNIT_DIR/x-ui.service" ] || [ -d "$UNIT_DIR/x-ui.service.d" ]; then
    systemctl disable x-ui >>"$LOG" 2>&1 || true
fi
rm -rf "$UNIT_DIR/x-ui.service" "$UNIT_DIR/x-ui.service.d"
systemctl daemon-reload >>"$LOG" 2>&1 || true
systemctl reset-failed x-ui >/dev/null 2>&1 || true
rm -rf "$XUI_DIR" "$ETC/x-ui" "$XUI_LOG_DIR"
rm -f "$XUI_CLI" "$ETC/default/x-ui" "$ETC/sysconfig/x-ui" "$ETC/conf.d/x-ui"
[ ! -e "$XUI_DIR" ] && [ ! -e "$ETC/x-ui" ] || fail "E_UNINSTALL:files" "could not delete the 3x-ui files"
log "3x-ui and Xray removed"

jail_removed=0
for f in jail.d/3x-ipl.conf jail.d/3x-ipl-backend.conf filter.d/3x-ipl.conf action.d/3x-ipl.conf; do
    if [ -e "$ETC/fail2ban/$f" ]; then
        rm -f "$ETC/fail2ban/$f"
        jail_removed=1
    fi
done
if [ "$jail_removed" = 1 ]; then
    if systemctl is-active fail2ban >/dev/null 2>&1; then systemctl restart fail2ban >>"$LOG" 2>&1 || true; fi
    log "fail2ban IP Limit jail removed (fail2ban itself stays)"
fi

stage CERTS
if [ -x "$ACME" ]; then
    # Takes its cron job away as well.
    "$ACME" --uninstall >>"$LOG" 2>&1 || log "WARNING: acme.sh --uninstall failed"
fi
if command -v crontab >/dev/null 2>&1; then
    cron=$(crontab -l 2>/dev/null)
    if printf '%s\n' "$cron" | grep acme.sh >/dev/null; then
        printf '%s\n' "$cron" | grep -v acme.sh | crontab - 2>>"$LOG" || log "WARNING: could not remove the acme.sh cron job"
    fi
    unset cron
fi
rm -rf "$ACME_HOME" "$CERT_ROOT" "$BACKUP_DIR"
log "acme.sh, certificates and database backups removed"

stage FIREWALL
for port in $CLOSE; do
    # Something other than the panel still listening there (a web server on 80 or 443) keeps its rule.
    if port_busy "$port"; then
        log "Port $port is still in use by another service, its firewall rule stays"
    else
        close_port "$port"
    fi
done
persist_iptables

stage JOBS
jobs_root=$(dirname "$JOB_DIR")
if [ "$(basename "$jobs_root")" = jobs ]; then
    find "$jobs_root" -mindepth 1 -maxdepth 1 ! -path "$JOB_DIR" -exec rm -rf {} + 2>/dev/null || true
    log "Old job directories removed"
fi

stage DONE
log "The server is clean"
finish 0
