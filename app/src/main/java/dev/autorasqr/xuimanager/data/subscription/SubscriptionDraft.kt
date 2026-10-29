package dev.autorasqr.xuimanager.data.subscription

import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The panel's subscription settings (3x-ui v3.10.2 SubscriptionGeneralTab, SubscriptionFormatsTab and the
 * observatory of SubscriptionBalancersTab) as an editable draft.
 *
 * AllSetting is flat, but four of its values are JSON kept as strings ("" when off). They are parsed into the draft
 * under the same key, so the form edits them field by field, and written back as strings by [changes].
 */
object SubscriptionDraft {
    const val MUX = "subJsonMux"
    const val RULES = "subJsonRules"
    const val OBSERVATORY = "subJsonObservatory"
    const val FINAL_MASK = "subJsonFinalMask"
    private val JSON_STRINGS = listOf(MUX, RULES, OBSERVATORY, FINAL_MASK)

    /** DEFAULT_MUX of SubscriptionFormatsTab. */
    val DEFAULT_MUX: JsonObject = buildJsonObject {
        put("enabled", true)
        put("concurrency", 8)
        put("xudpConcurrency", 16)
        put("xudpProxyUDP443", "reject")
    }

    /** The probe settings the observatory switch starts from (the panel's placeholders). */
    val DEFAULT_PING: JsonObject = buildJsonObject {
        put("destination", "https://www.google.com/generate_204")
        put("connectivity", "")
        put("interval", "1m")
        put("timeout", "5s")
        put("sampling", 1)
        put("httpMethod", "HEAD")
    }

    const val DIRECT = "direct"
    const val BLOCK = "block"

    enum class RuleKind(val key: String) { DOMAIN("domain"), IP("ip") }

    /** What enabling Direct / Block adds (DEFAULT_DIRECT_RULES, DEFAULT_BLOCK_RULES). */
    private fun defaults(tag: String): List<JsonObject> = when (tag) {
        DIRECT -> listOf(rule(DIRECT, RuleKind.DOMAIN, listOf("geosite:category-ir")), rule(DIRECT, RuleKind.IP, listOf("geoip:private", "geoip:ir")))
        else -> listOf(rule(BLOCK, RuleKind.DOMAIN, listOf("geosite:category-ads-all")))
    }

    private fun rule(tag: String, kind: RuleKind, values: List<String>) = buildJsonObject {
        put("type", "field")
        put("outboundTag", tag)
        put(kind.key, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    /** AllSetting as the form edits it. */
    fun fromPanel(settings: JsonObject, json: Json = Json): InboundDraft {
        val out = settings.toMutableMap()
        for (key in JSON_STRINGS) {
            val text = (settings[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            val parsed = text?.takeIf { it.isNotEmpty() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            if (parsed is JsonObject || parsed is JsonArray) out[key] = parsed else out.remove(key)
        }
        return withRuleSlots(InboundDraft(JsonObject(out), isNew = false))
    }

    /**
     * The values to write back: only keys the panel sent (anything the shared draft logic may add stays local) whose
     * value differs from [original]; parsed JSON goes back as a string, a removed one as "".
     */
    fun changes(original: InboundDraft, draft: InboundDraft, panelKeys: Set<String>): Map<String, JsonElement> {
        val before = withoutEmptyRules(original).inbound
        val after = withoutEmptyRules(draft).inbound
        return panelKeys.filter { before[it] != after[it] }.associateWith { key ->
            val value = after[key]
            when {
                key in JSON_STRINGS -> JsonPrimitive(value?.toString().orEmpty())
                value == null -> JsonPrimitive("")
                else -> value
            }
        }
    }

    // --- Direct / Block rules (a rule holds either domains or IPs, matched by outboundTag) ---

    fun rulesOn(d: InboundDraft, tag: String): Boolean = rules(d).any { it["outboundTag"].str() == tag }

    /** Enabling adds the panel's defaults (block before, direct after the others); disabling drops every rule of [tag]. */
    fun setRules(d: InboundDraft, tag: String, on: Boolean): InboundDraft {
        val current = rules(d)
        val next = if (on) {
            if (current.any { it["outboundTag"].str() == tag }) current
            else if (tag == BLOCK) defaults(tag) + current else current + defaults(tag)
        } else {
            val rest = current.filter { it["outboundTag"].str() != tag }
            // Like the panel: once neither tag is left, the whole list goes, foreign rules included.
            if (rest.none { it["outboundTag"].str() == DIRECT || it["outboundTag"].str() == BLOCK }) emptyList() else rest
        }
        return withRuleSlots(d.set(JsonPath(RULES), if (next.isEmpty()) null else JsonArray(next)))
    }

    /** Path of the domain or IP list of [tag]'s rule; [withRuleSlots] keeps one for every enabled tag. */
    fun rulePath(d: InboundDraft, tag: String, kind: RuleKind): JsonPath? {
        val index = rules(d).indexOfFirst { it["outboundTag"].str() == tag && it.containsKey(kind.key) }
        return if (index < 0) null else JsonPath(listOf(RULES, index, kind.key))
    }

    private fun rules(d: InboundDraft): List<JsonObject> = (d[JsonPath(RULES)] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** An empty domain and IP rule for each enabled tag, so both lists can be edited; [withoutEmptyRules] drops them again. */
    private fun withRuleSlots(d: InboundDraft): InboundDraft {
        var list = rules(d)
        if (list.isEmpty()) return d
        for (tag in listOf(DIRECT, BLOCK)) {
            if (list.none { it["outboundTag"].str() == tag }) continue
            for (kind in RuleKind.entries) {
                if (list.none { it["outboundTag"].str() == tag && it.containsKey(kind.key) }) list = list + rule(tag, kind, emptyList())
            }
        }
        val raw = d[JsonPath(RULES)] as? JsonArray
        return if (raw != null && raw.size == list.size) d else d.set(JsonPath(RULES), JsonArray(list))
    }

    private fun withoutEmptyRules(d: InboundDraft): InboundDraft {
        val list = rules(d)
        if (list.isEmpty()) return d
        val kept = list.filterNot { r ->
            val tag = r["outboundTag"].str()
            (tag == DIRECT || tag == BLOCK) && RuleKind.entries.any { k -> (r[k.key] as? JsonArray)?.isEmpty() == true }
        }
        return d.set(JsonPath(RULES), if (kept.isEmpty()) null else JsonArray(kept))
    }
}
