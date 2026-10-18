# XUI Manager precheck. Read-only, runs as the login user through `sh -s`; prints key=value lines.
# The app prepends DOMAIN='…' when the server has a domain, to check that it points here.
echo "uid=$(id -u)"
echo "gid=$(id -g)"
echo "home=$HOME"
echo "arch=$(uname -m)"
if [ -r /etc/os-release ]; then
    . /etc/os-release
fi
echo "os=${ID:-unknown}"
echo "os_version=${VERSION_ID:-}"
if command -v systemctl >/dev/null 2>&1; then echo "systemd=1"; else echo "systemd=0"; fi
if [ "$(id -u)" = "0" ]; then
    echo "sudo=root"
elif command -v sudo >/dev/null 2>&1; then
    if sudo -n true >/dev/null 2>&1; then echo "sudo=nopasswd"; else echo "sudo=password"; fi
else
    echo "sudo=none"
fi
# The override exists only for the app's tests; deployments always use /usr/local/x-ui.
xui_bin="${XUI_MANAGER_XUI_DIR:-/usr/local/x-ui}/x-ui"
if [ -x "$xui_bin" ]; then
    echo "xui_installed=1"
    echo "xui_version=$("$xui_bin" -v 2>/dev/null | head -n 1 | tr -d '\r')"
else
    echo "xui_installed=0"
fi
if [ -n "${DOMAIN:-}" ]; then
    resolved=""
    if command -v getent >/dev/null 2>&1; then
        resolved=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')
    fi
    if [ -z "$resolved" ] && command -v dig >/dev/null 2>&1; then
        resolved=$(dig +short A "$DOMAIN" 2>/dev/null | grep -E '^[0-9.]+$' | sort -u | tr '\n' ' ')
    fi
    echo "domain_ips=$resolved"
    # Addresses this server answers on: its own interfaces plus the public address seen from outside (NAT).
    local_ips=""
    if command -v ip >/dev/null 2>&1; then
        local_ips=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | tr '\n' ' ')
    elif command -v hostname >/dev/null 2>&1; then
        local_ips=$(hostname -I 2>/dev/null)
    fi
    public_ip=""
    for url in https://api.ipify.org https://ipv4.icanhazip.com https://ifconfig.me/ip; do
        if command -v curl >/dev/null 2>&1; then
            public_ip=$(curl -4 -fsS --max-time 4 "$url" 2>/dev/null | tr -d '[:space:]')
        elif command -v wget >/dev/null 2>&1; then
            public_ip=$(wget -4 -qO- -T 4 "$url" 2>/dev/null | tr -d '[:space:]')
        fi
        case "$public_ip" in *.*.*.*) break ;; *) public_ip="" ;; esac
    done
    echo "server_ips=$local_ips $public_ip"
fi
