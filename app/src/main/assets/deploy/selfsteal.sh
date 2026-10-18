#!/usr/bin/env bash
# XUI Manager self-steal: an HTTPS decoy site for the server's own domain. Reality forwards every connection that is
# not a client to it, so a probe of DOMAIN:443 sees an ordinary website with a valid Let's Encrypt certificate.
#
#   Caddy (pinned release, checked by SHA-256) runs as xui-caddy and listens on 127.0.0.1 only: port 80 stays free
#   for acme.sh renewals, and nothing new is exposed to the internet.
#
# Standalone: started like run.sh with params.env (ACTION=install|remove, DOMAIN, OWNER_UID, OWNER_GID, CADDY_*),
#   talks through log/stage/exit and writes result.env with SELFSTEAL_PORT.
# Library: run.sh sources it with SELFSTEAL_LIB=1 and calls selfsteal_install after the panel certificate is in place.

SS_ETC="${XUI_MANAGER_SS_ETC:-/etc/xui-manager/selfsteal}"
SS_WWW="${XUI_MANAGER_SS_WWW:-/var/www/xui-selfsteal}"
SS_BIN_DIR="${XUI_MANAGER_BIN_DIR:-/usr/local/bin}"
SS_UNIT_DIR="${XUI_MANAGER_UNIT_DIR:-/etc/systemd/system}"
SS_USER=xui-caddy
SS_UNIT=xui-caddy
SS_CADDY="$SS_BIN_DIR/xui-caddy"
SS_HOOK="$SS_BIN_DIR/xui-manager-cert-hook"
SS_PORT=""

ss_arch() {
    case "$(uname -m)" in
        x86_64 | amd64) echo amd64 ;;
        aarch64 | arm64) echo arm64 ;;
        *) return 1 ;;
    esac
}

ss_install_caddy() {
    if [ -x "$SS_CADDY" ] && "$SS_CADDY" version 2>/dev/null | grep "^v$CADDY_VERSION " >/dev/null; then
        log "Caddy $CADDY_VERSION is already installed"
        return 0
    fi
    local arch sha url tmp actual
    arch=$(ss_arch) || fail E_UNSUPPORTED "Caddy has no build for $(uname -m)"
    if [ "$arch" = amd64 ]; then sha="$CADDY_SHA256_AMD64"; else sha="$CADDY_SHA256_ARM64"; fi
    url="https://github.com/caddyserver/caddy/releases/download/v$CADDY_VERSION/caddy_${CADDY_VERSION}_linux_${arch}.tar.gz"
    tmp="$JOB_DIR/caddy.tar.gz"
    log "Downloading Caddy $CADDY_VERSION ($arch)"
    curl -fsSL --retry 3 --connect-timeout 20 -o "$tmp" "$url" >>"$LOG" 2>&1 || fail E_CADDY_DOWNLOAD "could not download $url"
    actual=$(sha256sum "$tmp" | awk '{print $1}')
    if [ "$actual" != "$sha" ]; then
        rm -f "$tmp"
        fail E_CADDY_HASH "Caddy archive checksum mismatch: $actual"
    fi
    rm -rf "$JOB_DIR/caddy" && mkdir -p "$JOB_DIR/caddy"
    tar -xzf "$tmp" -C "$JOB_DIR/caddy" caddy >>"$LOG" 2>&1 || fail E_CADDY_DOWNLOAD "could not unpack the Caddy archive"
    mkdir -p "$SS_BIN_DIR"
    cp -f "$JOB_DIR/caddy/caddy" "$SS_CADDY.new" && chmod 755 "$SS_CADDY.new" && mv -f "$SS_CADDY.new" "$SS_CADDY"
    rm -rf "$tmp" "$JOB_DIR/caddy"
    log "Caddy $CADDY_VERSION installed, sha256 $actual"
}

ss_ensure_user() {
    id -u "$SS_USER" >/dev/null 2>&1 && return 0
    if command -v useradd >/dev/null 2>&1; then
        useradd --system --user-group --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin "$SS_USER" >>"$LOG" 2>&1
    elif command -v adduser >/dev/null 2>&1; then
        adduser -S -D -H -s /sbin/nologin "$SS_USER" >>"$LOG" 2>&1 && addgroup -S "$SS_USER" >>"$LOG" 2>&1
        addgroup "$SS_USER" "$SS_USER" >>"$LOG" 2>&1 || true
    fi
    id -u "$SS_USER" >/dev/null 2>&1 || fail E_SELFSTEAL "could not create the $SS_USER user"
}

