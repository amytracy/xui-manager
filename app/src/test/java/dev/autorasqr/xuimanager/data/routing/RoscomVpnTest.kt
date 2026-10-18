package dev.autorasqr.xuimanager.data.routing

import dev.autorasqr.xuimanager.domain.model.ClientRouting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoscomVpnTest {
    /** 3x-ui v3.10.2's default template (internal/web/service/config.json), routing part. */
    private val template = Json.parseToJsonElement(
        """{"log":{},"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"blocked"}],
           "routing":{"domainStrategy":"AsIs","rules":[
             {"inboundTag":["api"],"outboundTag":"api","type":"field"},
             {"ip":["geoip:private"],"outboundTag":"blocked","type":"field"},
             {"outboundTag":"blocked","protocol":["bittorrent"],"type":"field"}]}}""",
    ).jsonObject

    private fun rules(config: JsonObject) = config["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `profiles map to the remote RoscomVPN sources`() {
        val on = RoscomVpn.subscriptionSettings(ClientRouting.ROSCOM_DEFAULT)
        assertEquals(JsonPrimitive(true), on["subEnableRouting"])
        assertEquals(RoscomVpn.HAPP_DEFAULT, on["subRoutingRules"]!!.jsonPrimitive.content)
        assertEquals(RoscomVpn.INCY_DEFAULT, on["subIncyRoutingRules"]!!.jsonPrimitive.content)
        assertTrue(RoscomVpn.HAPP_DEFAULT.startsWith("https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/"))
        val off = RoscomVpn.subscriptionSettings(ClientRouting.OFF)
        assertEquals(JsonPrimitive(false), off["subEnableRouting"])
        assertEquals("", off["subRoutingRules"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the active profile is read back from the settings`() {
        fun settings(enabled: Boolean, rules: String) = buildJsonObject { put("subEnableRouting", enabled); put("subRoutingRules", rules) }
        assertEquals(ClientRouting.OFF, RoscomVpn.profileOf(settings(false, "")))
        assertEquals(ClientRouting.OFF, RoscomVpn.profileOf(JsonObject(emptyMap())))
        assertEquals(ClientRouting.ROSCOM_DEFAULT, RoscomVpn.profileOf(settings(true, RoscomVpn.HAPP_DEFAULT)))
        assertEquals(ClientRouting.ROSCOM_WHITELIST, RoscomVpn.profileOf(settings(true, RoscomVpn.HAPP_WHITELIST)))
        assertEquals(ClientRouting.CUSTOM, RoscomVpn.profileOf(settings(true, "happ://routing/onadd/e30=")))
    }

    @Test
    fun `server block goes right after the API rule, once, and comes off cleanly`() {
        val on = RoscomVpn.withServerBlock(template, enabled = true)
        val tags = rules(on).map { it["ruleTag"]?.jsonPrimitive?.content }
        assertEquals(listOf(null, RoscomVpn.RULE_TAG_DOMAINS, RoscomVpn.RULE_TAG_IPS, null, null), tags)
        val domains = rules(on)[1]
        assertEquals("blocked", domains["outboundTag"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("ext:geosite_roscomvpn.dat:category-ru", "ext:geosite_roscomvpn.dat:whitelist"),
            domains["domain"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("ext:geoip_roscomvpn.dat:direct", rules(on)[2]["ip"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(RoscomVpn.hasServerBlock(on))
        assertEquals("applying twice changes nothing", on, RoscomVpn.withServerBlock(on, enabled = true))
        assertEquals("domainStrategy and other keys survive", "AsIs", on["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)

        val off = RoscomVpn.withServerBlock(on, enabled = false)
        assertFalse(RoscomVpn.hasServerBlock(off))
        assertEquals(rules(template), rules(off))
        assertEquals(template["outbounds"], off["outbounds"])
    }

    @Test
    fun `a template without a blackhole gets one of its own, removed again with the rules`() {
        val bare = Json.parseToJsonElement("""{"outbounds":[{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[]}}""").jsonObject
        val on = RoscomVpn.withServerBlock(bare, enabled = true)
        val outbounds = on["outbounds"]!!.jsonArray.map { it.jsonObject }
        val own = outbounds.single { it["protocol"]!!.jsonPrimitive.content == "blackhole" }
        assertEquals(own["tag"], rules(on).first()["outboundTag"])
        val off = RoscomVpn.withServerBlock(on, enabled = false)
        assertEquals(bare["outbounds"], off["outbounds"])
    }
}
