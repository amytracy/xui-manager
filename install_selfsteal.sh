#!/usr/bin/env bash
# ==============================================================================
# Xray VLESS-Reality "Self-Steal" All-in-One Installer
# Стек: Xray-core (порт 443) + Caddy (127.0.0.1:8443) + Let's Encrypt / Certbot
# Авторская сборка на основе caddy.sh и server.txt
# ==============================================================================
set -euo pipefail

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log_info()    { echo -e "${CYAN}[INFO]${NC} $*"; }
log_ok()      { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }
log_header()  { echo -e "\n${BOLD}${PURPLE}=== $* ===${NC}\n"; }

# 1. Проверка прав root
if [[ $EUID -ne 0 ]]; then
    log_warn "Скрипт требует прав root. Перезапуск через sudo..."
    exec sudo bash "$0" "$@"
fi

# 2. Аргументы командной строки или интерактивный ввод
DOMAIN="${1:-}"
CADDY_PORT="${2:-8443}"
CLIENT_NAME="${3:-}"
FINGERPRINT="${4:-}"

echo -e "${BOLD}${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║          Xray VLESS-Reality Self-Steal Installer               ║"
echo "║          Маскировка под собственный реальный сайт (Caddy)      ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

if [[ -z "$DOMAIN" ]]; then
    read -rp "Введите ваш домен (например, files.example.com): " DOMAIN
fi

# Очистка домена от пробелов, http://, https:// и слэшей
DOMAIN="$(echo "$DOMAIN" | sed -e 's|^[^/]*//||' -e 's|/.*$||' | tr -d ' ')"

if [[ -z "$DOMAIN" ]]; then
    log_error "Домен не может быть пустым!"
    exit 1
fi

if [[ -z "$CLIENT_NAME" ]]; then
    CLIENT_NAME="Reality-${DOMAIN}"
fi

# Выбор TLS Fingerprint (uTLS)
if [[ -z "$FINGERPRINT" ]]; then
    echo ""
    echo -e "${CYAN}Выберите TLS Fingerprint (клиентский отпечаток TLS):${NC}"
    echo "  1) chrome  (по умолчанию, максимальная схожесть с браузерами)"
    echo "  2) firefox (как в вашем шаблоне server.txt)"
    echo "  3) qq      (Tencent QQ Browser)"
    echo "  4) safari  (Apple Safari)"
    read -rp "Ваш выбор [1-4, по умолчанию 1]: " FP_CHOICE
    case "${FP_CHOICE:-1}" in
        2|firefox) FINGERPRINT="firefox" ;;
        3|qq)      FINGERPRINT="qq" ;;
        4|safari)  FINGERPRINT="safari" ;;
        *)         FINGERPRINT="chrome" ;;
    esac
fi

WEB_ROOT="/var/www/${DOMAIN}"
XRAY_CONFIG="/usr/local/etc/xray/config.json"
CADDY_OVERRIDE_DIR="/etc/systemd/system/caddy.service.d"

log_info "Домен:        ${GREEN}${DOMAIN}${NC}"
log_info "Порт Caddy:   ${GREEN}${CADDY_PORT}${NC} (локальный fallback: 127.0.0.1:${CADDY_PORT})"
log_info "Внешний порт: ${GREEN}443${NC} (Xray VLESS Reality)"
log_info "Fingerprint:  ${GREEN}${FINGERPRINT}${NC}"
log_info "Web root:     ${GREEN}${WEB_ROOT}${NC}"

# 3. Определение внешнего IP сервера и проверка DNS
log_header "1. Проверка сетевого адреса и DNS"
SERVER_IP=""
for ip_service in "https://api.ipify.org" "https://icanhazip.com" "https://ifconfig.me" "https://ipinfo.io/ip"; do
    SERVER_IP=$(curl -4 -s --max-time 4 "$ip_service" 2>/dev/null || true)
    if [[ -n "$SERVER_IP" ]]; then
        break
    fi
