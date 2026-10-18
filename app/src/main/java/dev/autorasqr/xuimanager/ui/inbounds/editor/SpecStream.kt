package dev.autorasqr.xuimanager.ui.inbounds.editor

import dev.autorasqr.xuimanager.data.inbound.editor.EditorDefaults
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Transport tab: the transport forms, protocols/hysteria.tsx, masque.tsx, sockopt.tsx and FinalMaskForm of 3x-ui v3.10.2. */
object SpecStream {
    private fun net(n: String): Visible = { InboundCapabilities.hasSelectableTransport(it) && it.network == n }
    private val SS = "streamSettings"

    fun fields(): List<Field> = listOf(
        ChoiceField(
            path = p("$SS.network"),
            label = Txt("Transport", "Транспорт"),
            options = {
                listOf(
                    Opt("tcp", "RAW"), Opt("kcp", "mKCP"), Opt("ws", "WebSocket"), Opt("grpc", "gRPC"),
                    Opt("httpupgrade", "HTTPUpgrade"), Opt("xhttp", "XHTTP"), Opt("xdrive", "XDRIVE"),
                )
            },
            onSelect = { d, value -> d.withNetwork(value) },
            visible = InboundCapabilities::hasSelectableTransport,
        ),
    ) + hysteria() + masque() + raw() + ws() + grpc() + httpUpgrade() + kcp() + xhttp() + xdrive() + listOf(sockopt()) + finalMask()

    private fun hysteria(): List<Field> {
        val h: Visible = { it.protocol == "hysteria" }
        val m = "$SS.hysteriaSettings.masquerade"
        return listOf(
            InfoField(Txt("Hysteria version", "Версия Hysteria"), { "2" }, visible = h),
            NumberField(p("$SS.hysteriaSettings.udpIdleTimeout"), Txt("UDP idle timeout, s", "Простой UDP, с"), min = 2, max = 600, visible = h),
            Section(
                Txt("Masquerade", "Маскировка"),
                listOf(
                    ChoiceField(
                        p("$m.type"), Txt("Type", "Тип"),
                        listOf(Opt("", "default (404 page)"), Opt("proxy", "proxy (reverse proxy)"), Opt("file", "file (serve directory)"), Opt("string", "string (fixed body)")),
                    ),
                    TextField(p("$m.url"), Txt("Upstream URL", "Адрес сайта"), placeholder = "https://www.example.com", visible = { it.s("$m.type") == "proxy" }),
                    SwitchField(p("$m.rewriteHost"), Txt("Rewrite Host", "Подменять Host"), visible = { it.s("$m.type") == "proxy" }),
                    SwitchField(p("$m.xForwarded"), Txt("X-Forwarded headers", "Заголовки X-Forwarded"), removeWhenOff = true, visible = { it.s("$m.type") == "proxy" }),
                    SwitchField(p("$m.insecure"), Txt("Skip upstream TLS check", "Не проверять TLS сайта"), visible = { it.s("$m.type") == "proxy" }),
                    TextField(p("$m.dir"), Txt("Directory", "Каталог"), placeholder = "/var/www/html", visible = { it.s("$m.type") == "file" }),
                    NumberField(p("$m.statusCode"), Txt("Status code", "Код ответа"), min = 0, max = 599, visible = { it.s("$m.type") == "string" }),
                    TextField(p("$m.content"), Txt("Body", "Тело ответа"), multiline = true, visible = { it.s("$m.type") == "string" }),
                    MapField(p("$m.headers"), Txt("Headers", "Заголовки"), visible = { it.s("$m.type") == "string" }),
                ),
                toggle = Toggle(p(m), { EditorDefaults.HYSTERIA_MASQUERADE }),
                visible = h,
            ),
        )
    }

    private fun masque(): List<Field> = listOf(
        TextField(p("$SS.masqueSettings.path"), Txt("Path", "Путь"), placeholder = EditorDefaults.MASQUE_DEFAULT_PATH, visible = { it.protocol == "masque" }),
    )

