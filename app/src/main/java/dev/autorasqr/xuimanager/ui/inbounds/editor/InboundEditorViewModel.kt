package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.data.inbound.editor.EditorIssue
import dev.autorasqr.xuimanager.data.inbound.editor.EditorTab
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.InboundValidator
import dev.autorasqr.xuimanager.data.inbound.editor.InboundWire
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.bool
import dev.autorasqr.xuimanager.data.inbound.editor.int
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.jsonStrings
import dev.autorasqr.xuimanager.data.inbound.editor.long
import dev.autorasqr.xuimanager.data.inbound.editor.obj
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.data.inbound.editor.strings
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.domain.repository.InboundEditorRepository
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.InboundEditorRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import kotlin.random.Random

data class EditorUiState(
    val loading: Boolean = true,
    val unsupported: Boolean = false,
    val draft: InboundDraft? = null,
    /** What was loaded, to tell whether leaving discards changes. */
    val original: InboundDraft? = null,
    val tab: EditorTab = EditorTab.BASIC,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val error: AppError? = null,
    val issue: EditorIssue? = null,
    val notice: Txt? = null,
    val fallbacks: List<FallbackRow> = emptyList(),
    val originalFallbacks: List<FallbackRow> = emptyList(),
    val fallbackChildren: List<Opt> = emptyList(),
    val scan: ScanSummary? = null,
    val saved: Boolean = false,
    val nodes: List<NodeChoice> = emptyList(),
) {
    val dirty: Boolean get() = draft != original || fallbacks != originalFallbacks
}

