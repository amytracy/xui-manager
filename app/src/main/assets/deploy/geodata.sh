#!/usr/bin/env bash
# XUI Manager: RoscomVPN geo files for Xray on the server (github.com/hydraponique/roscomvpn-geoip and -geosite).
#
# install: downloads geoip.dat and geosite.dat from the latest releases, checks each against the .sha256 published
#          with it, stores them next to Xray as geoip_roscomvpn.dat / geosite_roscomvpn.dat (routing rules refer to
#          them as ext:geoip_roscomvpn.dat:direct etc.) and installs a weekly systemd timer that refreshes them.
# remove:  deletes the timer, the updater and both files. Remove the routing rules that use them first.
#
# Started like run.sh with params.env (ACTION, OWNER_UID, OWNER_GID); talks through log/stage/exit.

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
BIN_DIR="${XUI_MANAGER_BIN_DIR:-/usr/local/bin}"
UNIT_DIR="${XUI_MANAGER_UNIT_DIR:-/etc/systemd/system}"
UPDATER="$BIN_DIR/xui-roscomvpn-geodata"
UNIT=xui-roscomvpn-geodata
ESC=$'\033'
FINISHED=0
IPTABLES_CHANGED=0

# shellcheck source=lib.sh
. "$JOB_DIR/lib.sh"
trap on_exit EXIT
trap 'fail E_INTERRUPTED "terminated by signal"' TERM INT

# The updater is a standalone script so the timer keeps working without the app. Exit codes: 0 updated or already
# current, 1 download or checksum failure (the installed files are left untouched).
write_updater() {
    mkdir -p "$BIN_DIR"
    cat > "$UPDATER" <<UPDATER
#!/bin/sh
# Installed by XUI Manager: refreshes the RoscomVPN geo files used by Xray. Run with --no-restart to skip the restart.
set -u
DIR="$XUI_DIR/bin"
TMP=\$(mktemp -d) || exit 1
trap 'rm -rf "\$TMP"' EXIT
changed=0
fetch() {
    repo="\$1"; file="\$2"; target="\$3"
    base="https://github.com/hydraponique/\$repo/releases/latest/download"
    curl -fsSL --retry 3 --connect-timeout 20 -o "\$TMP/\$file" "\$base/\$file" || { echo "download of \$file failed"; return 1; }
    curl -fsSL --retry 3 --connect-timeout 20 -o "\$TMP/\$file.sha256" "\$base/\$file.sha256" || { echo "download of \$file.sha256 failed"; return 1; }
    expected=\$(awk '{print \$1; exit}' "\$TMP/\$file.sha256")
    actual=\$(sha256sum "\$TMP/\$file" | awk '{print \$1}')
    [ -n "\$expected" ] && [ "\$expected" = "\$actual" ] || { echo "checksum mismatch for \$file"; return 1; }
    [ -s "\$TMP/\$file" ] || { echo "\$file is empty"; return 1; }
    if [ -f "\$DIR/\$target" ] && [ "\$(sha256sum "\$DIR/\$target" | awk '{print \$1}')" = "\$actual" ]; then
        echo "\$target is current (\$actual)"
        return 0
    fi
    mkdir -p "\$DIR"
    cp -f "\$TMP/\$file" "\$DIR/\$target.new" && chmod 644 "\$DIR/\$target.new" && mv -f "\$DIR/\$target.new" "\$DIR/\$target"
    echo "\$target updated (\$actual)"
    changed=1
}
fetch roscomvpn-geoip geoip.dat geoip_roscomvpn.dat || exit 1
fetch roscomvpn-geosite geosite.dat geosite_roscomvpn.dat || exit 1
if [ "\$changed" = 1 ] && [ "\${1:-}" != "--no-restart" ]; then
    systemctl restart x-ui
fi
exit 0
UPDATER
    chmod 755 "$UPDATER"
}

write_timer() {
    cat > "$UNIT_DIR/$UNIT.service" <<UNITFILE
[Unit]
Description=XUI Manager: refresh RoscomVPN geo files for Xray
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=$UPDATER
UNITFILE
    cat > "$UNIT_DIR/$UNIT.timer" <<UNITFILE
[Unit]
Description=XUI Manager: weekly refresh of RoscomVPN geo files

[Timer]
OnCalendar=Mon *-*-* 04:30:00
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
UNITFILE
}

stage PREPARE
[ "$(id -u)" = 0 ] || fail E_NOT_ROOT "root privileges are required"
command -v systemctl >/dev/null 2>&1 || fail E_UNSUPPORTED "systemd is required"
[ -d "$XUI_DIR" ] || fail "E_INSTALL:missing" "3x-ui is not installed in $XUI_DIR"
ensure_cmd curl curl || fail E_DEPS "curl is not available"
ensure_cmd sha256sum coreutils || fail E_DEPS "sha256sum is not available"

stage GEODATA
case "$ACTION" in
    install)
        write_updater
        log "Downloading RoscomVPN geoip.dat and geosite.dat"
        "$UPDATER" --no-restart >>"$LOG" 2>&1 || fail E_GEODATA "could not download or verify the RoscomVPN geo files"
        [ -s "$XUI_DIR/bin/geoip_roscomvpn.dat" ] && [ -s "$XUI_DIR/bin/geosite_roscomvpn.dat" ] ||
            fail E_GEODATA "the geo files are missing after the download"
        write_timer
        systemctl daemon-reload >>"$LOG" 2>&1
        systemctl enable --now "$UNIT.timer" >>"$LOG" 2>&1 || log "WARNING: could not enable $UNIT.timer"
        log "Geo files installed; refreshed weekly by $UNIT.timer"
        ;;
    remove)
        systemctl disable --now "$UNIT.timer" >>"$LOG" 2>&1 || true
        rm -f "$UNIT_DIR/$UNIT.service" "$UNIT_DIR/$UNIT.timer" "$UPDATER"
        systemctl daemon-reload >>"$LOG" 2>&1 || true
        rm -f "$XUI_DIR/bin/geoip_roscomvpn.dat" "$XUI_DIR/bin/geosite_roscomvpn.dat"
        log "RoscomVPN geo files removed"
        ;;
    *)
        fail E_GEODATA "unknown action $ACTION"
        ;;
esac

stage DONE
finish 0