    private fun raw(): List<Field> {
        val r = net("tcp")
        val h = "$SS.tcpSettings.header"
        val http: Visible = { r(it) && it.s("$h.type") == "http" }
        return listOf(
            SwitchField(p("$SS.tcpSettings.acceptProxyProtocol"), Txt("Accept PROXY protocol", "Принимать PROXY protocol"), visible = r),
            ChoiceField(
                path = p("$h.type"), label = Txt("HTTP obfuscation", "HTTP-обфускация"),
                options = { listOf(Opt("none", "none"), Opt("http", "http")) }, default = "none",
                onSelect = { d, value -> d.set(p(h), if (value == "http") EditorDefaults.TCP_HTTP_HEADER else JsonObject(mapOf("type" to json("none")))) },
                visible = r,
            ),
            Section(
                Txt("Request", "Запрос"),
                listOf(
                    TextField(p("$h.request.version"), Txt("Version", "Версия"), placeholder = "1.1"),
                    TextField(p("$h.request.method"), Txt("Method", "Метод"), placeholder = "GET"),
                    TagsField(p("$h.request.path"), Txt("Paths", "Пути"), placeholder = "/"),
                    MapField(p("$h.request.headers"), Txt("Headers", "Заголовки"), multiValue = true, valueHint = "a, b"),
                ),
                visible = http,
            ),
            Section(
                Txt("Response", "Ответ"),
                listOf(
                    TextField(p("$h.response.version"), Txt("Version", "Версия"), placeholder = "1.1"),
                    TextField(p("$h.response.status"), Txt("Status", "Статус"), placeholder = "200"),
                    TextField(p("$h.response.reason"), Txt("Reason", "Пояснение"), placeholder = "OK"),
                    MapField(p("$h.response.headers"), Txt("Headers", "Заголовки"), multiValue = true, valueHint = "a, b"),
                ),
                visible = http,
            ),
        )
    }

    private fun ws(): List<Field> {
        val w = net("ws")
        val b = "$SS.wsSettings"
        return listOf(
            SwitchField(p("$b.acceptProxyProtocol"), Txt("Accept PROXY protocol", "Принимать PROXY protocol"), visible = w),
            TextField(p("$b.host"), Txt("Host"), visible = w),
            TextField(p("$b.path"), Txt("Path", "Путь"), placeholder = "/", visible = w),
            NumberField(p("$b.heartbeatPeriod"), Txt("Heartbeat period, s", "Период heartbeat, с"), min = 0, visible = w),
            MapField(p("$b.headers"), Txt("Headers", "Заголовки"), visible = w),
        )
    }

    private fun grpc(): List<Field> {
        val g = net("grpc")
        return listOf(
            TextField(p("$SS.grpcSettings.serviceName"), Txt("Service name", "Имя сервиса"), visible = g),
            TextField(p("$SS.grpcSettings.authority"), Txt("Authority"), visible = g),
            SwitchField(p("$SS.grpcSettings.multiMode"), Txt("Multi mode", "Multi mode"), visible = g),
        )
    }

    private fun httpUpgrade(): List<Field> {
        val u = net("httpupgrade")
        val b = "$SS.httpupgradeSettings"
        return listOf(
            SwitchField(p("$b.acceptProxyProtocol"), Txt("Accept PROXY protocol", "Принимать PROXY protocol"), visible = u),
            TextField(p("$b.host"), Txt("Host"), visible = u),
            TextField(p("$b.path"), Txt("Path", "Путь"), placeholder = "/", visible = u),
            MapField(p("$b.headers"), Txt("Headers", "Заголовки"), visible = u),
        )
    }

    private fun kcp(): List<Field> {
        val k = net("kcp")
        val b = "$SS.kcpSettings"
        return listOf(
            NumberField(p("$b.mtu"), Txt("MTU"), min = 576, max = 1460, visible = k),
            NumberField(p("$b.tti"), Txt("TTI, ms"), min = 10, max = 100, visible = k),
            NumberField(p("$b.uplinkCapacity"), Txt("Uplink, MB/s", "Отдача, МБ/с"), min = 0, visible = k),
            NumberField(p("$b.downlinkCapacity"), Txt("Downlink, MB/s", "Загрузка, МБ/с"), min = 0, visible = k),
            NumberField(p("$b.cwndMultiplier"), Txt("CWND multiplier", "Множитель CWND"), min = 1, visible = k),
            NumberField(p("$b.maxSendingWindow"), Txt("Max sending window", "Макс. окно отправки"), min = 0, visible = k),
        )
    }

