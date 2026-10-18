package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Declarative description of the inbound form. Every field reads and writes a [JsonPath] of the inbound, so the form
 * mirrors 3x-ui's own layout field by field while anything it does not show survives an edit unchanged.
 */
sealed interface Field {
    val visible: (InboundDraft) -> Boolean
}

typealias Visible = (InboundDraft) -> Boolean

val Always: Visible = { true }

/** A label in both app languages; technical names stay as the panel spells them. */
data class Txt(val en: String, val ru: String = en) {
    @Composable
    fun text(): String = if (LocalConfiguration.current.locales[0].language == "ru") ru else en
}

data class Opt(val value: String, val label: String = value)

class TextField(
    val path: JsonPath,
    val label: Txt,
    val placeholder: String? = null,
    val hint: Txt? = null,
    val multiline: Boolean = false,
    val monospace: Boolean = false,
    /** Blank input removes the key instead of storing "". */
    val removeWhenBlank: Boolean = false,
    override val visible: Visible = Always,
) : Field

class NumberField(
    val path: JsonPath,
    val label: Txt,
    val min: Long? = null,
    val max: Long? = null,
    val placeholder: String? = null,
    val hint: Txt? = null,
    /** Blank input removes the key (optional numbers); otherwise blank becomes [min] or 0. */
    val removeWhenBlank: Boolean = false,
    /** Stored as a string (xmux and ranges that may also hold "a-b"). */
    val asString: Boolean = false,
    override val visible: Visible = Always,
) : Field

class SwitchField(
    val path: JsonPath,
    val label: Txt,
    val hint: Txt? = null,
    /** Value shown when the key is missing. */
    val default: Boolean = false,
    /** False writes remove the key (optional flags). */
    val removeWhenOff: Boolean = false,
    override val visible: Visible = Always,
) : Field

class ChoiceField(
    val path: JsonPath,
    val label: Txt,
    val options: (InboundDraft) -> List<Opt>,
    val hint: Txt? = null,
    val default: String = "",
    /** Replaces the plain write, e.g. protocol and network switches that reseed other parts. */
    val onSelect: ((InboundDraft, String) -> InboundDraft)? = null,
    val enabled: (InboundDraft) -> Boolean = { true },
    /** "" removes the key. */
    val removeWhenEmpty: Boolean = false,
    override val visible: Visible = Always,
) : Field {
    constructor(path: JsonPath, label: Txt, options: List<Opt>, hint: Txt? = null, default: String = "", removeWhenEmpty: Boolean = false, visible: Visible = Always) :
        this(path, label, { options }, hint, default, null, { true }, removeWhenEmpty, visible)
}

/** Several values from a fixed list (ALPN, sniffing destinations). */
class MultiChoiceField(
    val path: JsonPath,
    val label: Txt,
    val options: List<Opt>,
    val hint: Txt? = null,
    override val visible: Visible = Always,
) : Field

/** A free list of strings edited as chips (serverNames, shortIds, CIDRs…), with optional suggestions. */
class TagsField(
    val path: JsonPath,
    val label: Txt,
    val placeholder: String? = null,
    val suggestions: List<String> = emptyList(),
    val hint: Txt? = null,
    /** An empty list removes the key. */
    val removeWhenEmpty: Boolean = false,
    /** Stored as one comma-separated string instead of an array (cipherSuites). */
    val joined: Boolean = false,
    override val visible: Visible = Always,
) : Field

/** A list of strings edited one per line (PEM certificate and key lines). */
class LinesField(
    val path: JsonPath,
    val label: Txt,
    val placeholder: String? = null,
    override val visible: Visible = Always,
) : Field

/** name: value pairs; [multiValue] stores each value as a list (RAW HTTP header obfuscation). */
class MapField(
    val path: JsonPath,
    val label: Txt,
    val multiValue: Boolean = false,
    val keyHint: String = "Header",
    val valueHint: String = "value",
    override val visible: Visible = Always,
) : Field

