package dev.autorasqr.xuimanager.data.subscription

import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.jsonStrings
import dev.autorasqr.xuimanager.data.subscription.SubscriptionDraft.RuleKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDraftTest {

    private val rules = """[{"type":"field","outboundTag":"block","domain":["geosite:category-ads-all"]},""" +
        """{"type":"field","outboundTag":"proxy","domain":["geosite:google"]}]"""

    private val settings = JsonObject(
        mapOf(
            "subTitle" to JsonPrimitive("My VPN"),
            "subUpdates" to JsonPrimitive(12),
            "subEnable" to JsonPrimitive(true),
            "subJsonMux" to JsonPrimitive(""),
            "subJsonRules" to JsonPrimitive(rules),
            "subJsonObservatory" to JsonPrimitive("""{"destination":"https://x/generate_204","sampling":2}"""),
            "subJsonFinalMask" to JsonPrimitive(""),
            "webPort" to JsonPrimitive(2053),
        ),
    )
    private val keys = settings.keys

    @Test
    fun `loading and saving without edits changes nothing`() {
        val draft = SubscriptionDraft.fromPanel(settings)
        assertTrue(SubscriptionDraft.changes(draft, draft, keys).isEmpty())
        // The JSON strings are parsed; an empty one is simply off.
        assertNull(draft[JsonPath("subJsonMux")])
        assertEquals("2", draft[JsonPath("subJsonObservatory.sampling")].toString())
    }

    @Test
    fun `plain settings are written as they are`() {
        val original = SubscriptionDraft.fromPanel(settings)
        val edited = original.set(JsonPath("subTitle"), json("Fast VPN")).set(JsonPath("subUpdates"), json(6))
        assertEquals(mapOf("subTitle" to JsonPrimitive("Fast VPN"), "subUpdates" to JsonPrimitive(6)), SubscriptionDraft.changes(original, edited, keys))
    }

    @Test
    fun `mux goes back as a JSON string and off as an empty one`() {
        val original = SubscriptionDraft.fromPanel(settings)
        val on = original.set(JsonPath("subJsonMux"), SubscriptionDraft.DEFAULT_MUX)
        val written = SubscriptionDraft.changes(original, on, keys).getValue("subJsonMux").jsonPrimitive.content
        assertEquals(SubscriptionDraft.DEFAULT_MUX, Json.parseToJsonElement(written))

        val withObservatory = SubscriptionDraft.fromPanel(settings)
        val off = withObservatory.set(JsonPath("subJsonObservatory"), null)
        assertEquals(JsonPrimitive(""), SubscriptionDraft.changes(withObservatory, off, keys)["subJsonObservatory"])
    }

    @Test
    fun `keys the panel did not send are never written`() {
        val original = SubscriptionDraft.fromPanel(settings)
        val edited = original.set(JsonPath("tag"), json("inbound-443")).set(JsonPath("subTitle"), json("x"))
        assertEquals(setOf("subTitle"), SubscriptionDraft.changes(original, edited, keys).keys)
    }

    @Test
    fun `direct rules are added after the others and block rules keep their place`() {
        val original = SubscriptionDraft.fromPanel(settings)
        assertTrue(SubscriptionDraft.rulesOn(original, SubscriptionDraft.BLOCK))
        assertFalse(SubscriptionDraft.rulesOn(original, SubscriptionDraft.DIRECT))
        // Block has a domain rule; an empty IP slot is there for editing but is not saved.
        assertNotNull(SubscriptionDraft.rulePath(original, SubscriptionDraft.BLOCK, RuleKind.IP))

        val direct = SubscriptionDraft.setRules(original, SubscriptionDraft.DIRECT, true)
        val saved = Json.parseToJsonElement(SubscriptionDraft.changes(original, direct, keys).getValue("subJsonRules").jsonPrimitive.content) as JsonArray
        assertEquals(listOf("block", "proxy", "direct", "direct"), saved.map { it.jsonObject["outboundTag"]!!.jsonPrimitive.content })
        assertEquals("""["geoip:private","geoip:ir"]""", saved[3].jsonObject["ip"].toString())
    }

    @Test
    fun `editing a rule list writes only real rules`() {
        val original = SubscriptionDraft.fromPanel(settings)
        val ipPath = SubscriptionDraft.rulePath(original, SubscriptionDraft.BLOCK, RuleKind.IP)!!
        val edited = original.set(ipPath, jsonStrings(listOf("geoip:ru")))
        val saved = Json.parseToJsonElement(SubscriptionDraft.changes(original, edited, keys).getValue("subJsonRules").jsonPrimitive.content) as JsonArray
        assertEquals(3, saved.size)
        assertEquals("""["geoip:ru"]""", saved.last().jsonObject["ip"].toString())
    }

    @Test
    fun `turning the last direct or block rules off clears the list`() {
        val original = SubscriptionDraft.fromPanel(settings)
        val off = SubscriptionDraft.setRules(original, SubscriptionDraft.BLOCK, false)
        assertEquals(JsonPrimitive(""), SubscriptionDraft.changes(original, off, keys)["subJsonRules"])
    }
}