    private fun xhttp(): List<Field> {
        val x = net("xhttp")
        val b = "$SS.xhttpSettings"
        fun mode(d: InboundDraft) = d.s("$b.mode").ifEmpty { "auto" }
        val placements = listOf(Opt("", "Default (path)"), Opt("path"), Opt("header"), Opt("cookie"), Opt("query"))
        return listOf(
            TextField(p("$b.host"), Txt("Host"), visible = x),
            TextField(p("$b.path"), Txt("Path", "Путь"), placeholder = "/", visible = x),
            ChoiceField(p("$b.mode"), Txt("Mode", "Режим"), opts("auto", "packet-up", "stream-up", "stream-one"), default = "auto", visible = x),
            TextField(p("$b.scMaxEachPostBytes"), Txt("Max bytes per POST", "Макс. байт на POST"), placeholder = "1000000", visible = { x(it) && mode(it) in setOf("packet-up", "auto") }),
            NumberField(p("$b.scMaxBufferedPosts"), Txt("Max buffered POSTs", "Макс. POST в буфере"), min = 0, visible = { x(it) && mode(it) in setOf("packet-up", "auto", "stream-up") }),
            TextField(p("$b.scMinPostsIntervalMs"), Txt("Min POST interval, ms", "Мин. интервал POST, мс"), placeholder = "e.g. 50-150", visible = { x(it) && mode(it) in setOf("packet-up", "auto") }),
            TextField(p("$b.scStreamUpServerSecs"), Txt("Stream-up server seconds", "Секунды stream-up на сервере"), placeholder = "20-80", visible = { x(it) && mode(it) == "stream-up" }),
            NumberField(p("$b.serverMaxHeaderBytes"), Txt("Server max header bytes", "Макс. заголовков на сервере, байт"), min = 0, placeholder = "0 (default)", visible = x),
            TextField(p("$b.xPaddingBytes"), Txt("Padding bytes", "Байты паддинга"), placeholder = "100-1000", visible = x),
            MapField(p("$b.headers"), Txt("Headers", "Заголовки"), visible = x),
            ChoiceField(p("$b.uplinkHTTPMethod"), Txt("Uplink HTTP method", "HTTP-метод отдачи"), listOf(Opt("", "Default (POST)"), Opt("POST"), Opt("PUT"), Opt("GET")), visible = x),
            SwitchField(p("$b.xPaddingObfsMode"), Txt("Padding obfuscation", "Обфускация паддинга"), visible = x),
            TextField(p("$b.xPaddingKey"), Txt("Padding key", "Ключ паддинга"), placeholder = "x_padding", visible = { x(it) && it.s("$b.xPaddingObfsMode") == "true" }),
            TextField(p("$b.xPaddingHeader"), Txt("Padding header", "Заголовок паддинга"), placeholder = "X-Padding", visible = { x(it) && it.s("$b.xPaddingObfsMode") == "true" }),
            ChoiceField(
                p("$b.xPaddingPlacement"), Txt("Padding placement", "Где паддинг"),
                listOf(Opt("", "Default (queryInHeader)"), Opt("queryInHeader"), Opt("header"), Opt("cookie"), Opt("query")),
                visible = { x(it) && it.s("$b.xPaddingObfsMode") == "true" },
            ),
            ChoiceField(
                p("$b.xPaddingMethod"), Txt("Padding method", "Метод паддинга"),
                listOf(Opt("", "Default (repeat-x)"), Opt("repeat-x"), Opt("tokenish")),
                visible = { x(it) && it.s("$b.xPaddingObfsMode") == "true" },
            ),
            ChoiceField(p("$b.sessionIDPlacement"), Txt("Session ID placement", "Где session ID"), placements, visible = x),
            TextField(p("$b.sessionIDKey"), Txt("Session ID key", "Ключ session ID"), placeholder = "x_session", visible = { x(it) && it.s("$b.sessionIDPlacement").let { v -> v.isNotEmpty() && v != "path" } }),
            ChoiceField(
                p("$b.sessionIDTable"), Txt("Session ID alphabet", "Алфавит session ID"),
                listOf(Opt("", "—")) + opts("ALPHABET", "Alphabet", "BASE36", "Base62", "HEX", "alphabet", "base36", "hex", "number"),
                visible = x,
            ),
            TextField(p("$b.sessionIDLength"), Txt("Session ID length", "Длина session ID"), placeholder = "8-16", visible = { x(it) && it.s("$b.sessionIDTable").isNotEmpty() }),
            ChoiceField(p("$b.seqPlacement"), Txt("Sequence placement", "Где номер пакета"), placements, visible = x),
            TextField(p("$b.seqKey"), Txt("Sequence key", "Ключ номера"), placeholder = "x_seq", visible = { x(it) && it.s("$b.seqPlacement").let { v -> v.isNotEmpty() && v != "path" } }),
            ChoiceField(
                p("$b.uplinkDataPlacement"), Txt("Uplink data placement", "Где данные отдачи"),
                listOf(Opt("", "Default (auto)"), Opt("auto"), Opt("body"), Opt("header"), Opt("cookie")),
                visible = { x(it) && mode(it) == "packet-up" },
            ),
            TextField(
                p("$b.uplinkDataKey"), Txt("Uplink data key", "Ключ данных отдачи"), placeholder = "x_data",
                visible = { x(it) && mode(it) == "packet-up" && it.s("$b.uplinkDataPlacement").let { v -> v.isNotEmpty() && v != "body" } },
            ),
            SwitchField(p("$b.noSSEHeader"), Txt("No SSE header", "Без заголовка SSE"), visible = x),
            Section(
                Txt("XMUX"),
                listOf(
                    TextField(p("$b.xmux.maxConcurrency"), Txt("Max concurrency", "Макс. параллельно"), placeholder = "16-32"),
                    NumberField(p("$b.xmux.maxConnections"), Txt("Max connections", "Макс. соединений"), asString = true, placeholder = "0"),
                    NumberField(p("$b.xmux.cMaxReuseTimes"), Txt("Max reuse times", "Макс. повторов"), asString = true, placeholder = "0"),
                    TextField(p("$b.xmux.hMaxRequestTimes"), Txt("Max requests", "Макс. запросов"), placeholder = "600-900"),
                    TextField(p("$b.xmux.hMaxReusableSecs"), Txt("Max reusable seconds", "Макс. секунд повтора"), placeholder = "1800-3000"),
                    NumberField(p("$b.xmux.hKeepAlivePeriod"), Txt("Keep-alive period", "Период keep-alive"), min = 0),
                ),
                toggle = Toggle(
                    path = p("$b.enableXmux"),
                    isOn = { it.s("$b.enableXmux") == "true" },
                    onOff = { d, on ->
                        val next = d.set(p("$b.enableXmux"), json(on))
                        if (on && next[p("$b.xmux")] == null) next.set(p("$b.xmux"), EditorDefaults.XMUX_FRESH) else next
                    },
                ),
                visible = x,
            ),
        )
    }