/** A group of fields; with [toggle] it is shown only while the object at the toggle path exists. */
class Section(
    val title: Txt,
    val fields: List<Field>,
    val hint: Txt? = null,
    val toggle: Toggle? = null,
    override val visible: Visible = Always,
) : Field

/** Switch that creates [path] from [create] or removes it; [isOn] and [onOff] override both for flag-driven sections. */
class Toggle(
    val path: JsonPath,
    val create: (InboundDraft) -> JsonElement = { JsonObject(emptyMap()) },
    val onOff: ((InboundDraft, Boolean) -> InboundDraft)? = null,
    val isOn: ((InboundDraft) -> Boolean)? = null,
)

/** The JSON of one subtree as editable text; applied while it parses (3x-ui's advanced editors). */
class JsonEditorField(
    val path: JsonPath,
    val label: Txt,
    val hint: Txt? = null,
    /** What an empty editor writes; null removes the key. */
    val emptyValue: JsonElement? = null,
    override val visible: Visible = Always,
) : Field

/** An array of objects; [itemFields] gets the item's own path (e.g. `...certificates.2`). */
class ListField(
    val path: JsonPath,
    val title: Txt,
    val itemTitle: (JsonObject, Int) -> String,
    val itemFields: (JsonPath) -> List<Field>,
    val newItem: (InboundDraft) -> JsonElement,
    val minItems: Int = 0,
    val hint: Txt? = null,
    override val visible: Visible = Always,
) : Field

/** Buttons: local transforms of the draft, or calls the view model makes to the panel. */
class Actions(val actions: List<Action>, override val visible: Visible = Always) : Field

sealed interface Action {
    val label: Txt
}

class LocalAction(override val label: Txt, val destructive: Boolean = false, val run: (InboundDraft) -> InboundDraft) : Action

class RemoteAction(override val label: Txt, val id: RemoteActionId, val arg: Any? = null, val destructive: Boolean = false) : Action

enum class RemoteActionId { REALITY_KEYS, MLDSA65, VLESS_ENC, SCAN_TARGET, SCAN_CANDIDATES, ECH_CERT, CERT_FROM_PANEL, PIN_FROM_CERT, PIN_FROM_REMOTE }

/** Read-only text computed from the draft (derived public keys, notes). */
class InfoField(val label: Txt?, val text: (InboundDraft) -> String?, val monospace: Boolean = false, val warning: Boolean = false, override val visible: Visible = Always) : Field

/** Anything the generic widgets cannot express. */
class CustomField(override val visible: Visible = Always, val content: @Composable (EditorScope) -> Unit) : Field

/** What custom content and action buttons can reach. */
interface EditorScope {
    val draft: InboundDraft
    val busy: Boolean
    fun update(transform: (InboundDraft) -> InboundDraft)
    fun remote(id: RemoteActionId, arg: Any? = null)

    /** Fallback rows of a VLESS/Trojan TCP master; saved through their own API after the inbound. */
    val fallbacks: List<FallbackRow>

    /** Other inbounds a fallback can point at. */
    val fallbackChildren: List<Opt>
    fun updateFallbacks(rows: List<FallbackRow>)

    /** The last Reality target probe, shown under the target field. */
    val scanResult: ScanSummary?

    /** Nodes of the panel a new inbound can be placed on; empty without nodes. */
    val nodes: List<NodeChoice>
}

/** One fallback: a child inbound (or a hand-written dest) matched by SNI, ALPN and path. */
data class FallbackRow(
    val childId: Int? = null,
    val name: String = "",
    val alpn: String = "",
    val path: String = "",
    val dest: String = "",
    val xver: Int = 0,
)

data class ScanSummary(
    val target: String,
    val feasible: Boolean,
    val tls13: Boolean,
    val h2: Boolean,
    val curve: String,
    val latencyMs: Long,
    val certSubject: String,
    val certIssuer: String,
    val serverNames: List<String>,
    val reason: String,
    val privateTarget: Boolean,
)
