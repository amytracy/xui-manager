package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.autorasqr.xuimanager.data.inbound.editor.EditorDefaults
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.jsonStrings
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

/** Security tab: security/tls.tsx, security/reality.tsx and useSecurityActions of 3x-ui v3.10.2. */
object SpecSecurity {
    private const val TLS = "streamSettings.tlsSettings"
    private const val R = "streamSettings.realitySettings"

    fun fields(): List<Field> = listOf(CustomField { scope -> SecuritySelector(scope) }) + tls() + reality()

    @Composable
    private fun SecuritySelector(scope: EditorScope) {
        val d = scope.draft
        val tlsOk = InboundCapabilities.canEnableTls(d)
        val options = buildList {
            if (!InboundCapabilities.tlsOnly(d)) add("none" to Txt("None", "Нет").text())
            add("tls" to "TLS")
            if (InboundCapabilities.canEnableReality(d)) add("reality" to "Reality")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = d.security == value,
                        enabled = tlsOk,
                        onClick = {
                            if (d.security != value) {
                                scope.update { it.withSecurity(value) }
                                // As the panel does: a new Reality block gets its key pair right away.
                                if (value == "reality") scope.remote(RemoteActionId.REALITY_KEYS)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
            if (!tlsOk) {
                Text(
                    Txt("This protocol and transport carry no TLS.", "Для этого протокола и транспорта TLS недоступен.").text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    private fun tls(): List<Field> {
        val t: Visible = { it.security == "tls" }
        return listOf(
            TextField(p("$TLS.serverName"), Txt("SNI"), placeholder = "example.com", visible = t),
            TagsField(
                p("$TLS.cipherSuites"), Txt("Cipher suites", "Наборы шифров"), suggestions = EditorDefaults.CIPHER_SUITES,
                hint = Txt("Empty means automatic.", "Пусто — автоматически."), joined = true, visible = t,
            ),
            ChoiceField(p("$TLS.minVersion"), Txt("Min TLS version", "Мин. версия TLS"), opts(EditorDefaults.TLS_VERSIONS), visible = t),
            ChoiceField(p("$TLS.maxVersion"), Txt("Max TLS version", "Макс. версия TLS"), opts(EditorDefaults.TLS_VERSIONS), visible = t),
            ChoiceField(p("$TLS.settings.fingerprint"), Txt("uTLS fingerprint", "Отпечаток uTLS"), listOf(Opt("", "None")) + opts(EditorDefaults.FINGERPRINTS), visible = t),
            MultiChoiceField(p("$TLS.alpn"), Txt("ALPN"), opts(EditorDefaults.ALPN), visible = t),
            TagsField(
                p("$TLS.curvePreferences"), Txt("Curve preferences", "Предпочтительные кривые"),
                suggestions = listOf("X25519MLKEM768", "X25519", "P-256", "P-384", "P-521"), removeWhenEmpty = true, visible = t,
            ),
            SwitchField(p("$TLS.rejectUnknownSni"), Txt("Reject unknown SNI", "Отклонять чужой SNI"), visible = t),
            SwitchField(p("$TLS.disableSystemRoot"), Txt("Disable system root", "Без системных корней"), visible = t),
            SwitchField(p("$TLS.enableSessionResumption"), Txt("Session resumption", "Возобновление сессий"), visible = t),
            ListField(
                path = p("$TLS.certificates"),
                title = Txt("Certificates", "Сертификаты"),
                itemTitle = { item, i -> "#${i + 1} · ${item["usage"].str() ?: "encipherment"}" },
                itemFields = { base -> certificateFields(base) },
                newItem = { EditorDefaults.tlsCertificate() },
                minItems = 1,
                visible = t,
            ),
            TextField(p("$TLS.masterKeyLog"), Txt("Master key log", "Журнал ключей (master key log)"), placeholder = "/path/to/sslkeylog.txt", removeWhenBlank = true, visible = t),
            Section(
                Txt("ECH (Encrypted Client Hello)"),
                listOf(
                    TextField(p("$TLS.echServerKeys"), Txt("ECH server keys", "Серверные ключи ECH"), monospace = true, multiline = true),
                    TextField(p("$TLS.settings.echConfigList"), Txt("ECH config list (clients)", "ECH config list (клиентам)"), monospace = true, multiline = true),
                    Actions(
                        listOf(
                            RemoteAction(Txt("Generate for SNI", "Сгенерировать для SNI"), RemoteActionId.ECH_CERT),
                            LocalAction(Txt("Clear", "Очистить"), destructive = true) {
                                it.set(p("$TLS.echServerKeys"), json("")).set(p("$TLS.settings.echConfigList"), json(""))
                            },
                        ),
                    ),
                    Section(
                        Txt("ECH socket options", "Параметры сокета ECH"),
                        listOf(
                            TextField(p("$TLS.echSockopt.dialerProxy"), Txt("Dialer proxy", "Dialer proxy")),
                            ChoiceField(p("$TLS.echSockopt.domainStrategy"), Txt("Domain strategy", "Стратегия доменов"), opts(EditorDefaults.DOMAIN_STRATEGIES)),
                            SwitchField(p("$TLS.echSockopt.tcpFastOpen"), Txt("TCP Fast Open")),
                            SwitchField(p("$TLS.echSockopt.tcpMptcp"), Txt("Multipath TCP")),
                        ),
                        toggle = Toggle(p("$TLS.echSockopt"), { EditorDefaults.ECH_SOCKOPT }),
                    ),
                ),
                visible = t,
            ),
            TagsField(p("$TLS.settings.pinnedPeerCertSha256"), Txt("Pinned peer certificate SHA-256", "Пиннинг сертификата (SHA-256)"), visible = t),
            Actions(
                listOf(
                    RemoteAction(Txt("Pin from my certificate", "Пиннинг своего сертификата"), RemoteActionId.PIN_FROM_CERT),
                    RemoteAction(Txt("Pin from SNI server", "Пиннинг сервера SNI"), RemoteActionId.PIN_FROM_REMOTE),
                ),
                visible = t,
            ),
            TextField(p("$TLS.settings.verifyPeerCertByName"), Txt("Verify peer certificate by name", "Проверять сертификат по имени"), placeholder = "example.com", visible = t),
        )
    }

    private fun certificateFields(base: JsonPath): List<Field> {
        val file: Visible = { it[base + "useFile"].str() != "false" }
        val inline: Visible = { it[base + "useFile"].str() == "false" }
        val index = base.parts.last() as Int
        return listOf(
            SwitchField(base + "useFile", Txt("Certificate from files", "Сертификат из файлов"), default = true),
            TextField(base + "certificateFile", Txt("Certificate file", "Файл сертификата"), placeholder = "/root/cert/example.com/fullchain.pem", visible = file),
            TextField(base + "keyFile", Txt("Key file", "Файл ключа"), placeholder = "/root/cert/example.com/privkey.pem", visible = file),
            Actions(
                listOf(
                    RemoteAction(Txt("Panel / domain certificate", "Сертификат панели / домена"), RemoteActionId.CERT_FROM_PANEL, index),
                    LocalAction(Txt("Clear", "Очистить"), destructive = true) { it.set(base + "certificateFile", json("")).set(base + "keyFile", json("")) },
                ),
                visible = file,
            ),
            LinesField(base + "certificate", Txt("Certificate (PEM)", "Сертификат (PEM)"), placeholder = "-----BEGIN CERTIFICATE-----", visible = inline),
            LinesField(base + "key", Txt("Private key (PEM)", "Приватный ключ (PEM)"), placeholder = "-----BEGIN PRIVATE KEY-----", visible = inline),
            NumberField(base + "ocspStapling", Txt("OCSP stapling, s", "OCSP stapling, с"), min = 0),
            SwitchField(base + "oneTimeLoading", Txt("Load once", "Загрузить один раз")),
            ChoiceField(base + "usage", Txt("Usage", "Назначение"), opts("encipherment", "verify", "issue")),
            SwitchField(base + "buildChain", Txt("Build chain", "Собирать цепочку"), visible = { it[base + "usage"].str() == "issue" }),
        )
    }

    private fun reality(): List<Field> {
        val r: Visible = { it.security == "reality" }
        return listOf(
            SwitchField(p("$R.show"), Txt("Show debug output", "Отладочный вывод (show)"), visible = r),
            NumberField(p("$R.xver"), Txt("xver (PROXY protocol to target)", "xver (PROXY protocol к цели)"), min = 0, visible = r),
            ChoiceField(p("$R.settings.fingerprint"), Txt("uTLS fingerprint", "Отпечаток uTLS"), opts(EditorDefaults.FINGERPRINTS), visible = r),
            TextField(p("$R.target"), Txt("Target", "Цель (target)"), placeholder = "example.com:443", visible = r),
            Actions(
                listOf(
                    RemoteAction(Txt("Check target", "Проверить цель"), RemoteActionId.SCAN_TARGET),
                    RemoteAction(Txt("Find a target", "Подобрать цель"), RemoteActionId.SCAN_CANDIDATES),
                ),
                visible = r,
            ),
            CustomField(r) { scope -> ScanResultCard(scope) },
            TagsField(p("$R.serverNames"), Txt("SNI (serverNames)"), placeholder = "example.com", visible = r),
            NumberField(p("$R.maxTimediff"), Txt("Max time difference, ms", "Макс. расхождение времени, мс"), min = 0, visible = r),
            TextField(p("$R.minClientVer"), Txt("Min client version", "Мин. версия клиента"), placeholder = "x.y.z", visible = r),
            TextField(p("$R.maxClientVer"), Txt("Max client version", "Макс. версия клиента"), placeholder = "x.y.z", visible = r),
            TagsField(p("$R.shortIds"), Txt("Short IDs"), visible = r),
            Actions(listOf(LocalAction(Txt("New short IDs", "Новые short ID")) { it.set(p("$R.shortIds"), jsonStrings(EditorDefaults.randomShortIds())) }), visible = r),
            TextField(p("$R.settings.spiderX"), Txt("SpiderX"), placeholder = "/", visible = r),
            Actions(listOf(LocalAction(Txt("New SpiderX", "Новый SpiderX")) { it.set(p("$R.settings.spiderX"), json(EditorDefaults.randomSpiderX())) }), visible = r),
            TextField(p("$R.settings.publicKey"), Txt("Public key", "Публичный ключ"), monospace = true, visible = r),
            TextField(p("$R.privateKey"), Txt("Private key", "Приватный ключ"), monospace = true, visible = r),
            Actions(
                listOf(
                    RemoteAction(Txt("New key pair", "Новая пара ключей"), RemoteActionId.REALITY_KEYS),
                    LocalAction(Txt("Clear", "Очистить"), destructive = true) { it.set(p("$R.privateKey"), json("")).set(p("$R.settings.publicKey"), json("")) },
                ),
                visible = r,
            ),
            TextField(p("$R.mldsa65Seed"), Txt("ML-DSA-65 seed"), monospace = true, visible = r),
            TextField(p("$R.settings.mldsa65Verify"), Txt("ML-DSA-65 verify"), monospace = true, multiline = true, visible = r),
            Actions(
                listOf(
                    RemoteAction(Txt("Generate ML-DSA-65", "Сгенерировать ML-DSA-65"), RemoteActionId.MLDSA65),
                    LocalAction(Txt("Clear", "Очистить"), destructive = true) { it.set(p("$R.mldsa65Seed"), json("")).set(p("$R.settings.mldsa65Verify"), json("")) },
                ),
                visible = r,
            ),
            TextField(p("$R.masterKeyLog"), Txt("Master key log", "Журнал ключей (master key log)"), placeholder = "/path/to/sslkeylog.txt", removeWhenBlank = true, visible = r),
            limitSection("limitFallbackUpload", Txt("Limit fallback upload", "Ограничить отдачу fallback"), r),
            limitSection("limitFallbackDownload", Txt("Limit fallback download", "Ограничить загрузку fallback"), r),
        )
    }

    private fun limitSection(key: String, title: Txt, visible: Visible) = Section(
        title,
        listOf(
            NumberField(p("$R.$key.afterBytes"), Txt("After bytes", "После байт"), min = 0),
            NumberField(p("$R.$key.bytesPerSec"), Txt("Bytes per second", "Байт в секунду"), min = 0),
            NumberField(p("$R.$key.burstBytesPerSec"), Txt("Burst bytes per second", "Всплеск, байт в секунду"), min = 0),
        ),
        toggle = Toggle(p("$R.$key"), { EditorDefaults.REALITY_LIMIT }),
        visible = visible,
    )

    @Composable
    private fun ScanResultCard(scope: EditorScope) {
        val r = scope.scanResult ?: return
        val ok = r.feasible && !r.privateTarget
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (ok) Txt("Suitable Reality target", "Подходит как цель Reality").text() else Txt("Not suitable", "Не подходит").text(),
                    color = if (ok) LocalStatusColors.current.success else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${r.target} · TLS 1.3 ${if (r.tls13) "✓" else "✗"} · H2 ${if (r.h2) "✓" else "✗"} · ${r.curve.ifEmpty { "?" }} · ${r.latencyMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (r.certSubject.isNotEmpty()) Text("${r.certSubject} — ${r.certIssuer}", style = MaterialTheme.typography.bodySmall)
                if (r.reason.isNotEmpty()) Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (r.privateTarget) {
                    Text(Txt("The target resolves to a private address.", "Цель указывает на частный адрес.").text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (r.serverNames.isNotEmpty()) {
                    TextButton(onClick = { scope.update { it.set(p("$R.serverNames"), jsonStrings(r.serverNames)) } }) {
                        Text(Txt("Use its names as SNI: ", "Взять его имена в SNI: ").text() + r.serverNames.take(3).joinToString())
                    }
                }
            }
        }
    }
}