    private fun xdrive(): List<Field> {
        val x = net("xdrive")
        val b = "$SS.xdriveSettings"
        val optional = listOf(
            "segmentBytes" to Txt("Segment bytes", "Размер сегмента, байт"), "flushIntervalMs" to Txt("Flush interval, ms", "Интервал сброса, мс"),
            "pollIntervalMs" to Txt("Poll interval, ms", "Интервал опроса, мс"), "maxPollIntervalMs" to Txt("Max poll interval, ms", "Макс. интервал опроса, мс"),
            "sessionTtlSeconds" to Txt("Session TTL, s", "Время сессии, с"), "concurrency" to Txt("Concurrency", "Параллельность"),
            "eagerWindowMs" to Txt("Eager window, ms", "Окно eager, мс"), "holeTimeoutMs" to Txt("Hole timeout, ms", "Таймаут пропуска, мс"),
        )
        return listOf(
            ChoiceField(p("$b.service"), Txt("Service", "Сервис"), opts("Google Drive", "local", "template"), visible = x),
            TextField(p("$b.remoteFolder"), Txt("Remote folder", "Удалённая папка"), visible = x),
            TagsField(p("$b.secrets"), Txt("Secrets", "Секреты"), visible = x),
        ) + optional.map { (key, label) -> NumberField(p("$b.$key"), label, min = 0, removeWhenBlank = true, visible = x) } + listOf(
            JsonEditorField(p("$b.template"), Txt("Template", "Шаблон"), visible = { x(it) && it.s("$b.service") == "template" }),
        )
    }