# ss_cert: makes sure CERT_FILE/KEY_FILE hold a trusted certificate for DOMAIN, issuing one if needed.
ss_cert() {
    SERVER_HOST="$DOMAIN"
    SSL_MODE=domain
    if [ -n "${KEY_FILE:-}" ] && [ -s "${KEY_FILE:-}" ] && cert_trusted "${CERT_FILE:-}"; then
        log "Using the panel certificate for $DOMAIN"
    elif [ -s "$CERT_ROOT/$DOMAIN/privkey.pem" ] && cert_trusted "$CERT_ROOT/$DOMAIN/fullchain.pem"; then
        CERT_FILE="$CERT_ROOT/$DOMAIN/fullchain.pem"
        KEY_FILE="$CERT_ROOT/$DOMAIN/privkey.pem"
        log "Using the existing certificate for $DOMAIN"
    else
        issue_cert
        cert_trusted "$CERT_FILE" || fail E_TLS "the issued certificate does not verify for $DOMAIN"
        log "Certificate for $DOMAIN issued by Let's Encrypt"
    fi
}

ss_copy_cert() {
    mkdir -p "$SS_ETC"
    chmod 755 "$SS_ETC"
    cp -f "$CERT_FILE" "$SS_ETC/fullchain.pem"
    cp -f "$KEY_FILE" "$SS_ETC/privkey.pem"
    chown "root:$SS_USER" "$SS_ETC/fullchain.pem" "$SS_ETC/privkey.pem" 2>/dev/null || true
    chmod 640 "$SS_ETC/fullchain.pem" "$SS_ETC/privkey.pem"
}

# After every renewal acme.sh restarts the panel (as before) and refreshes the decoy site's copy of the certificate.
ss_install_hook() {
    cat > "$SS_HOOK" <<HOOK
#!/bin/sh
# Installed by XUI Manager: runs after acme.sh renews the certificate for $DOMAIN.
systemctl restart x-ui 2>/dev/null || true
if [ -f "$SS_ETC/Caddyfile" ]; then
    cp -f "$CERT_FILE" "$SS_ETC/fullchain.pem"
    cp -f "$KEY_FILE" "$SS_ETC/privkey.pem"
    chown root:$SS_USER "$SS_ETC/fullchain.pem" "$SS_ETC/privkey.pem" 2>/dev/null || true
    chmod 640 "$SS_ETC/fullchain.pem" "$SS_ETC/privkey.pem"
    systemctl restart $SS_UNIT 2>/dev/null || true
fi
HOOK
    chmod 755 "$SS_HOOK"
    {
        echo "DOMAIN=$DOMAIN"
        echo "CERT_FILE=$CERT_FILE"
        echo "KEY_FILE=$KEY_FILE"
    } > "$SS_ETC/source.env"
    if [ -x "$ACME" ]; then
        # acme.sh runs the reload command right away, which also restarts the panel once.
        "$ACME" --installcert -d "$DOMAIN" --key-file "$KEY_FILE" --fullchain-file "$CERT_FILE" \
            --reloadcmd "$SS_HOOK" >>"$LOG" 2>&1 ||
            log "WARNING: acme.sh does not manage $DOMAIN; renew the certificate yourself and run $SS_HOOK"
    else
        log "WARNING: acme.sh is not installed; renew the certificate yourself and run $SS_HOOK"
    fi
}

# --- decoy site: one of several templates with a name and colours of its own, so sites made by this app differ ---

ss_pick() {
    local n=$#
    local i=$((RANDOM % n + 1))
    eval "printf '%s' \"\${$i}\""
}

ss_brand() {
    local label first
    label=$(printf '%s' "$DOMAIN" | awk -F. '{ if (NF >= 2) print $(NF-1); else print $1 }' | tr -cd 'a-z0-9-')
    [ -n "$label" ] || label="studio"
    first=$(printf '%s' "${label:0:1}" | tr 'a-z' 'A-Z')
    printf '%s%s' "$first" "${label:1}"
}

