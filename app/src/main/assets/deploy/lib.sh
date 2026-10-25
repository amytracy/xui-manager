# XUI Manager shared shell functions, sourced by run.sh, selfsteal.sh and the maintenance scripts.
#
# Expects from the caller: JOB_DIR, LOG, OWNER_UID, OWNER_GID, FINISHED=0, IPTABLES_CHANGED=0, ESC, and for
# certificates SSL_MODE, SERVER_HOST, CERT_ROOT and ACME. CERT_RELOAD_CMD overrides what acme.sh runs after a renewal.
#
# Callers run with pipefail, so never end a pipeline with `grep -q`: it exits at the first match, the writer dies of
# SIGPIPE and the whole pipeline reads as false. Use `grep ... >/dev/null`, which reads its input to the end.

log() { printf '%s\n' "$*" >> "$LOG"; }

own() { chown "$OWNER_UID:$OWNER_GID" "$@" 2>/dev/null || true; }

stage() {
    printf '%s' "$1" > "$JOB_DIR/stage"
    log "==> $1"
}

finish() {
    printf '%s' "$1" > "$JOB_DIR/exit.tmp"
    own "$JOB_DIR/exit.tmp"
    mv -f "$JOB_DIR/exit.tmp" "$JOB_DIR/exit"
    FINISHED=1
}

fail() {
    local code="$1"
    shift
    log "ERROR: $*"
    rm -f "$JOB_DIR/install.sh"
    finish "$code"
    exit 1
}

on_exit() {
    local rc=$?
    if [ "$FINISHED" -ne 1 ]; then
        log "ERROR: runner stopped unexpectedly (exit $rc)"
        finish "E_UNEXPECTED:$rc"
    fi
}

SED_UNBUFFERED=""
if sed --version 2>/dev/null | grep GNU >/dev/null; then SED_UNBUFFERED="-u"; fi

# Strips colours and replaces anything that looks like a credential with asterisks.
mask() {
    sed $SED_UNBUFFERED -E \
        -e "s/${ESC}\[[0-9;]*[A-Za-z]//g" \
        -e 's/((Password|PASSWORD|Username|API Token|apiToken|Token|DSN|PGPASSWORD|WebBasePath|Access URL)[^:=]*[:=])[[:space:]]*.*/\1 ********/'
}

pkg_install() {
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -y >>"$LOG" 2>&1
        DEBIAN_FRONTEND=noninteractive apt-get install -y "$@" >>"$LOG" 2>&1
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y "$@" >>"$LOG" 2>&1
    elif command -v yum >/dev/null 2>&1; then
        yum install -y "$@" >>"$LOG" 2>&1
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache "$@" >>"$LOG" 2>&1
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm "$@" >>"$LOG" 2>&1
    elif command -v zypper >/dev/null 2>&1; then
        zypper -n install "$@" >>"$LOG" 2>&1
    else
        return 1
    fi
}

ensure_cmd() {
    command -v "$1" >/dev/null 2>&1 && return 0
    log "Installing $2"
    pkg_install "$2" || return 1
    command -v "$1" >/dev/null 2>&1
}