    private fun sockopt(): Field {
        val b = "$SS.sockopt"
        return Section(
            Txt("Socket options (sockopt)", "Параметры сокета (sockopt)"),
            listOf(
                NumberField(p("$b.mark"), Txt("Mark", "Метка (mark)"), min = 0),
                NumberField(p("$b.tcpKeepAliveInterval"), Txt("TCP keep-alive interval", "Интервал TCP keep-alive"), min = 0),
                NumberField(p("$b.tcpKeepAliveIdle"), Txt("TCP keep-alive idle", "Простой до keep-alive"), min = 0),
                NumberField(p("$b.tcpMaxSeg"), Txt("TCP max segment", "Макс. сегмент TCP"), min = 0),
                NumberField(p("$b.tcpUserTimeout"), Txt("TCP user timeout", "TCP user timeout"), min = 0),
                NumberField(p("$b.tcpWindowClamp"), Txt("TCP window clamp", "Ограничение окна TCP"), min = 0),
                SwitchField(p("$b.acceptProxyProtocol"), Txt("Accept PROXY protocol", "Принимать PROXY protocol")),
                SwitchField(p("$b.tcpFastOpen"), Txt("TCP Fast Open")),
                SwitchField(p("$b.penetrate"), Txt("Penetrate", "Penetrate")),
                SwitchField(p("$b.V6Only"), Txt("IPv6 only", "Только IPv6")),
                ChoiceField(p("$b.tcpcongestion"), Txt("TCP congestion", "Управление перегрузкой TCP"), opts("bbr", "cubic", "reno")),
                ChoiceField(p("$b.tproxy"), Txt("TProxy"), listOf(Opt("off", "Off"), Opt("redirect", "Redirect"), Opt("tproxy", "TProxy"))),
                TagsField(
                    p("$b.trustedXForwardedFor"), Txt("Trusted client IP headers", "Доверенные заголовки IP клиента"),
                    suggestions = listOf("CF-Connecting-IP", "X-Real-IP", "True-Client-IP", "X-Client-IP"),
                    hint = Txt("Only for transports carried over HTTP (WS, HTTPUpgrade, XHTTP, gRPC).", "Только для транспортов поверх HTTP (WS, HTTPUpgrade, XHTTP, gRPC)."),
                ),
                ListField(
                    path = p("$b.customSockopt"),
                    title = Txt("Custom options", "Свои параметры"),
                    itemTitle = { item, i -> item["opt"].str()?.takeIf { it.isNotEmpty() } ?: "#${i + 1}" },
                    itemFields = { base ->
                        listOf(
                            ChoiceField(base + "system", Txt("System", "Система"), listOf(Opt("", "any")) + opts("linux", "windows", "darwin"), removeWhenEmpty = true),
                            ChoiceField(base + "type", Txt("Type", "Тип"), opts("int", "str")),
                            TextField(base + "level", Txt("Level", "Уровень"), placeholder = "6"),
                            TextField(base + "opt", Txt("Option", "Параметр")),
                            TextField(base + "value", Txt("Value", "Значение")),
                        )
                    },
                    newItem = { EditorDefaults.CUSTOM_SOCKOPT },
                ),
            ),
            toggle = Toggle(p(b), onOff = { d, on -> d.withSockopt(on) }),
        )
    }

