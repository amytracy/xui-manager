package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.data.inbound.editor.EditorDefaults
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.ui.common.SelectField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Protocol tab: the protocols folder of 3x-ui v3.10.2's inbound form. */
object SpecProtocol {
    private fun on(protocol: String): Visible = { it.protocol == protocol }
    private val VLESS_AUTH = listOf(
        Opt("x25519", "X25519"), Opt("x25519_xorpub", "X25519 (xorpub)"), Opt("x25519_random", "X25519 (random)"),
        Opt("mlkem768", "ML-KEM-768"), Opt("mlkem768_xorpub", "ML-KEM-768 (xorpub)"), Opt("mlkem768_random", "ML-KEM-768 (random)"),
    )

    fun fields(): List<Field> = vless() + shadowsocks() + accounts() + mixed() + tunnel() + tun() + wireguard() + amneziawg() +
        tuic() + masque() + mtproto() + fallbacksNote()

    private fun vless(): List<Field> = listOf(
        TextField(p("settings.decryption"), Txt("Decryption", "Decryption"), monospace = true, visible = on("vless")),
        TextField(p("settings.encryption"), Txt("Encryption", "Encryption"), monospace = true, visible = on("vless")),
        CustomField(on("vless")) { scope -> VlessAuthGenerator(scope) },
        CustomField({ it.protocol == "vless" && it.network == "tcp" && (it.security == "tls" || it.security == "reality") }) { scope -> TestseedField(scope) },
    )

    @Composable
    private fun VlessAuthGenerator(scope: EditorScope) {
        var auth by remember { mutableStateOf(VLESS_AUTH.first()) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectField(
                label = Txt("VLESS encryption: authentication", "VLESS encryption: аутентификация").text(),
                selected = auth,
                options = VLESS_AUTH,
                text = { it.label },
                onSelect = { auth = it },
            )
            FieldList(
                listOf(
                    Actions(
                        listOf(
                            RemoteAction(Txt("Generate", "Сгенерировать"), RemoteActionId.VLESS_ENC, auth.value),
                            LocalAction(Txt("Turn off", "Выключить"), destructive = true) {
                                it.set(p("settings.decryption"), json("none")).set(p("settings.encryption"), json("none"))
                            },
                        ),
                    ),
                ),
                scope,
            )
        }
    }