is_ipv4() { [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; }
is_ip() { is_ipv4 "$1" || [[ "$1" == *:* ]]; }

port_busy() {
    if command -v ss >/dev/null 2>&1; then
        ss -tln 2>/dev/null | awk 'NR > 1 {print $4}' | grep -E "[:.]$1\$" >/dev/null
    else
        netstat -tln 2>/dev/null | awk 'NR > 2 {print $4}' | grep -E "[:.]$1\$" >/dev/null
    fi
}

random_port() {
    if command -v shuf >/dev/null 2>&1; then
        shuf -i 20000-60000 -n 1
    else
        echo $(((RANDOM % 40000) + 20000))
    fi
}

in_list() {
    local needle="$1"
    shift
    local item
    for item in "$@"; do [ "$needle" = "$item" ] && return 0; done
    return 1
}

# pick_port PREFERRED [TAKEN...] prints PREFERRED if it is free, otherwise a random free port.
pick_port() {
    local port="$1"
    shift
    local tries=0
    while port_busy "$port" || in_list "$port" "$@"; do
        tries=$((tries + 1))
        [ "$tries" -gt 50 ] && return 1
        port=$(random_port)
    done
    echo "$port"
}

open_port() {
    local port="$1"
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -i '^Status: active' >/dev/null; then
        ufw allow "$port/tcp" >>"$LOG" 2>&1 && log "ufw: allowed $port/tcp"
        return 0
    fi
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        firewall-cmd --permanent --add-port="$port/tcp" >>"$LOG" 2>&1
        firewall-cmd --reload >>"$LOG" 2>&1
        log "firewalld: allowed $port/tcp"
        return 0
    fi
    if command -v iptables >/dev/null 2>&1; then
        local rules
        rules=$(iptables -S INPUT 2>/dev/null)
        if [ "${rules%%$'\n'*}" = "-P INPUT ACCEPT" ] && ! printf '%s\n' "$rules" | grep -- '-A INPUT' >/dev/null; then
            log "iptables: INPUT accepts all traffic, nothing to open for $port/tcp"
            return 0
        fi
        iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null ||
            iptables -I INPUT -p tcp --dport "$port" -j ACCEPT
        if command -v ip6tables >/dev/null 2>&1; then
            ip6tables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null ||
                ip6tables -I INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null
        fi
        IPTABLES_CHANGED=1
        log "iptables: allowed $port/tcp"
        return 0
    fi
    log "No active firewall found, $port/tcp needs no rule"
}

# close_port PORT: takes back the rule open_port added for PORT/tcp, in the same firewall open_port would pick.
close_port() {
    local port="$1"
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -i '^Status: active' >/dev/null; then
        ufw --force delete allow "$port/tcp" >>"$LOG" 2>&1 && log "ufw: removed the rule for $port/tcp"
        return 0
    fi
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        if firewall-cmd --permanent --query-port="$port/tcp" >/dev/null 2>&1; then
            firewall-cmd --permanent --remove-port="$port/tcp" >>"$LOG" 2>&1
            firewall-cmd --reload >>"$LOG" 2>&1
            log "firewalld: removed $port/tcp"
        fi
        return 0
    fi
    if command -v iptables >/dev/null 2>&1; then
        local removed=0
        while iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; do
            iptables -D INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || break
            removed=1
        done
        if command -v ip6tables >/dev/null 2>&1; then
            while ip6tables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null; do
                ip6tables -D INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || break
                removed=1
            done
        fi
        if [ "$removed" = 1 ]; then
            IPTABLES_CHANGED=1
            log "iptables: removed the rule for $port/tcp"
        fi
    fi
}

persist_iptables() {
    [ "$IPTABLES_CHANGED" = 1 ] || return 0
    if command -v netfilter-persistent >/dev/null 2>&1; then
        netfilter-persistent save >>"$LOG" 2>&1
    elif [ -d /etc/iptables ]; then
        iptables-save > /etc/iptables/rules.v4
        command -v ip6tables-save >/dev/null 2>&1 && ip6tables-save > /etc/iptables/rules.v6
    elif [ -f /etc/sysconfig/iptables ]; then
        iptables-save > /etc/sysconfig/iptables
    else
        log "WARNING: iptables rules are not persisted and will be lost on reboot (install iptables-persistent)"
    fi
}

# --- TLS: the panel only ever gets a publicly trusted certificate (Let's Encrypt), never a self-signed one ---

ca_bundle() {
    local f
    for f in /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt /etc/ssl/ca-bundle.pem /etc/ssl/cert.pem; do
        if [ -s "$f" ]; then
            echo "$f"
            return 0
        fi
    done
    return 1
}

# cert_trusted FILE: chains to a public root, covers SERVER_HOST and stays valid for at least another day.
cert_trusted() {
    local cert="$1" bundle check=-checkhost
    [ -n "$cert" ] && [ -s "$cert" ] || return 1
    bundle=$(ca_bundle) || return 1
    if is_ipv4 "$SERVER_HOST"; then check=-checkip; fi
    openssl x509 -in "$cert" -noout -checkend 86400 >/dev/null 2>&1 || return 1
    openssl x509 -in "$cert" -noout "$check" "$SERVER_HOST" 2>/dev/null | grep "does match" >/dev/null || return 1
    openssl verify -CAfile "$bundle" -untrusted "$cert" "$cert" >/dev/null 2>&1
}

# issue_cert: Let's Encrypt for SERVER_HOST through acme.sh standalone (HTTP-01 on port 80). Sets CERT_FILE/KEY_FILE.
issue_cert() {
    local dir profile=""
    case "$SSL_MODE" in
        ip) dir="$CERT_ROOT/ip" profile="--certificate-profile shortlived --days 6" ;;
        domain) dir="$CERT_ROOT/$SERVER_HOST" ;;
        *) fail E_TLS_UNAVAILABLE "Let's Encrypt needs a public IPv4 address or a domain name" ;;
    esac
    port_busy 80 && fail E_TLS_PORT80 "port 80 is in use; Let's Encrypt needs it to validate this server"
    open_port 80
    persist_iptables
    ensure_cmd socat socat || fail E_DEPS "socat is not available"
    if [ ! -x "$ACME" ]; then
        log "Installing acme.sh"
        (cd /root 2>/dev/null; curl -fsSL https://get.acme.sh | sh) >>"$LOG" 2>&1 || fail E_TLS "could not install acme.sh"
    fi
    "$ACME" --set-default-ca --server letsencrypt >>"$LOG" 2>&1
    log "Requesting a Let's Encrypt certificate for $SERVER_HOST"
    # shellcheck disable=SC2086
    "$ACME" --issue -d "$SERVER_HOST" --standalone --server letsencrypt $profile --httpport 80 --force >>"$LOG" 2>&1 ||
        fail E_TLS "Let's Encrypt could not validate $SERVER_HOST; port 80 must be reachable from the internet"
    mkdir -p "$dir" && chmod 700 "$dir"
    "$ACME" --installcert -d "$SERVER_HOST" --key-file "$dir/privkey.pem" --fullchain-file "$dir/fullchain.pem" \
        --reloadcmd "${CERT_RELOAD_CMD:-systemctl restart x-ui 2>/dev/null || true}" >>"$LOG" 2>&1 || true
    [ -s "$dir/fullchain.pem" ] && [ -s "$dir/privkey.pem" ] || fail E_TLS "the certificate files were not installed"
    chmod 600 "$dir/privkey.pem"
    CERT_FILE="$dir/fullchain.pem"
    KEY_FILE="$dir/privkey.pem"
}

