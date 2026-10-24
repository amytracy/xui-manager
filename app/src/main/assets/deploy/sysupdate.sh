#!/usr/bin/env bash
# XUI Manager: operating system update, the equivalent of `apt update && apt upgrade` (dnf/yum upgrade on RHEL-likes).
#
# Runs without questions: configuration files changed on the server are kept (--force-confold) and needrestart
# restarts services on its own. When the upgrade leaves the system wanting a restart (/var/run/reboot-required on
# Debian/Ubuntu, `needs-restarting -r` on RHEL-likes) the reboot is scheduled a minute ahead, so the app still has
# time to read the result and clean up the job directory.
#
# Started like run.sh with params.env (OWNER_UID, OWNER_GID); talks through log/stage/exit and result.env (REBOOT=0|1).

set -u
set -o pipefail

JOB_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$JOB_DIR" || exit 90
LOG="$JOB_DIR/log"
echo $$ > "$JOB_DIR/pid"
# shellcheck source=/dev/null
. "$JOB_DIR/params.env"

# The override exists only for the app's script tests.
REBOOT_FLAG="${XUI_MANAGER_REBOOT_FLAG:-/var/run/reboot-required}"
# Seconds apt waits for a package manager that is already running (unattended-upgrades on a fresh server).
LOCK_WAIT=600
ESC=$'\033'
FINISHED=0
IPTABLES_CHANGED=0

# shellcheck source=lib.sh
. "$JOB_DIR/lib.sh"
trap on_exit EXIT
trap 'fail E_INTERRUPTED "terminated by signal"' TERM INT

# `apt upgrade` rather than `apt-get upgrade`: --with-new-pkgs also installs new dependencies (a new kernel).
apt_get() {
    DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1 \
        apt-get -y -o DPkg::Lock::Timeout="$LOCK_WAIT" \
        -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "$@" >>"$LOG" 2>&1
}

# fail_apt STEP: E_SYSUPDATE:lock when apt gave up waiting for another package manager, plain E_SYSUPDATE otherwise.
fail_apt() {
    if tail -n 20 "$LOG" | grep -E 'Could not get lock|Unable to acquire the dpkg frontend lock' >/dev/null; then
        fail "E_SYSUPDATE:lock" "another package manager kept the lock for $LOCK_WAIT s"
    fi
    fail E_SYSUPDATE "apt-get $1 failed"
}

stage PREPARE
[ "$(id -u)" = 0 ] || fail E_NOT_ROOT "root privileges are required"
command -v systemctl >/dev/null 2>&1 || fail E_UNSUPPORTED "systemd is required"
log "OS: $( (. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-$ID}") || uname -s), kernel $(uname -r)"

stage UPGRADE
NEED_REBOOT=0
if command -v apt-get >/dev/null 2>&1; then
    # Finishes an upgrade that was interrupted earlier; apt refuses to work until that is done.
    DEBIAN_FRONTEND=noninteractive dpkg --configure -a >>"$LOG" 2>&1 || log "WARNING: dpkg --configure -a failed"
    log "apt-get update"
    apt_get update || fail_apt update
    log "apt-get upgrade"
    apt_get upgrade --with-new-pkgs || fail_apt upgrade
    apt_get autoremove || log "WARNING: apt-get autoremove failed"
    [ -e "$REBOOT_FLAG" ] && NEED_REBOOT=1
elif command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then
    PM=yum
    command -v dnf >/dev/null 2>&1 && PM=dnf
    log "$PM upgrade"
    "$PM" -y upgrade >>"$LOG" 2>&1 || fail E_SYSUPDATE "$PM upgrade failed"
    if command -v needs-restarting >/dev/null 2>&1; then
        # Exit code 1 means a restart is needed, 0 that it is not.
        if needs-restarting -r >>"$LOG" 2>&1; then :; else [ $? -eq 1 ] && NEED_REBOOT=1; fi
    fi
else
    fail E_UNSUPPORTED "only apt, dnf and yum systems can be updated"
fi
log "System packages are up to date"

stage REBOOT
if [ "$NEED_REBOOT" = 1 ]; then
    log "The update needs a restart; rebooting in 1 minute"
    shutdown -r +1 "XUI Manager: restart after the system update" >>"$LOG" 2>&1 ||
        systemd-run --on-active=60 systemctl reboot >>"$LOG" 2>&1 ||
        fail "E_SYSUPDATE:reboot" "the system is updated, but the restart could not be scheduled"
else
    log "No restart needed"
fi

(
    umask 077
    echo "REBOOT=$NEED_REBOOT" > "$JOB_DIR/result.env.tmp"
)
own "$JOB_DIR/result.env.tmp"
mv -f "$JOB_DIR/result.env.tmp" "$JOB_DIR/result.env"

stage DONE
finish 0