    // --- FinalMask ---

    private val TCP_NETWORKS = setOf("raw", "tcp", "httpupgrade", "ws", "grpc", "xhttp", "xdrive")

    private fun showTcp(d: InboundDraft) = d.protocol != "wireguard" && d.network in TCP_NETWORKS
    private fun showUdp(d: InboundDraft) = d.protocol == "hysteria" || d.protocol == "wireguard" || d.network == "kcp"
    private fun showQuic(d: InboundDraft) = d.protocol == "hysteria" || d.network == "xhttp"

    private fun finalMask(): List<Field> {
        val fm = "$SS.finalmask"
        val any: Visible = { it.protocol != "tunnel" && InboundCapabilities.canEnableStream(it) && (showTcp(it) || showUdp(it) || showQuic(it)) }
        return listOf(
            Section(
                Txt("FinalMask"),
                listOf(
                    ListField(
                        path = p("$fm.tcp"),
                        title = Txt("TCP masks", "TCP-маски"),
                        itemTitle = { item, i -> "${i + 1}. ${item["type"].str().orEmpty()}" },
                        itemFields = { base -> tcpMaskFields(base) },
                        newItem = { mask("fragment", EditorDefaults.tcpMaskSettings("fragment")) },
                        visible = ::showTcp,
                    ),
                    ListField(
                        path = p("$fm.udp"),
                        title = Txt("UDP masks", "UDP-маски"),
                        itemTitle = { item, i -> "${i + 1}. ${item["type"].str().orEmpty()}" },
                        itemFields = { base -> udpMaskFields(base) },
                        newItem = { mask("salamander", EditorDefaults.udpMaskSettings("salamander")) },
                        visible = ::showUdp,
                    ),
                    Section(
                        Txt("QUIC params", "Параметры QUIC"),
                        quicFields("$fm.quicParams"),
                        toggle = Toggle(p("$fm.quicParams"), { EditorDefaults.QUIC_PARAMS }),
                        visible = ::showQuic,
                    ),
                ),
                hint = Txt("Extra obfuscation layers on top of the transport.", "Дополнительные слои обфускации поверх транспорта."),
                visible = any,
            ),
        )
    }

    private fun mask(type: String, settings: JsonObject) = buildJsonObject { put("type", type); put("settings", settings) }

    private fun tcpMaskFields(base: JsonPath): List<Field> {
        val s = base + "settings"
        fun type(d: InboundDraft) = d[base + "type"].str().orEmpty()
        return listOf(
            ChoiceField(
                path = base + "type", label = Txt("Type", "Тип"), options = { opts(EditorDefaults.TCP_MASK_TYPES) },
                onSelect = { d, value -> d.set(base, mask(value, EditorDefaults.tcpMaskSettings(value))) },
            ),
            TextField(s + "packets", Txt("Packets", "Пакеты"), placeholder = "1-3 / tlshello", visible = { type(it) == "fragment" }),
            TagsField(s + "lengths", Txt("Lengths", "Длины"), placeholder = "100-200", visible = { type(it) == "fragment" }),
            TagsField(s + "delays", Txt("Delays, ms", "Задержки, мс"), placeholder = "10-20", visible = { type(it) == "fragment" }),
            TextField(s + "maxSplit", Txt("Max split", "Макс. частей"), visible = { type(it) == "fragment" }),
        ) + sudokuFields(s) { type(it) == "sudoku" } + listOf(
            TextField(s + "hostname", Txt("Hostname"), visible = { type(it) == "xmc" }),
            TextField(s + "password", Txt("Password", "Пароль"), visible = { type(it) == "xmc" }),
            ListField(
                path = s + "profiles",
                title = Txt("Profiles", "Профили"),
                itemTitle = { item, i -> item["username"].str()?.takeIf { it.isNotEmpty() } ?: "#${i + 1}" },
                itemFields = { pb ->
                    listOf(
                        TextField(pb + "username", Txt("Username", "Имя игрока")),
                        TextField(pb + "uuid", Txt("UUID"), monospace = true),
                        TextField(pb + "texturesValue", Txt("Textures value", "Textures value"), monospace = true, multiline = true),
                        TextField(pb + "texturesSignature", Txt("Textures signature", "Textures signature"), monospace = true, multiline = true),
                    )
                },
                newItem = { EditorDefaults.XMC_PROFILE },
                minItems = 1,
                visible = { type(it) == "xmc" },
            ),
            JsonEditorField(
                s, Txt("Settings (JSON)", "Настройки (JSON)"),
                hint = Txt("clients / servers item lists, as in the panel's header-custom editor.", "Списки clients / servers, как в редакторе header-custom панели."),
                visible = { type(it) == "header-custom" },
            ),
        )
    }