done

if [[ -z "$SERVER_IP" ]]; then
    SERVER_IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7}' || true)
fi

if [[ -z "$SERVER_IP" ]]; then
    log_warn "Не удалось автоматически определить внешний IP. Введите его вручную:"
    read -rp "IP сервера: " SERVER_IP
else
    log_ok "Внешний IPv4 сервера: ${GREEN}${SERVER_IP}${NC}"
fi

# Проверка DNS-записи домена
DOMAIN_IP=""
if command -v getent &>/dev/null; then
    DOMAIN_IP=$(getent ahosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | head -n1 || true)
elif command -v dig &>/dev/null; then
    DOMAIN_IP=$(dig +short A "$DOMAIN" 2>/dev/null | head -n1 || true)
elif command -v ping &>/dev/null; then
    DOMAIN_IP=$(ping -c 1 "$DOMAIN" 2>/dev/null | head -n 1 | grep -oE '\((([0-9]{1,3}\.){3}[0-9]{1,3})\)' | tr -d '()' || true)
fi

if [[ -n "$DOMAIN_IP" ]]; then
    log_info "DNS-запись домена ${DOMAIN} указывает на: ${GREEN}${DOMAIN_IP}${NC}"
    if [[ "$DOMAIN_IP" != "$SERVER_IP" ]]; then
        echo ""
        log_warn "ВНИМАНИЕ! IP домена (${DOMAIN_IP}) не совпадает с IP сервера (${SERVER_IP})!"
        log_warn "Для корректной работы Self-Steal и выпуска сертификата Let's Encrypt А-запись домена должна указывать на этот сервер."
        read -rp "Всё равно продолжить? (y/N): " CONTINUE_SETUP
        if [[ "$CONTINUE_SETUP" != "y" && "$CONTINUE_SETUP" != "Y" ]]; then
            log_error "Установка прервана. Направьте А-запись домена на ${SERVER_IP} и запустите скрипт заново."
            exit 1
        fi
    else
        log_ok "DNS проверен: домен успешно указывает на этот сервер."
    fi
else
    log_warn "Не удалось проверить DNS для ${DOMAIN}. Убедитесь, что А-запись указывает на ${SERVER_IP}."
fi

# 4. Включение TCP BBR
log_header "2. Оптимизация сети (TCP BBR)"
if sysctl net.ipv4.tcp_congestion_control 2>/dev/null | grep -q bbr; then
    log_ok "TCP BBR уже активен."
else
    log_info "Включаем алгоритм перегрузки TCP BBR..."
    cat > /etc/sysctl.d/99-xray-bbr.conf <<EOF
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
EOF
    sysctl --system >/dev/null 2>&1 || true
    log_ok "TCP BBR успешно настроен."
fi

# 5. Установка базовых утилит
log_header "3. Установка системных зависимостей"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y debian-keyring debian-archive-keyring apt-transport-https \
    curl wget socat jq qrencode openssl certbot ufw gpg

# Освобождаем порты 80/443 от apache2 / nginx, если они мешают
for srv in apache2 nginx httpd; do
    if systemctl is-active --quiet "$srv" 2>/dev/null; then
        log_warn "Служба $srv занимает веб-порты. Останавливаем и отключаем $srv..."
        systemctl stop "$srv" 2>/dev/null || true
        systemctl disable "$srv" 2>/dev/null || true
    fi
done

# 6. Получение / проверка SSL сертификата
log_header "4. Проверка и выпуск SSL-сертификата"

CERT_FILE=""
KEY_FILE=""

# Проверяем пути:
# 1) /root/cert/${DOMAIN} (стандарт 3x-ui / предыдущего caddy.sh)
# 2) /etc/letsencrypt/live/${DOMAIN}
if [[ -f "/root/cert/${DOMAIN}/fullchain.pem" && -f "/root/cert/${DOMAIN}/privkey.pem" ]]; then
    CERT_FILE="/root/cert/${DOMAIN}/fullchain.pem"
    KEY_FILE="/root/cert/${DOMAIN}/privkey.pem"
    log_ok "Найден существующий сертификат в /root/cert/${DOMAIN}"