ss_site() {
    local brand accent year template
    brand=$(ss_brand)
    accent=$(ss_pick "#2563eb" "#0d9488" "#7c3aed" "#db2777" "#ea580c" "#16a34a" "#0891b2" "#4f46e5")
    year=$(date +%Y)
    template=$(ss_pick cloud studio notes)
    mkdir -p "$SS_WWW"
    case "$template" in
        cloud) ss_site_cloud "$brand" "$accent" "$year" ;;
        studio) ss_site_studio "$brand" "$accent" "$year" ;;
        *) ss_site_notes "$brand" "$accent" "$year" ;;
    esac > "$SS_WWW/index.html"
    printf 'User-agent: *\nAllow: /\n' > "$SS_WWW/robots.txt"
    chmod 755 "$SS_WWW"
    chmod 644 "$SS_WWW/index.html" "$SS_WWW/robots.txt"
    log "Decoy site generated ($template template)"
}

ss_site_cloud() {
    cat <<HTML
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$1 — Cloud infrastructure</title>
<style>
:root { --accent: $2; }
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, "Segoe UI", Roboto, Arial, sans-serif; background: #0b1020; color: #e5e7eb; line-height: 1.6; }
header { display: flex; justify-content: space-between; align-items: center; max-width: 1040px; margin: 0 auto; padding: 24px; }
.logo { font-weight: 700; font-size: 20px; }
.logo span { color: var(--accent); }
nav a { color: #9ca3af; text-decoration: none; margin-left: 24px; font-size: 15px; }
main { max-width: 1040px; margin: 0 auto; padding: 64px 24px; }
h1 { font-size: 44px; line-height: 1.15; max-width: 720px; }
.lead { color: #9ca3af; font-size: 18px; margin: 20px 0 36px; max-width: 620px; }
.btn { display: inline-block; background: var(--accent); color: #fff; padding: 12px 22px; border-radius: 8px; text-decoration: none; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 20px; margin-top: 72px; }
.card { background: #111832; border: 1px solid #1f2a4a; border-radius: 12px; padding: 24px; }
.card h3 { margin-bottom: 8px; }
.card p { color: #9ca3af; font-size: 15px; }
footer { text-align: center; color: #6b7280; font-size: 14px; padding: 40px 24px; }
</style>
</head>
<body>
<header><div class="logo">$1<span>.</span></div><nav><a href="#">Platform</a><a href="#">Pricing</a><a href="#">Docs</a></nav></header>
<main>
<h1>Infrastructure that stays out of your way</h1>
<p class="lead">Managed compute, storage and private networking for teams that would rather ship than babysit servers.</p>
<a class="btn" href="#">Contact sales</a>
<div class="grid">
<div class="card"><h3>Compute</h3><p>Dedicated vCPU instances with predictable performance and hourly billing.</p></div>
<div class="card"><h3>Object storage</h3><p>S3-compatible buckets replicated across availability zones.</p></div>
<div class="card"><h3>Private network</h3><p>Isolated networks with built-in firewalls and floating addresses.</p></div>
</div>
</main>
<footer>&copy; $3 $1. All rights reserved.</footer>
</body>
</html>
HTML
}

ss_site_studio() {
    cat <<HTML
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$1 Studio</title>
<style>
:root { --accent: $2; }
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: Georgia, "Times New Roman", serif; background: #faf8f5; color: #1f2937; line-height: 1.7; }
.wrap { max-width: 880px; margin: 0 auto; padding: 48px 24px; }
header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 96px; }
.name { font-size: 22px; letter-spacing: 0.04em; }
header a { color: #4b5563; font-family: Arial, sans-serif; font-size: 14px; text-decoration: none; margin-left: 20px; }
h1 { font-size: 52px; font-weight: normal; line-height: 1.1; }
h1 em { color: var(--accent); font-style: normal; }
.intro { font-size: 19px; color: #4b5563; margin: 28px 0 80px; max-width: 600px; }
.work { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 28px; }
.item { border-top: 2px solid #1f2937; padding-top: 16px; }
.item h2 { font-size: 20px; font-weight: normal; }
.item p { font-family: Arial, sans-serif; font-size: 14px; color: #6b7280; }
footer { margin-top: 96px; font-family: Arial, sans-serif; font-size: 13px; color: #9ca3af; }
</style>
</head>
<body>
<div class="wrap">
<header><div class="name">$1 Studio</div><nav><a href="#">Work</a><a href="#">About</a><a href="#">Contact</a></nav></header>
<h1>Brands and products, <em>carefully made.</em></h1>
<p class="intro">We are a small independent studio working on identity, editorial design and websites for people who care about details.</p>
<div class="work">
<div class="item"><h2>Northwind Coffee</h2><p>Identity, packaging</p></div>
<div class="item"><h2>Atlas Quarterly</h2><p>Editorial design</p></div>
<div class="item"><h2>Field Notes App</h2><p>Product design, website</p></div>
</div>
<footer>&copy; $3 $1 Studio</footer>
</div>
</body>
</html>
HTML
}

ss_site_notes() {
    cat <<HTML
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$1 notes</title>
<style>
:root { --accent: $2; }
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, "Segoe UI", Roboto, Arial, sans-serif; background: #fff; color: #111827; line-height: 1.7; }
.wrap { max-width: 680px; margin: 0 auto; padding: 56px 24px; }
header { margin-bottom: 56px; }
header h1 { font-size: 26px; }
header p { color: #6b7280; }
article { padding: 22px 0; border-bottom: 1px solid #e5e7eb; }
article time { font-size: 13px; color: #9ca3af; }
article h2 { font-size: 20px; margin: 4px 0 6px; }
article h2 a { color: inherit; text-decoration: none; }
article h2 a:hover { color: var(--accent); }
article p { color: #4b5563; }
footer { margin-top: 48px; color: #9ca3af; font-size: 14px; }
</style>
</head>
<body>
<div class="wrap">
<header><h1>$1 notes</h1><p>Small write-ups about servers, networks and the tools I use.</p></header>
<article><time>$3</time><h2><a href="#">Moving a home lab to a single box</a></h2><p>What I kept, what I dropped and why fewer moving parts won.</p></article>
<article><time>$3</time><h2><a href="#">Notes on backing up a Postgres database</a></h2><p>A boring setup that has survived two disk failures so far.</p></article>
<article><time>$3</time><h2><a href="#">Reading logs without losing your mind</a></h2><p>A few shell one-liners I reach for every week.</p></article>
<footer>&copy; $3 $1</footer>
</div>
</body>
</html>
HTML
}

ss_write_config() {
    cat > "$SS_ETC/Caddyfile" <<CADDY
{
	admin off
	auto_https off
	persist_config off
}

https://$DOMAIN:$SS_PORT {
	bind 127.0.0.1
	tls $SS_ETC/fullchain.pem $SS_ETC/privkey.pem
	root * $SS_WWW
	file_server
	header -Server
}
CADDY
    chmod 644 "$SS_ETC/Caddyfile"
    cat > "$SS_UNIT_DIR/$SS_UNIT.service" <<UNIT
[Unit]
Description=XUI Manager self-steal site for $DOMAIN (Caddy)
After=network-online.target
Wants=network-online.target

[Service]
User=$SS_USER
Group=$SS_USER
ExecStart=$SS_CADDY run --config $SS_ETC/Caddyfile --adapter caddyfile
Restart=on-failure
RestartSec=5
Environment=XDG_DATA_HOME=/var/lib/$SS_UNIT XDG_CONFIG_HOME=/var/lib/$SS_UNIT
StateDirectory=$SS_UNIT
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
UNIT
}

# selfsteal_install: sets SS_PORT to the local port Reality must forward to.
selfsteal_install() {
    [ -n "${DOMAIN:-}" ] || fail E_SELFSTEAL "self-steal needs a domain"
    ensure_cmd tar tar || fail E_DEPS "tar is not available"
    ss_cert
    ss_install_caddy
    ss_ensure_user
    # A previous run's site must not count as "port in use".
    systemctl stop "$SS_UNIT" >>"$LOG" 2>&1 || true
    SS_PORT=$(pick_port "${SELFSTEAL_PORT:-8443}") || fail "E_PORT_BUSY:8443" "no free local port for the decoy site"
    ss_copy_cert
    ss_site
    ss_write_config
    "$SS_CADDY" validate --config "$SS_ETC/Caddyfile" --adapter caddyfile >>"$LOG" 2>&1 ||
        fail E_SELFSTEAL "Caddy rejected its configuration"
    ss_install_hook
    systemctl daemon-reload >>"$LOG" 2>&1
    systemctl enable "$SS_UNIT" >>"$LOG" 2>&1
    systemctl restart "$SS_UNIT" >>"$LOG" 2>&1 || fail E_SELFSTEAL "could not start the decoy site"
    local started=0
    for _ in $(seq 1 15); do
        if port_busy "$SS_PORT"; then
            started=1
            break
        fi
        sleep 1
    done
    [ "$started" = 1 ] || fail E_SELFSTEAL "the decoy site is not listening on 127.0.0.1:$SS_PORT"
    local version
    version=$(curl -sS -o /dev/null --max-time 15 -w '%{http_version}' --resolve "$DOMAIN:$SS_PORT:127.0.0.1" \
        "https://$DOMAIN:$SS_PORT/" 2>>"$LOG") || fail E_SELFSTEAL "the decoy site does not answer over HTTPS with a valid certificate"
    log "Decoy site answers on 127.0.0.1:$SS_PORT (HTTP/$version) with the certificate for $DOMAIN"
}

selfsteal_remove() {
    systemctl disable --now "$SS_UNIT" >>"$LOG" 2>&1 || true
    rm -f "$SS_UNIT_DIR/$SS_UNIT.service"
    systemctl daemon-reload >>"$LOG" 2>&1 || true
    if [ -f "$SS_ETC/source.env" ] && [ -x "$ACME" ]; then
        local domain cert key
        domain=$(sed -n 's/^DOMAIN=//p' "$SS_ETC/source.env")
        cert=$(sed -n 's/^CERT_FILE=//p' "$SS_ETC/source.env")
        key=$(sed -n 's/^KEY_FILE=//p' "$SS_ETC/source.env")
        "$ACME" --installcert -d "$domain" --key-file "$key" --fullchain-file "$cert" \
            --reloadcmd "systemctl restart x-ui 2>/dev/null || true" >>"$LOG" 2>&1 || true
    fi
    rm -rf "$SS_ETC" "$SS_WWW" "/var/lib/$SS_UNIT"
    rm -f "$SS_HOOK" "$SS_CADDY"
    if id -u "$SS_USER" >/dev/null 2>&1; then
        if command -v userdel >/dev/null 2>&1; then userdel "$SS_USER" >>"$LOG" 2>&1 || true; else deluser "$SS_USER" >>"$LOG" 2>&1 || true; fi
    fi
    log "Decoy site removed"
}

# ---------------------------------------------------------------------------------------------------------

if [ "${SELFSTEAL_LIB:-0}" != 1 ]; then
    set -u
    set -o pipefail

    JOB_DIR="$(cd "$(dirname "$0")" && pwd)"
    cd "$JOB_DIR" || exit 90
    LOG="$JOB_DIR/log"
    echo $$ > "$JOB_DIR/pid"
    # shellcheck source=/dev/null
    . "$JOB_DIR/params.env"

    CERT_ROOT="${XUI_MANAGER_CERT_ROOT:-/root/cert}"
    ACME="${XUI_MANAGER_ACME:-/root/.acme.sh/acme.sh}"
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
    ensure_cmd curl curl || fail E_DEPS "curl is not available"
    ensure_cmd openssl openssl || fail E_DEPS "openssl is not available"
    ensure_cmd sha256sum coreutils || fail E_DEPS "sha256sum is not available"

    stage SELFSTEAL
    case "$ACTION" in
        install)
            selfsteal_install
            (
                umask 077
                echo "SELFSTEAL_PORT=$SS_PORT" > "$JOB_DIR/result.env.tmp"
            )
            own "$JOB_DIR/result.env.tmp"
            mv -f "$JOB_DIR/result.env.tmp" "$JOB_DIR/result.env"
            ;;
        remove)
            selfsteal_remove
            ;;
        *)
            fail E_SELFSTEAL "unknown action $ACTION"
            ;;
    esac

    stage DONE
    finish 0
fi