    private fun udpMaskFields(base: JsonPath): List<Field> {
        val s = base + "settings"
        fun type(d: InboundDraft) = d[base + "type"].str().orEmpty()
        return listOf(
            ChoiceField(
                path = base + "type", label = Txt("Type", "Тип"),
                // udphop is a client-side mask; the panel does not offer it on inbounds.
                options = { opts(EditorDefaults.UDP_MASK_TYPES.filter { it != "udphop" }) },
                onSelect = { d, value -> d.set(base, mask(value, EditorDefaults.udpMaskSettings(value))) },
            ),
            TextField(s + "password", Txt("Password", "Пароль"), monospace = true, visible = { type(it) == "salamander" }),
            Actions(
                listOf(LocalAction(Txt("New password", "Новый пароль")) { it.set(s + "password", json(EditorDefaults.randomLowerAndNum(16))) }),
                visible = { type(it) == "salamander" },
            ),
            TextField(s + "header", Txt("Header", "Заголовок"), visible = { type(it) == "mkcp-legacy" }),
            TextField(s + "value", Txt("Value", "Значение"), visible = { type(it) == "mkcp-legacy" }),
            TagsField(s + "domains", Txt("Domains", "Домены"), visible = { type(it) == "xdns" }),
            SwitchField(s + "dgram", Txt("Datagram socket", "Датаграммный сокет"), visible = { type(it) == "xicmp" }),
            TagsField(s + "ips", Txt("IPs"), visible = { type(it) == "xicmp" }),
            TextField(s + "url", Txt("URL"), visible = { type(it) == "realm" }),
            TagsField(s + "stunServers", Txt("STUN servers", "STUN-серверы"), visible = { type(it) == "realm" }),
            NumberField(s + "reset", Txt("Reset", "Сброс"), min = 0, visible = { type(it) == "noise" }),
            ListField(
                path = s + "noise",
                title = Txt("Noise items", "Шумовые пакеты"),
                itemTitle = { item, i -> "#${i + 1} ${item["type"].str().orEmpty()}" },
                itemFields = { nb ->
                    listOf(
                        TextField(nb + "rand", Txt("Random length", "Случайная длина"), placeholder = "1-8192"),
                        TextField(nb + "randRange", Txt("Byte range", "Диапазон байт"), placeholder = "0-255"),
                        ChoiceField(nb + "type", Txt("Type", "Тип"), opts("array", "str", "base64", "hex")),
                        JsonEditorField(nb + "packet", Txt("Packet", "Пакет"), emptyValue = kotlinx.serialization.json.JsonArray(emptyList())),
                        TextField(nb + "delay", Txt("Delay, ms", "Задержка, мс"), placeholder = "10-20"),
                    )
                },
                newItem = { EditorDefaults.NOISE_ITEM },
                visible = { type(it) == "noise" },
            ),
        ) + sudokuFields(s) { type(it) == "sudoku" } + listOf(
            JsonEditorField(
                s, Txt("Settings (JSON)", "Настройки (JSON)"),
                hint = Txt("client / server item lists, as in the panel's header-custom editor.", "Списки client / server, как в редакторе header-custom панели."),
                visible = { type(it) == "header-custom" },
            ),
        )
    }