# --- The installed panel (XUI_BIN) ---

# read_panel_settings: sets PANEL_PORT and WEB_BASE_PATH from the running panel's own settings.
read_panel_settings() {
    local current
    # Some 2.x releases print the current password here too: the output is parsed in memory and never logged.
    current=$("$XUI_BIN" setting -show true 2>/dev/null)
    PANEL_PORT=$(printf '%s\n' "$current" | sed -n 's/^port:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
    WEB_BASE_PATH=$(printf '%s\n' "$current" | sed -n 's/^webBasePath:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
    [[ "$PANEL_PORT" =~ ^[0-9]+$ ]] || fail "E_INSTALL:settings" "could not read the current panel port"
}

# read_panel_cert: sets CERT_FILE and KEY_FILE to the certificate the panel serves (empty when it has none).
read_panel_cert() {
    local current
    current=$("$XUI_BIN" setting -getCert true 2>/dev/null)
    CERT_FILE=$(printf '%s\n' "$current" | sed -n 's/^cert:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
    KEY_FILE=$(printf '%s\n' "$current" | sed -n 's/^key:[[:space:]]*//p' | head -n 1 | tr -d '[:space:]')
}

# issue_api_token: sets API_TOKEN to a new token named "xui-manager" (3x-ui recreates it, so an older one stops
# working), or leaves it empty on 2.x panels, which have no tokens and are reached with the login and password.
issue_api_token() {
    local setting_help
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
}