@HiltViewModel
class InboundEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: InboundEditorRepository,
    private val nodeRepository: NodeRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<InboundEditorRoute>()
    val serverId: Long = route.serverId
    private val inboundId: Int? = route.inboundId.takeIf { it > 0 }
    val isNew: Boolean get() = inboundId == null

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (!repository.supported(serverId)) {
                    _state.update { it.copy(loading = false, unsupported = true) }
                    return@launch
                }
                val others = repository.otherInbounds(serverId)
                val (draft, fallbacks) = if (inboundId == null) {
                    val used = others.mapNotNull { it["port"].int() }.toSet()
                    val port = generateSequence { Random.nextInt(10000, 60000) }.first { it !in used }
                    InboundDraft.new(port) to emptyList()
                } else {
                    val (raw, rows) = repository.load(serverId, inboundId)
                    InboundDraft.fromPanel(raw) to rows.map(::rowFromPanel)
                }
                // Node names are optional: the form works without them, just without "Place on".
                val nodes = runCatching { nodeRepository.choices(serverId) }.getOrDefault(emptyList())
                val children = others.filter { it["id"].int() != inboundId }.map { ib ->
                    Opt(ib["id"].int().toString(), "#${ib["id"].int()} ${ib["remark"].str().orEmpty().ifEmpty { ib["protocol"].str().orEmpty() }} :${ib["port"].int()}")
                }
                _state.update {
                    it.copy(
                        loading = false, draft = draft, original = draft, fallbacks = fallbacks, originalFallbacks = fallbacks,
                        fallbackChildren = children, nodes = nodes,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asAppError()) }
            }
        }
    }

    fun selectTab(tab: EditorTab) = _state.update { it.copy(tab = tab) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun update(transform: (InboundDraft) -> InboundDraft) = _state.update { s ->
        val draft = s.draft ?: return@update s
        s.copy(draft = transform(draft), issue = null)
    }

    fun updateFallbacks(rows: List<FallbackRow>) = _state.update { it.copy(fallbacks = rows) }

    fun remote(id: RemoteActionId, arg: Any?) {
        val draft = _state.value.draft ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                runRemote(id, arg, draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.asAppError()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun runRemote(id: RemoteActionId, arg: Any?, draft: InboundDraft) {
        val r = JsonPath("streamSettings.realitySettings")
        val t = JsonPath("streamSettings.tlsSettings")
        when (id) {
            RemoteActionId.REALITY_KEYS -> {
                val (privateKey, publicKey) = repository.newX25519(serverId)
                update { it.set(r + "privateKey", json(privateKey)).set(r + JsonPath("settings.publicKey"), json(publicKey)) }
            }
            RemoteActionId.MLDSA65 -> {
                val (seed, verify) = repository.newMldsa65(serverId)
                update { it.set(r + "mldsa65Seed", json(seed)).set(r + JsonPath("settings.mldsa65Verify"), json(verify)) }
            }
            RemoteActionId.VLESS_ENC -> {
                val pair = repository.newVlessEnc(serverId, arg as? String ?: "x25519")
                if (pair == null) {
                    notice(Txt("The panel offered no key of that kind.", "Панель не выдала ключ такого типа."))
                } else {
                    update { it.set(JsonPath("settings.decryption"), json(pair.first)).set(JsonPath("settings.encryption"), json(pair.second)) }
                }
            }
            RemoteActionId.SCAN_TARGET -> {
                val target = draft[r + "target"].str().orEmpty().trim()
                if (target.isEmpty()) {
                    notice(Txt("Enter a target first, e.g. www.apple.com:443.", "Сначала укажите цель, например www.apple.com:443."))
                    return
                }
                val sni = draft[r + "serverNames"].strings().firstOrNull { it.isNotBlank() }.orEmpty()
                val result = repository.scanTarget(serverId, target, sni, draft[r + "xver"].int() ?: 0)
                val summary = summaryOf(result)
                _state.update { it.copy(scan = summary) }
                // applyRealityScanResult: names off a trusted chain are usable as SNI.
                val trusted = result["certValid"].bool() == true || result["certChainValid"].bool() == true
                update { d ->
                    var next = d
                    result["target"].str()?.takeIf { it.isNotEmpty() }?.let { next = next.set(r + "target", json(it)) }
                    if (trusted && summary.serverNames.isNotEmpty()) next = next.set(r + "serverNames", jsonStrings(summary.serverNames))
                    next
                }
            }
            RemoteActionId.SCAN_CANDIDATES -> {
                val found = repository.findTarget(serverId)
                if (found == null) {
                    notice(Txt("None of the preset sites works from this server.", "Ни один из готовых сайтов не подходит с этого сервера."))
                } else {
                    val (host, latency) = found
                    update { it.set(r + "target", json("$host:443")).set(r + "serverNames", jsonStrings(listOf(host))) }
                    _state.update {
                        it.copy(scan = ScanSummary("$host:443", true, true, true, "", latency, "", "", listOf(host), "", false))
                    }
                }
            }
            RemoteActionId.ECH_CERT -> {
                val sni = draft[t + "serverName"].str().orEmpty()
                if (sni.isBlank()) {
                    notice(Txt("Fill in SNI first.", "Сначала заполните SNI."))
                    return
                }
                val (keys, configs) = repository.newEchCert(serverId, sni)
                update { it.set(t + "echServerKeys", json(keys)).set(t + JsonPath("settings.echConfigList"), json(configs)) }
            }
            RemoteActionId.CERT_FROM_PANEL -> {
                // A node's inbound needs paths that exist on the node.
                val files = repository.panelCertFiles(serverId, draft.nodeId)
                if (files == null) {
                    notice(Txt("The panel has no certificate files set.", "У панели не заданы файлы сертификата."))
                    return
                }
                val (cert, key) = files
                update { d ->
                    if (arg == "tuic") {
                        d.set(JsonPath("settings.server.certificate"), json(cert)).set(JsonPath("settings.server.private_key"), json(key))
                    } else {
                        val base = t + "certificates" + (arg as? Int ?: 0)
                        d.set(base + "useFile", json(true)).set(base + "certificateFile", json(cert)).set(base + "keyFile", json(key))
                    }
                }
            }
            RemoteActionId.PIN_FROM_CERT -> {
                val first = draft[t + "certificates" + 0].obj()
                val file = first?.get("certificateFile").str().orEmpty().takeIf { first?.get("useFile").bool() != false }.orEmpty()
                val content = first?.get("certificate").strings().orEmpty().joinToString("\n").trim()
                if (file.isEmpty() && content.isEmpty()) {
                    notice(Txt("Set a certificate first.", "Сначала задайте сертификат."))
                    return
                }
                mergePins(repository.certHash(serverId, file, content))
            }
            RemoteActionId.PIN_FROM_REMOTE -> {
                val server = draft[t + "serverName"].str().orEmpty()
                if (server.isBlank()) {
                    notice(Txt("Fill in SNI first.", "Сначала заполните SNI."))
                    return
                }
                mergePins(repository.remoteCertHash(serverId, server))
            }
        }
    }

    private fun mergePins(hashes: List<String>) {
        val path = JsonPath("streamSettings.tlsSettings.settings.pinnedPeerCertSha256")
        update { d -> d.set(path, jsonStrings((d[path].strings() + hashes).distinct())) }
    }

    private fun notice(text: Txt) = _state.update { it.copy(notice = text) }

    fun save() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.saving) return
        val issue = InboundValidator.validate(draft)
        if (issue != null) {
            _state.update { it.copy(issue = issue, tab = issue.tab) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val fallbacks = if (InboundCapabilities.isFallbackHost(draft)) {
                    s.fallbacks.filter { it.childId != null || it.dest.isNotBlank() }.mapIndexed { index, row -> rowToPanel(row, index) }
                } else {
                    null
                }
                repository.save(serverId, inboundId, InboundWire.payload(draft), fallbacks)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.asAppError()) }
            }
        }
    }

    private fun summaryOf(r: JsonObject) = ScanSummary(
        target = r["target"].str().orEmpty(),
        feasible = r["feasible"].bool() == true,
        tls13 = r["tls13"].bool() == true,
        h2 = r["h2"].bool() == true,
        curve = r["curveID"].str().orEmpty(),
        latencyMs = r["latencyMs"].long() ?: 0,
        certSubject = r["certSubject"].str().orEmpty(),
        certIssuer = r["certIssuer"].str().orEmpty(),
        serverNames = r["serverNames"].strings(),
        reason = r["reason"].str().orEmpty(),
        privateTarget = r["privateTarget"].bool() == true,
    )

    private fun rowFromPanel(o: JsonObject) = FallbackRow(
        childId = o["childId"].int()?.takeIf { it > 0 },
        name = o["name"].str().orEmpty(),
        alpn = o["alpn"].str().orEmpty(),
        path = o["path"].str().orEmpty(),
        dest = o["dest"].str().orEmpty(),
        xver = o["xver"].int() ?: 0,
    )

    private fun rowToPanel(r: FallbackRow, index: Int) = JsonObject(
        mapOf(
            "childId" to (r.childId?.let(::JsonPrimitive) ?: JsonNull),
            "name" to JsonPrimitive(r.name),
            "alpn" to JsonPrimitive(r.alpn),
            "path" to JsonPrimitive(r.path),
            "dest" to JsonPrimitive(r.dest),
            "xver" to JsonPrimitive(r.xver),
            "sortOrder" to JsonPrimitive(index),
        ),
    )
}
