package dev.autorasqr.xuimanager.ui.inbounds.editor

import dev.autorasqr.xuimanager.data.inbound.editor.EditorDefaults
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.InboundWire
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Walks every tab's fields for every protocol / transport / security the form allows. */
class EditorSpecTest {
    private val tabs = listOf(SpecBasic.fields(), SpecProtocol.fields(), SpecStream.fields(), SpecSecurity.fields(), SpecSniffing.fields(), SpecAdvanced.fields())

    private fun drafts(): List<InboundDraft> = EditorDefaults.PROTOCOLS.flatMap { protocol ->
        val base = InboundDraft.new(20000).withProtocol(protocol).withSockopt(true)
        val networks = if (InboundCapabilities.hasSelectableTransport(base)) EditorDefaults.NETWORKS else listOf(base.network)
        networks.flatMap { network ->
            val n = if (network == base.network) base else base.withNetwork(network)
            listOf("none", "tls", "reality").mapNotNull { security ->
                when {
                    security == "tls" && !InboundCapabilities.canEnableTls(n) -> null
                    security == "reality" && !InboundCapabilities.canEnableReality(n) -> null
                    security == n.security -> n
                    else -> n.withSecurity(security)
                }
            }
        }
    }

    private fun walk(fields: List<Field>, d: InboundDraft, seen: MutableList<String>) {
        for (f in fields) {
            if (!f.visible(d)) continue
            seen += f::class.simpleName.orEmpty()
            when (f) {
                is Section -> walk(f.fields, d, seen)
                is ListField -> {
                    val items = d[f.path] as? JsonArray ?: JsonArray(listOf(f.newItem(d)))
                    items.indices.forEach { i ->
                        f.itemTitle(items[i] as? JsonObject ?: JsonObject(emptyMap()), i)
                        walk(f.itemFields(f.path + i), d, seen)
                    }
                }
                is ChoiceField -> f.options(d).forEach { opt -> f.onSelect?.invoke(d, opt.value)?.let { InboundWire.payload(it) } }
                is InfoField -> f.text(d)
                else -> Unit
            }
        }
    }

    @Test
    fun `every combination renders its fields and produces a payload`() {
        val all = drafts()
        assertTrue("combinations: ${all.size}", all.size > 60)
        for (d in all) {
            val seen = mutableListOf<String>()
            tabs.forEach { walk(it, d, seen) }
            assertTrue("${d.protocol}/${d.network}/${d.security} shows fields", seen.size > 5)
            InboundWire.payload(d)
        }
    }

    @Test
    fun `masks of every type switch cleanly`() {
        var d = InboundDraft.new(1).withNetwork("kcp")
        EditorDefaults.UDP_MASK_TYPES.forEach { type ->
            d = d.set(JsonPath("streamSettings.finalmask.udp.0"), JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive(type), "settings" to EditorDefaults.udpMaskSettings(type))))
            val seen = mutableListOf<String>()
            walk(SpecStream.fields(), d, seen)
            InboundWire.payload(d)
        }
        var t = InboundDraft.new(1)
        EditorDefaults.TCP_MASK_TYPES.forEach { type ->
            t = t.set(JsonPath("streamSettings.finalmask.tcp.0"), JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive(type), "settings" to EditorDefaults.tcpMaskSettings(type))))
            walk(SpecStream.fields(), t, mutableListOf())
        }
    }
}