    /** settings.testseed: four positive numbers or nothing. */
    @Composable
    private fun TestseedField(scope: EditorScope) {
        val current = (scope.draft[p("settings.testseed")] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        var text by remember { mutableStateOf(current) }
        val numbers = text.split(',', ' ').filter { it.isNotBlank() }.map { it.toIntOrNull() }
        val valid = text.isBlank() || (numbers.size == 4 && numbers.all { it != null && it > 0 })
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                val parsed = input.split(',', ' ').filter { it.isNotBlank() }.map { it.toIntOrNull() }
                scope.update { d ->
                    when {
                        input.isBlank() -> d.set(p("settings.testseed"), null)
                        parsed.size == 4 && parsed.all { it != null && it > 0 } -> d.set(p("settings.testseed"), JsonArray(parsed.map { JsonPrimitive(it) }))
                        else -> d
                    }
                }
            },
            label = { Text("Vision testseed") },
            placeholder = { Text("900, 500, 900, 256") },
            isError = !valid,
            supportingText = { Text(Txt("Four positive numbers, or empty for the default.", "Четыре положительных числа или пусто для значения по умолчанию.").text()) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun shadowsocks(): List<Field> {
        val ss = on("shadowsocks")
        return listOf(
            ChoiceField(
                path = p("settings.method"), label = Txt("Encryption method", "Метод шифрования"), options = { opts(EditorDefaults.SS_METHODS) },
                onSelect = { d, method ->
                    var next = d.set(p("settings.method"), json(method))
                    if (method.startsWith("2022")) next = next.set(p("settings.password"), json(EditorDefaults.shadowsocksPassword(method)))
                    next
                },
                visible = ss,
            ),
            TextField(p("settings.password"), Txt("Password", "Пароль"), monospace = true, visible = { it.protocol == "shadowsocks" && InboundCapabilities.isSS2022(it) }),
            Actions(
                listOf(LocalAction(Txt("New password", "Новый пароль")) { it.set(p("settings.password"), json(EditorDefaults.shadowsocksPassword(it.s("settings.method")))) }),
                visible = { it.protocol == "shadowsocks" && InboundCapabilities.isSS2022(it) },
            ),
            ChoiceField(p("settings.network"), Txt("Network", "Сеть"), listOf(Opt("tcp,udp", "TCP, UDP"), Opt("tcp", "TCP"), Opt("udp", "UDP")), visible = ss),
            SwitchField(p("settings.ivCheck"), Txt("ivCheck"), visible = ss),
        )
    }

    private fun accounts(): List<Field> = listOf(
        SwitchField(p("settings.allowTransparent"), Txt("Allow transparent proxying", "Прозрачное проксирование"), visible = on("http")),
        ListField(
            path = p("settings.accounts"),
            title = Txt("Accounts", "Учётные записи"),
            itemTitle = { item, i -> item["user"].str()?.takeIf { it.isNotEmpty() } ?: "#${i + 1}" },
            itemFields = { base ->
                listOf(
                    TextField(base + "user", Txt("User", "Пользователь")),
                    TextField(base + "pass", Txt("Password", "Пароль"), monospace = true),
                )
            },
            newItem = { buildJsonObject { put("user", EditorDefaults.randomLowerAndNum(8)); put("pass", EditorDefaults.randomLowerAndNum(12)) } },
            visible = { it.protocol == "http" || (it.protocol == "mixed" && it.s("settings.auth") != "noauth") },
        ),
    )

    private fun mixed(): List<Field> = listOf(
        ChoiceField(p("settings.auth"), Txt("Authentication", "Аутентификация"), opts("noauth", "password"), visible = on("mixed")),
        SwitchField(p("settings.udp"), Txt("UDP"), visible = on("mixed")),
        TextField(p("settings.ip"), Txt("UDP IP"), placeholder = "127.0.0.1", visible = { it.protocol == "mixed" && it[p("settings.udp")].str() == "true" }),
    )

    private fun tunnel(): List<Field> {
        val t = on("tunnel")
        return listOf(
            TextField(p("settings.rewriteAddress"), Txt("Rewrite address", "Подменять адрес"), removeWhenBlank = true, visible = t),
            NumberField(p("settings.rewritePort"), Txt("Rewrite port", "Подменять порт"), min = 0, max = 65535, removeWhenBlank = true, visible = t),
            ChoiceField(p("settings.allowedNetwork"), Txt("Allowed network", "Разрешённая сеть"), listOf(Opt("tcp,udp", "TCP, UDP"), Opt("tcp", "TCP"), Opt("udp", "UDP")), visible = t),
            MapField(p("settings.portMap"), Txt("Port map", "Карта портов"), keyHint = "5555", valueHint = "1.1.1.1:7777", visible = t),
            SwitchField(p("settings.followRedirect"), Txt("Follow redirect", "Учитывать редирект (TProxy)"), visible = t),
        )
    }

    private fun tun(): List<Field> {
        val t = on("tun")
        return listOf(
            TextField(p("settings.name"), Txt("Interface name", "Имя интерфейса"), placeholder = "xray0", visible = t),
            TextField(p("settings.desc"), Txt("Description (Windows)", "Описание (Windows)"), placeholder = "Wintun", removeWhenBlank = true, visible = t),
            NumberField(p("settings.mtu"), Txt("MTU"), min = 0, visible = t),
            TagsField(p("settings.gateway"), Txt("Gateway", "Шлюз"), placeholder = "10.0.0.1/16", visible = t),
            TagsField(p("settings.dns"), Txt("DNS"), placeholder = "1.1.1.1", visible = t),
            NumberField(p("settings.userLevel"), Txt("User level", "Уровень пользователя"), min = 0, visible = t),
            TagsField(p("settings.autoSystemRoutingTable"), Txt("Auto system routes", "Автомаршруты системы"), placeholder = "0.0.0.0/0", visible = t),
            TextField(p("settings.autoOutboundsInterface"), Txt("Outbounds interface", "Интерфейс исходящих"), placeholder = "auto", visible = t),
            SwitchField(p("settings.autoSystemDnsToGateway"), Txt("System DNS to gateway", "Системный DNS на шлюз"), removeWhenOff = true, visible = t),
            MultiChoiceField(p("settings.autoSystemWfpBlockLeak"), Txt("WFP leak blocking (Windows)", "Блокировка утечек WFP (Windows)"), opts("dns", "misconfigtun"), visible = t),
        )
    }

    private fun wireguard(): List<Field> {
        val w = on("wireguard")
        return listOf(
            TextField(p("settings.secretKey"), Txt("Private key", "Приватный ключ"), monospace = true, visible = w),
            Actions(listOf(LocalAction(Txt("New key pair", "Новая пара ключей")) { it.set(p("settings.secretKey"), json(EditorDefaults.wireguardKeyPair().first)) }), visible = w),
            InfoField(Txt("Public key", "Публичный ключ"), { EditorDefaults.wireguardPublicKey(it.s("settings.secretKey")) ?: "—" }, monospace = true, visible = w),
            TextField(p("settings.subnetIp"), Txt("Subnet IP", "IP подсети"), placeholder = "10.0.0.0", visible = w),
            NumberField(p("settings.subnetCidr"), Txt("Subnet CIDR", "Маска подсети"), min = 1, max = 32, removeWhenBlank = true, visible = w),
            NumberField(p("settings.mtu"), Txt("MTU"), min = 1, removeWhenBlank = true, visible = w),
            TextField(p("settings.dns"), Txt("DNS"), placeholder = "1.1.1.1, 1.0.0.1", removeWhenBlank = true, visible = w),
            SwitchField(p("settings.noKernelTun"), Txt("No kernel TUN", "Без TUN ядра"), visible = w),
        )
    }

    private fun amneziawg(): List<Field> {
        val a = on("amneziawg")
        fun srv(key: String) = p("settings.server.$key")
        val strings = listOf(
            "h1" to "1 or 100-800", "h2" to "2 or 100-800", "h3" to "3 or 100-800", "h4" to "4 or 100-800",
            "i1" to "<r 64>", "i2" to "<r 64>", "i3" to "<r 64>", "i4" to "<r 64>", "i5" to "<r 64>",
            "headerProtectionKey" to "", "contentPaddingAddition" to "8-64", "rekeyAfterTime" to "100-160", "rekeyTimeout" to "3-10",
            "rejectAfterTime" to "190-250", "keepaliveTimeout" to "8-20", "maxHandshakeAttempts" to "15-50",
        )
        return listOf(
            TextField(srv("privateKey"), Txt("Private key", "Приватный ключ"), monospace = true, visible = a),
            Actions(
                listOf(
                    LocalAction(Txt("New key pair", "Новая пара ключей")) {
                        val (privateKey, _) = EditorDefaults.wireguardKeyPair()
                        it.set(srv("privateKey"), json(privateKey))
                    },
                ),
                visible = a,
            ),
            InfoField(Txt("Public key", "Публичный ключ"), { it.s("settings.server.publicKey").ifEmpty { "—" } }, monospace = true, visible = a),
            TextField(srv("subnetIp"), Txt("Subnet IP", "IP подсети"), placeholder = "10.8.1.0", visible = a),
            NumberField(srv("subnetCidr"), Txt("Subnet CIDR", "Маска подсети"), min = 1, max = 32, visible = a),
            NumberField(srv("mtu"), Txt("MTU"), min = 1, removeWhenBlank = true, visible = a),
            TextField(srv("primaryDns"), Txt("Primary DNS", "Основной DNS"), placeholder = "8.8.8.8", visible = a),
            TextField(srv("secondaryDns"), Txt("Secondary DNS", "Запасной DNS"), placeholder = "8.8.4.4", visible = a),
            TextField(srv("externalInterface"), Txt("External interface", "Внешний интерфейс"), placeholder = "eth0", visible = a),
            SwitchField(srv("ipv6Enabled"), Txt("IPv6"), visible = a),
            TextField(srv("ipv6Subnet"), Txt("IPv6 subnet", "IPv6-подсеть"), placeholder = "fd86:ea04:1115::/64", visible = { it.protocol == "amneziawg" && it[srv("ipv6Enabled")].str() == "true" }),
            TextField(srv("ipv6ExternalInterface"), Txt("IPv6 external interface", "Внешний интерфейс IPv6"), placeholder = "eth0", visible = { it.protocol == "amneziawg" && it[srv("ipv6Enabled")].str() == "true" }),
            Section(
                Txt("Obfuscation", "Обфускация"),
                listOf(
                    Actions(listOf(LocalAction(Txt("Randomize", "Сгенерировать заново")) { d ->
                        EditorDefaults.awgObfuscation().entries.fold(d) { acc, (k, v) -> acc.set(srv(k), v) }
                    })),
                    NumberField(srv("jc"), Txt("Jc"), min = 0),
                    NumberField(srv("jmin"), Txt("Jmin"), min = 0),
                    NumberField(srv("jmax"), Txt("Jmax"), min = 0),
                    NumberField(srv("s1"), Txt("S1"), min = 0, max = 1552),
                    NumberField(srv("s2"), Txt("S2"), min = 0, max = 1608),
                    NumberField(srv("s3"), Txt("S3"), min = 0, max = 1636),
                    NumberField(srv("s4"), Txt("S4"), min = 0, max = 32),
                ) + strings.map { (key, placeholder) -> TextField(srv(key), Txt(key), placeholder = placeholder.ifEmpty { null }, monospace = true) } + listOf(
                    SwitchField(srv("randomTrailers"), Txt("Random trailers", "Случайные хвосты")),
                    SwitchField(srv("disableCookies"), Txt("Disable cookies", "Отключить cookies")),
                ),
                visible = a,
            ),
        )
    }

    private fun tuic(): List<Field> {
        val t = on("tuic")
        fun srv(key: String) = p("settings.server.$key")
        return listOf(
            TextField(srv("certificate"), Txt("Certificate file", "Файл сертификата"), placeholder = "/root/cert.pem", visible = t),
            TextField(srv("private_key"), Txt("Private key file", "Файл ключа"), placeholder = "/root/privkey.pem", visible = t),
            Actions(listOf(RemoteAction(Txt("Use the panel certificate", "Взять сертификат панели"), RemoteActionId.CERT_FROM_PANEL, "tuic")), visible = t),
            TextField(srv("sni"), Txt("SNI"), placeholder = "example.com", visible = t),
            ChoiceField(srv("congestion_control"), Txt("Congestion control", "Управление перегрузкой"), listOf(Opt("bbr", "BBR"), Opt("cubic", "CUBIC"), Opt("new_reno", "New Reno")), visible = t),
            MultiChoiceField(srv("alpn"), Txt("ALPN"), opts("h3", "spdy/3.1"), visible = t),
            ChoiceField(srv("udp_relay_mode"), Txt("UDP relay mode", "Режим UDP"), listOf(Opt("native", "Native"), Opt("quic", "QUIC")), visible = t),
            SwitchField(srv("zero_rtt_handshake"), Txt("0-RTT handshake", "0-RTT рукопожатие"), visible = t),
            ChoiceField(srv("log_level"), Txt("Log level", "Уровень журнала"), opts("info", "warn", "error", "debug"), visible = t),
            NumberField(srv("max_idle_time"), Txt("Max idle time, s", "Простой, с"), min = 1, visible = t),
            NumberField(srv("authentication_timeout"), Txt("Authentication timeout, s", "Таймаут аутентификации, с"), min = 1, visible = t),
            NumberField(srv("max_udp_relay_packet_size"), Txt("Max UDP packet size", "Макс. размер UDP-пакета"), min = 1, max = 65245, visible = t),
        )
    }

    private fun masque(): List<Field> = listOf(
        TagsField(p("settings.address"), Txt("Address pool", "Пул адресов"), placeholder = "10.14.0.1/24", visible = on("masque")),
        NumberField(p("settings.mtu"), Txt("MTU"), min = 1280, max = 65535, placeholder = "1280", removeWhenBlank = true, visible = on("masque")),
    )

    private fun mtproto(): List<Field> {
        val m = on("mtproto")
        return listOf(
            TextField(p("settings.fakeTlsDomain"), Txt("Fake TLS domain", "Домен Fake TLS"), placeholder = "www.cloudflare.com", visible = m),
            Section(
                Txt("Domain fronting", "Domain fronting"),
                listOf(
                    TextField(p("settings.domainFronting.ip"), Txt("IP"), placeholder = "127.0.0.1", removeWhenBlank = true),
                    NumberField(p("settings.domainFronting.port"), Txt("Port", "Порт"), min = 0, max = 65535, placeholder = "443", removeWhenBlank = true),
                    SwitchField(p("settings.domainFronting.proxyProtocol"), Txt("PROXY protocol", "PROXY protocol"), removeWhenOff = true),
                ),
                visible = m,
            ),
            SwitchField(p("settings.proxyProtocolListener"), Txt("Accept PROXY protocol", "Принимать PROXY protocol"), removeWhenOff = true, visible = m),
            ChoiceField(p("settings.preferIp"), Txt("Prefer IP", "Предпочитать IP"), listOf(Opt("", "—")) + opts("prefer-ipv6", "prefer-ipv4", "only-ipv6", "only-ipv4"), removeWhenEmpty = true, visible = m),
            SwitchField(p("settings.debug"), Txt("Debug"), removeWhenOff = true, visible = m),
            NumberField(p("settings.throttleMaxConnections"), Txt("Max connections", "Макс. соединений"), min = 0, removeWhenBlank = true, visible = m),
            NumberField(p("settings.pendingHandshakesMaxPerIp"), Txt("Pending handshakes per IP", "Рукопожатий с одного IP"), min = 0, removeWhenBlank = true, visible = m),
            SwitchField(p("settings.routeThroughXray"), Txt("Route through Xray", "Маршрутизировать через Xray"), removeWhenOff = true, visible = m),
            TextField(p("settings.outboundTag"), Txt("Outbound tag", "Тег исходящего"), removeWhenBlank = true, visible = { it.protocol == "mtproto" && it[p("settings.routeThroughXray")].str() == "true" }),
            TextField(p("settings.publicIpv4"), Txt("Public IPv4", "Публичный IPv4"), placeholder = "1.2.3.4", removeWhenBlank = true, visible = m),
            TextField(p("settings.publicIpv6"), Txt("Public IPv6", "Публичный IPv6"), placeholder = "2001:db8::1", removeWhenBlank = true, visible = m),
        )
    }

    private fun fallbacksNote(): List<Field> = listOf(
        CustomField(InboundCapabilities::isFallbackHost) { scope -> FallbacksCard(scope) },
        CustomField({ (it.protocol == "vless" || it.protocol == "trojan") && it.network == "tcp" && !InboundCapabilities.isFallbackHost(it) }) { _ ->
            Text(
                Txt("Fallbacks need RAW with TLS or Reality.", "Fallbacks работают только с RAW и TLS или Reality.").text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
