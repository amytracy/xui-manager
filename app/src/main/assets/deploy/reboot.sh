#!/usr/bin/env bash
# XUI Manager: restart the whole server on request.
#
# The reboot is scheduled a few seconds ahead (a systemd timer, or a detached `shutdown -r now` where systemd-run
# refuses), so the app still has time to read the result and clean up the job directory before SSH goes away.
#
# Started like run.sh with params.env (OWNER_UID, OWNER_GID); talks through log/stage/exit.

set -u
set -o pipefail

JOB_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$JOB_DIR" || exit 90
LOG="$JOB_DIR/log"
echo $$ > "$JOB_DIR/pid"
# shellcheck source=/dev/null
. "$JOB_DIR/params.env"

# Seconds until the restart; the override exists only for the app's script tests.
DELAY="${XUI_MANAGER_REBOOT_DELAY:-10}"
ESC=$'\033'
FINISHED=0
IPTABLES_CHANGED=0

# shellcheck source=lib.sh
. "$JOB_DIR/lib.sh"
trap on_exit EXIT
trap 'fail E_INTERRUPTED "terminated by signal"' TERM INT

stage PREPARE
[ "$(id -u)" = 0 ] || fail E_NOT_ROOT "root privileges are required"
command -v systemctl >/dev/null 2>&1 || fail E_UNSUPPORTED "systemd is required"

stage REBOOT
if systemd-run --on-active="$DELAY" --timer-property=AccuracySec=1s systemctl reboot >>"$LOG" 2>&1; then
    log "The server restarts in $DELAY s"
elif command -v shutdown >/dev/null 2>&1; then
    # Detached from this job, so the restart still happens after the script has finished.
    if command -v setsid >/dev/null 2>&1; then
        setsid nohup sh -c "sleep $DELAY; shutdown -r now 'XUI Manager: restart'" >/dev/null 2>&1 </dev/null &
    else
        nohup sh -c "sleep $DELAY; shutdown -r now 'XUI Manager: restart'" >/dev/null 2>&1 </dev/null &
    fi
    log "The server restarts in $DELAY s (shutdown)"
else
    fail E_REBOOT "the restart could not be scheduled"
fi

stage DONE
finish 0