    private fun sudokuFields(s: JsonPath, visible: Visible): List<Field> = listOf(
        TextField(s + "password", Txt("Password", "Пароль"), visible = visible),
        TextField(s + "ascii", Txt("ASCII"), visible = visible),
        TextField(s + "customTable", Txt("Custom table", "Своя таблица"), visible = visible),
        TagsField(s + "customTables", Txt("Custom tables", "Свои таблицы"), visible = visible),
        NumberField(s + "paddingMin", Txt("Padding min", "Мин. паддинг"), min = 0, visible = visible),
        NumberField(s + "paddingMax", Txt("Padding max", "Макс. паддинг"), min = 0, visible = visible),
    )

    private fun quicFields(b: String): List<Field> {
        val brutal: Visible = { it.s("$b.congestion").let { c -> c == "brutal" || c == "force-brutal" } }
        val optionalNumbers = listOf(
            "initStreamReceiveWindow" to Txt("Initial stream window", "Начальное окно потока"),
            "maxStreamReceiveWindow" to Txt("Max stream window", "Макс. окно потока"),
            "initConnectionReceiveWindow" to Txt("Initial connection window", "Начальное окно соединения"),
            "maxConnectionReceiveWindow" to Txt("Max connection window", "Макс. окно соединения"),
        )
        return listOf(
            ChoiceField(p("$b.congestion"), Txt("Congestion", "Управление перегрузкой"), opts("reno", "bbr", "brutal", "force-brutal")),
            ChoiceField(
                p("$b.bbrProfile"), Txt("BBR profile", "Профиль BBR"), listOf(Opt("", "—")) + opts("conservative", "standard", "aggressive"),
                removeWhenEmpty = true, visible = { it.s("$b.congestion") == "bbr" },
            ),
            TextField(p("$b.brutalUp"), Txt("Brutal upload", "Brutal отдача"), placeholder = "100 mbps", removeWhenBlank = true, visible = brutal),
            TextField(p("$b.brutalDown"), Txt("Brutal download", "Brutal загрузка"), placeholder = "100 mbps", removeWhenBlank = true, visible = brutal),
            SwitchField(p("$b.brutalDisableLossCompensation"), Txt("No loss compensation", "Без компенсации потерь"), removeWhenOff = true, visible = brutal),
            SwitchField(p("$b.debug"), Txt("Debug"), removeWhenOff = true),
        ) + optionalNumbers.map { (key, label) -> NumberField(p("$b.$key"), label, min = 0, removeWhenBlank = true) } + listOf(
            NumberField(p("$b.maxIdleTimeout"), Txt("Max idle timeout, s", "Макс. простой, с"), min = 4, max = 120, removeWhenBlank = true),
            NumberField(p("$b.keepAlivePeriod"), Txt("Keep-alive period, s", "Период keep-alive, с"), min = 2, max = 60, removeWhenBlank = true),
            NumberField(p("$b.maxIncomingStreams"), Txt("Max incoming streams", "Макс. входящих потоков"), min = 8, removeWhenBlank = true),
            SwitchField(p("$b.disablePathMTUDiscovery"), Txt("Disable path MTU discovery", "Без Path MTU discovery"), removeWhenOff = true),
            SwitchField(p("$b.disableChromeParrot"), Txt("Disable Chrome parrot", "Без имитации Chrome"), removeWhenOff = true),
            SwitchField(p("$b.disableGSO"), Txt("Disable GSO", "Без GSO"), removeWhenOff = true),
            SwitchField(p("$b.disableStatelessReset"), Txt("Disable stateless reset", "Без stateless reset"), removeWhenOff = true),
            Section(
                Txt("UDP hop"),
                listOf(
                    TextField(p("$b.udpHop.ports"), Txt("Ports", "Порты"), placeholder = "20000-50000"),
                    TextField(p("$b.udpHop.interval"), Txt("Interval, s", "Интервал, с"), placeholder = "5-10"),
                ),
                toggle = Toggle(p("$b.udpHop"), { EditorDefaults.UDP_HOP }),
            ),
        )
    }
}