elif [[ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" && -f "/etc/letsencrypt/live/${DOMAIN}/privkey.pem" ]]; then
    CERT_FILE="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"
    KEY_FILE="/etc/letsencrypt/live/${DOMAIN}/privkey.pem"
    log_ok "Найден существующий сертификат Let's Encrypt в /etc/letsencrypt/live/${DOMAIN}"
else
    log_info "Сертификат не обнаружен. Выпускаем бесплатный сертификат Let's Encrypt через Certbot..."
    
    # Временно останавливаем любые процессы на 80 порту перед standalone
    systemctl stop caddy 2>/dev/null || true
    
    certbot certonly --standalone \
        -d "${DOMAIN}" \
        --non-interactive \
        --agree-tos \
        --register-unsafely-without-email \
        --preferred-challenges http

    if [[ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]]; then
        CERT_FILE="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"
        KEY_FILE="/etc/letsencrypt/live/${DOMAIN}/privkey.pem"
        log_ok "Сертификат Let's Encrypt успешно получен!"
    else
        log_error "Не удалось выпустить сертификат Let's Encrypt."
        log_error "Проверьте, что порт 80 открыт наружу и домен ${DOMAIN} резолвится в ${SERVER_IP}."
        exit 1
    fi
fi

# Настраиваем хук обновления certbot для Caddy
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-caddy.sh <<'EOF'
#!/usr/bin/env bash
systemctl reload caddy 2>/dev/null || true
EOF
chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-caddy.sh

# 7. Установка и настройка Caddy
log_header "5. Установка и настройка веб-сервера Caddy"

if [[ ! -f /usr/share/keyrings/caddy-stable-archive-keyring.gpg ]]; then
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | \
        gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg --yes
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | \
        tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
    apt-get update -y
fi

if ! command -v caddy &>/dev/null; then
    apt-get install -y caddy
    log_ok "Caddy установлен."
else
    log_ok "Caddy уже установлен."
fi

# Создаем каталог сайта и заглушку
mkdir -p "${WEB_ROOT}"

# Генерируем современный красивый сайт-заглушку, выглядящий как реальный IT/Cloud сервис
cat > "${WEB_ROOT}/index.html" <<EOF
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${DOMAIN} | Cloud Edge Infrastructure & Network Solutions</title>
    <style>
        :root {
            --bg: #090d16;
            --surface: #111827;
            --border: #1f293d;
            --primary: #3b82f6;
            --accent: #60a5fa;
            --text: #f3f4f6;
            --muted: #94a3b8;
            --success: #10b981;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background-color: var(--bg);
            color: var(--text);
            line-height: 1.6;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }
        header {
            border-bottom: 1px solid var(--border);
            background: rgba(17, 24, 39, 0.8);
            backdrop-filter: blur(12px);
            position: sticky;
            top: 0;
            z-index: 50;
        }
        .nav-container {
            max-width: 1100px;
            margin: 0 auto;
            padding: 1rem 1.5rem;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .brand {
            font-size: 1.25rem;
            font-weight: 700;
            color: #fff;
            display: flex;
            align-items: center;
            gap: 0.5rem;
            letter-spacing: -0.025em;
        }
        .brand-dot {
            width: 10px;
            height: 10px;
            background: var(--primary);
            border-radius: 50%;
            box-shadow: 0 0 12px var(--primary);
        }
        .badge {
            display: inline-flex;
            align-items: center;
            gap: 0.4rem;
            background: rgba(16, 185, 129, 0.1);
            color: var(--success);
            padding: 0.35rem 0.85rem;
            border-radius: 9999px;
            font-size: 0.85rem;
            font-weight: 500;
            border: 1px solid rgba(16, 185, 129, 0.2);
        }
        .badge-dot {
            width: 6px;
            height: 6px;
            background: var(--success);
            border-radius: 50%;
            animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
        }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
        main {
            flex: 1;
            max-width: 1100px;
            margin: 0 auto;
            padding: 4rem 1.5rem;
            width: 100%;
        }
        .hero {
            text-align: center;
            margin-bottom: 4rem;
        }
        .hero h1 {
            font-size: 2.8rem;
            font-weight: 800;
            letter-spacing: -0.03em;
            margin-bottom: 1.2rem;
            background: linear-gradient(135deg, #ffffff 30%, #93c5fd 100%);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        .hero p {
            font-size: 1.15rem;
            color: var(--muted);
            max-width: 650px;
            margin: 0 auto 2rem;
        }
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 1.5rem;
            margin-bottom: 4rem;
        }
        .card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 2rem;
            transition: transform 0.2s ease, border-color 0.2s ease;
        }
        .card:hover {
            transform: translateY(-4px);
            border-color: var(--primary);
        }
        .card-icon {
            width: 42px;
            height: 42px;
            background: rgba(59, 130, 246, 0.1);
            color: var(--accent);
            border-radius: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 1.25rem;
            margin-bottom: 1.2rem;
        }
        .card h3 {
            font-size: 1.25rem;
            margin-bottom: 0.75rem;
            color: #fff;
        }
        .card p {
            color: var(--muted);
            font-size: 0.95rem;
        }
        .stats-banner {
            background: linear-gradient(135deg, rgba(30, 41, 59, 0.5) 0%, rgba(15, 23, 42, 0.8) 100%);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 2.5rem;
            display: flex;
            justify-content: space-around;
            flex-wrap: wrap;
            gap: 2rem;
            text-align: center;
        }
        .stat-item h4 {
            font-size: 2.2rem;
            font-weight: 700;
            color: #fff;
        }
        .stat-item p {
            color: var(--muted);
            font-size: 0.9rem;
            margin-top: 0.25rem;
        }
        footer {
            border-top: 1px solid var(--border);
            padding: 2rem 1.5rem;
            text-align: center;
            color: var(--muted);
            font-size: 0.875rem;
            margin-top: auto;
        }
    </style>
</head>
<body>
    <header>
        <div class="nav-container">
            <div class="brand">
                <span class="brand-dot"></span>
                <span>${DOMAIN}</span>
            </div>
            <div class="badge">
                <span class="badge-dot"></span>
                <span>Cluster Online: 99.99%</span>
            </div>
        </div>
    </header>

    <main>
        <section class="hero">
            <h1>Autonomous Edge Infrastructure</h1>
            <p>High-availability distributed routing platform, edge compute microservices, and low-latency transport protocols.</p>
        </section>

        <section class="grid">
            <div class="card">
                <div class="card-icon">⚡</div>
                <h3>Anycast Routing</h3>
                <p>Distributed BGP Anycast mesh ensuring optimal packet propagation and sub-10ms edge processing.</p>
            </div>
            <div class="card">
                <div class="card-icon">🛡️</div>
                <h3>Zero-Trust Perimeter</h3>
                <p>Cryptographic TLS inspection, automated mutual authentication, and proactive DDoS scrubbing algorithms.</p>
            </div>
            <div class="card">
                <div class="card-icon">🌐</div>
                <h3>Telemetry & Metrics</h3>
                <p>Real-time continuous packet tracing, link health diagnostics, and dynamic path optimization.</p>
            </div>
        </section>

        <section class="stats-banner">
            <div class="stat-item">
                <h4>99.99%</h4>
                <p>Network Availability</p>
            </div>
            <div class="stat-item">
                <h4>&lt; 15ms</h4>
                <p>Average Edge Latency</p>
            </div>
            <div class="stat-item">
                <h4>TLS 1.3</h4>
                <p>Post-Quantum Ready</p>
            </div>
        </section>
    </main>

    <footer>
        &copy; $(date +%Y) ${DOMAIN}. All rights reserved. Enterprise Distributed Systems.
    </footer>
</body>
</html>
EOF

log_ok "Сайт-заглушка сформирован в ${WEB_ROOT}/index.html"

# Создаем Caddyfile
# Caddy слушает:
# - порт 80: для ACME webroot-проверок и перенаправления обычных HTTP запросов
# - 127.0.0.1:${CADDY_PORT}: для приема fallback-запросов от Xray Reality
cat > /etc/caddy/Caddyfile <<EOF
{
    admin off
}

http://${DOMAIN}:80 {
    handle /.well-known/acme-challenge/* {
        root * ${WEB_ROOT}
        file_server
    }
    handle {
        redir https://{host}{uri} permanent
    }
}

${DOMAIN}:${CADDY_PORT} {
    bind 127.0.0.1
    tls ${CERT_FILE} ${KEY_FILE}
    root * ${WEB_ROOT}
    file_server
}
EOF

# Проверяем конфиг Caddy
caddy validate --config /etc/caddy/Caddyfile

# Настраиваем запуск Caddy от root (для беспрепятственного чтения сертификатов)
mkdir -p "${CADDY_OVERRIDE_DIR}"
cat > "${CADDY_OVERRIDE_DIR}/override.conf" <<EOF
[Service]
User=root
Group=root
EOF

systemctl daemon-reload
systemctl enable caddy
systemctl restart caddy
log_ok "Caddy запущен и слушает 127.0.0.1:${CADDY_PORT}"

# 8. Установка Xray-core
log_header "6. Установка Xray-core"

if ! command -v xray &>/dev/null; then
    log_info "Загрузка и установка официального релиза Xray..."
    bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
    log_ok "Xray-core установлен."
else
    log_ok "Xray-core уже установлен: $(xray version | head -n1)"
fi

# 9. Генерация ключей VLESS Reality
log_header "7. Генерация ключей VLESS Reality"

UUID=$(xray uuid)
X25519_OUT=$(xray x25519)

# В версиях Xray v25+ вывод изменился:
# Старый формат: "Private key: ..." и "Public key: ..."
# Новый формат v25/v26: "PrivateKey: ..." и "Password: ..." (где Password — это Public Key)
PRIVATE_KEY=$(echo "$X25519_OUT" | grep -Ei "Private[[:space:]]*Key:" | head -n1 | awk '{print $NF}' | tr -d ' \r\n' || true)
PUBLIC_KEY=$(echo "$X25519_OUT" | grep -Ei "(Public[[:space:]]*Key|Password):" | head -n1 | awk '{print $NF}' | tr -d ' \r\n' || true)

# Фолбэк по номерам строк, если метки не совпали
if [[ -z "$PRIVATE_KEY" ]]; then
    PRIVATE_KEY=$(echo "$X25519_OUT" | sed -n '1p' | awk '{print $NF}' | tr -d ' \r\n')
fi
if [[ -z "$PUBLIC_KEY" ]]; then
    PUBLIC_KEY=$(echo "$X25519_OUT" | sed -n '2p' | awk '{print $NF}' | tr -d ' \r\n')
fi
SHORT_ID=$(openssl rand -hex 8)

log_info "UUID:        ${GREEN}${UUID}${NC}"
log_info "Public Key:  ${GREEN}${PUBLIC_KEY}${NC}"
log_info "Short ID:    ${GREEN}${SHORT_ID}${NC}"

# 10. Формирование серверного конфига Xray
log_header "8. Запись серверного конфига Xray"

mkdir -p /usr/local/etc/xray/
if [[ -f "${XRAY_CONFIG}" ]]; then
    cp "${XRAY_CONFIG}" "${XRAY_CONFIG}.bak.$(date +%s)"
fi

cat > "${XRAY_CONFIG}" <<EOF
{
  "log": {
    "loglevel": "warning"
  },
  "inbounds": [
    {
      "port": 443,
      "protocol": "vless",
      "tag": "in-vless-reality",
      "settings": {
        "clients": [
          {
            "id": "${UUID}",
            "flow": "xtls-rprx-vision"
          }
        ],
        "decryption": "none"
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls", "quic"],
        "routeOnly": true
      },
      "streamSettings": {
        "network": "tcp",
        "tcpSettings": {
          "acceptProxyProtocol": false,
          "header": {
            "type": "none"
          }
        },
        "security": "reality",
        "realitySettings": {
          "show": false,
          "xver": 0,
          "target": "127.0.0.1:${CADDY_PORT}",
          "serverNames": [
            "${DOMAIN}"
          ],
          "privateKey": "${PRIVATE_KEY}",
          "minClientVer": "",
          "maxClientVer": "",
          "maxTimediff": 0,
          "shortIds": [
            "${SHORT_ID}"
          ],
          "settings": {
            "publicKey": "${PUBLIC_KEY}",
            "fingerprint": "${FINGERPRINT}",
            "serverName": "",
            "spiderX": "/"
          }
        }
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "freedom",
      "tag": "direct"
    },
    {
      "protocol": "blackhole",
      "tag": "block"
    }
  ]
}
EOF

chmod 644 "${XRAY_CONFIG}"

# Проверяем конфиг Xray
xray run -test -config "${XRAY_CONFIG}"
log_ok "Конфигурация Xray валидна."

# Перезапуск Xray
systemctl enable xray
systemctl restart xray
log_ok "Xray-core успешно перезапущен на порту 443."

# 11. Настройка сетевого экрана (UFW)
if command -v ufw &>/dev/null && ufw status | grep -qw "active"; then
    log_info "Настройка портов UFW..."
    ufw allow 80/tcp >/dev/null 2>&1 || true
    ufw allow 443/tcp >/dev/null 2>&1 || true
    log_ok "Порты 80/tcp и 443/tcp открыты в UFW."
fi

# 12. Формирование ссылки и конфигурационных файлов
log_header "9. Генерация клиентских данных"

ENCODED_CLIENT_NAME=$(printf %s "${CLIENT_NAME}" | jq -sRr @uri)
VLESS_URL="vless://${UUID}@${SERVER_IP}:443?security=reality&encryption=none&pbk=${PUBLIC_KEY}&headerType=none&fp=${FINGERPRINT}&spx=%2F&type=tcp&flow=xtls-rprx-vision&sni=${DOMAIN}&sid=${SHORT_ID}#${ENCODED_CLIENT_NAME}"

CLIENT_TXT_FILE="/root/xray-${DOMAIN}-client.txt"
SINGBOX_JSON_FILE="/root/xray-${DOMAIN}-singbox.json"

# Сохраняем текстовый конфиг
cat > "${CLIENT_TXT_FILE}" <<EOF
====================================================================
           Xray VLESS-Reality Self-Steal Client Configuration
====================================================================
Домен (SNI / Host):    ${DOMAIN}
IP сервера:            ${SERVER_IP}
Порт:                  443
Протокол:              VLESS
Безопасность:          Reality
UUID:                  ${UUID}
Flow:                  xtls-rprx-vision
Encryption:            none
Public Key (pbk):      ${PUBLIC_KEY}
Short ID (sid):        ${SHORT_ID}
Fingerprint (fp):      ${FINGERPRINT}
SpiderX:               /
Network:               tcp

--------------------------------------------------------------------
Ссылка для импорта (VLESS URI):
${VLESS_URL}
--------------------------------------------------------------------
Поддерживаемые клиенты:
- Windows:      v2rayN, sing-box, NekoBox
- Android:      v2rayNG, NekoBox, Happ, sing-box
- iOS:          Streisand, FoXray, Shadowrocket, V2Box, Happ
- macOS/Linux:  v2rayN, sing-box, NekoRay
====================================================================
EOF

# Сохраняем Sing-box Outbound JSON
cat > "${SINGBOX_JSON_FILE}" <<EOF
{
  "type": "vless",
  "tag": "${CLIENT_NAME}",
  "server": "${SERVER_IP}",
  "server_port": 443,
  "uuid": "${UUID}",
  "flow": "xtls-rprx-vision",
  "tls": {
    "enabled": true,
    "server_name": "${DOMAIN}",
    "utls": {
      "enabled": true,
      "fingerprint": "${FINGERPRINT}"
    },
    "reality": {
      "enabled": true,
      "public_key": "${PUBLIC_KEY}",
      "short_id": "${SHORT_ID}"
    }
  }
}
EOF

# Создаем удобную глобальную команду xray-info для быстрого просмотра QR и ссылки в любое время
cat > /usr/local/bin/xray-info <<EOF
#!/usr/bin/env bash
if command -v qrencode &>/dev/null; then
    echo ""
    echo -e "\033[1;36m=== QR-КОД ДЛЯ ПОДКЛЮЧЕНИЯ ===\033[0m"
    qrencode -t ANSIUTF8 "${VLESS_URL}"
fi
cat "${CLIENT_TXT_FILE}"
EOF
chmod +x /usr/local/bin/xray-info

# 13. Итоговый вывод в терминал
echo ""
echo -e "${BOLD}${GREEN}====================================================================${NC}"
echo -e "${BOLD}${GREEN}       Установка Xray VLESS-Reality (Self-Steal) ЗАВЕРШЕНА!        ${NC}"
echo -e "${BOLD}${GREEN}====================================================================${NC}"
echo ""

# Отображаем QR-код прямо в терминале
if command -v qrencode &>/dev/null; then
    echo -e "${CYAN}Отсканируйте QR-код в вашем приложении (v2rayNG, Streisand, Happ и др.):${NC}"
    echo ""
    qrencode -t ANSIUTF8 "${VLESS_URL}"
    echo ""
fi

echo -e "${BOLD}${YELLOW}=== Ссылка для импорта (скопируйте целиком): ===${NC}"
echo -e "${GREEN}${VLESS_URL}${NC}"
echo ""

echo -e "${BOLD}${CYAN}=== Параметры подключения: ===${NC}"
echo -e "Адрес (Address/Host): ${BOLD}${SERVER_IP}${NC}"
echo -e "Порт:                 ${BOLD}443${NC}"
echo -e "UUID:                 ${BOLD}${UUID}${NC}"
echo -e "Flow:                 ${BOLD}xtls-rprx-vision${NC}"
echo -e "SNI:                  ${BOLD}${DOMAIN}${NC}"
echo -e "Public Key:           ${BOLD}${PUBLIC_KEY}${NC}"
echo -e "Short ID:             ${BOLD}${SHORT_ID}${NC}"
echo -e "Fingerprint:          ${BOLD}${FINGERPRINT}${NC}"
echo ""

echo -e "${BOLD}${PURPLE}=== Сохраненные файлы: ===${NC}"
echo -e "• Текстовый конфиг:       ${GREEN}${CLIENT_TXT_FILE}${NC}"
echo -e "• Sing-box Outbound JSON: ${GREEN}${SINGBOX_JSON_FILE}${NC}"
echo -e "• Каталог сайта-заглушки: ${GREEN}${WEB_ROOT}${NC}"
echo -e "• Команда для вызова QR:  ${BOLD}xray-info${NC}"
echo ""

echo -e "${CYAN}Проверка работы маскировки (браузер / curl):${NC}"
echo -e "Если открыть ${BOLD}https://${DOMAIN}${NC} в обычном браузере, откроется сайт-заглушка с валидным SSL."
echo -e "Любые проверки цензоров (GFW, РКН) увидят легальный веб-сервер с оригинальным сертификатом!"
echo ""
