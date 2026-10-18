package dev.autorasqr.xuimanager.data.routing

import dev.autorasqr.xuimanager.data.panel.PanelJson.bool
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import dev.autorasqr.xuimanager.domain.model.ClientRouting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RoscomVPN routing (github.com/hydraponique/roscomvpn-routing, -geoip, -geosite).
 *
 * Client side: 3x-ui v3 fetches the profile from these URLs itself (cached, refreshed by its cron job) and sends it
 * to Happ in the Routing header and to INCY as an autorouting link; the clients download the RoscomVPN geo files the
 * profile names and send RU/BY traffic direct.
 *
 * Server side (optional): Xray refuses Russian destinations, using the same geo files stored next to Xray by
 * geodata.sh, so a client without routing never reaches Russian services from the server's address.
 */
object RoscomVpn {
    private const val BASE = "https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/main"
    const val HAPP_DEFAULT = "$BASE/HAPP/DEFAULT.DEEPLINK"
    const val HAPP_WHITELIST = "$BASE/HAPP/WHITELIST.DEEPLINK"
    const val INCY_DEFAULT = "$BASE/INCY/DEFAULT.JSON"
    const val INCY_WHITELIST = "$BASE/INCY/WHITELIST.JSON"

    const val GEOIP_FILE = "geoip_roscomvpn.dat"
    const val GEOSITE_FILE = "geosite_roscomvpn.dat"
    const val RULE_TAG_DOMAINS = "xui-manager-roscomvpn-ru-domains"
    const val RULE_TAG_IPS = "xui-manager-roscomvpn-ru-ips"
    private const val OWN_BLACKHOLE = "xui-manager-blocked"

    /** Settings keys to write for [profile]; [ClientRouting.CUSTOM] is never written. */
    fun subscriptionSettings(profile: ClientRouting): Map<String, JsonElement> {
        val (happ, incy) = when (profile) {
            ClientRouting.ROSCOM_DEFAULT -> HAPP_DEFAULT to INCY_DEFAULT
            ClientRouting.ROSCOM_WHITELIST -> HAPP_WHITELIST to INCY_WHITELIST
            ClientRouting.OFF -> "" to ""
            ClientRouting.CUSTOM -> error("a custom profile is not managed by the app")
        }
        val on = profile != ClientRouting.OFF
        return mapOf(
            "subEnableRouting" to JsonPrimitive(on),
            "subRoutingRules" to JsonPrimitive(happ),
            "subIncyEnableRouting" to JsonPrimitive(on),
            "subIncyRoutingRules" to JsonPrimitive(incy),
        )
    }

    fun profileOf(settings: JsonObject): ClientRouting {
        val happ = settings.string("subRoutingRules").orEmpty().trim()
        val enabled = settings.bool("subEnableRouting") == true
        return when {
            happ.isEmpty() && !enabled -> ClientRouting.OFF
            happ.equals(HAPP_DEFAULT, ignoreCase = true) -> ClientRouting.ROSCOM_DEFAULT
            happ.equals(HAPP_WHITELIST, ignoreCase = true) -> ClientRouting.ROSCOM_WHITELIST
            happ.isEmpty() -> ClientRouting.OFF
            else -> ClientRouting.CUSTOM
        }
    }

    fun hasServerBlock(config: JsonObject): Boolean = rules(config).any { it.tag() in OWN_TAGS }

    /**
     * Returns [config] with the server-side rules added (right after the API rule, so they win over anything that
     * would route Russian traffic out) or removed. Idempotent; other rules are kept in order.
     */
    fun withServerBlock(config: JsonObject, enabled: Boolean): JsonObject {
        val others = rules(config).filter { it.tag() !in OWN_TAGS }
        var outbounds = (config["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val newRules = if (enabled) {
            val blackhole = outbounds.firstOrNull { it.string("protocol") == "blackhole" }?.string("tag")
                ?: OWN_BLACKHOLE.also {
                    outbounds = outbounds + buildJsonObject { put("protocol", "blackhole"); put("tag", it) }
                }
            val own = listOf(
                buildJsonObject {
                    put("type", "field")
                    put("ruleTag", RULE_TAG_DOMAINS)
                    put("domain", buildJsonArray {
                        add(JsonPrimitive("ext:$GEOSITE_FILE:category-ru"))
                        add(JsonPrimitive("ext:$GEOSITE_FILE:whitelist"))
                    })
                    put("outboundTag", blackhole)
                },
                buildJsonObject {
                    put("type", "field")
                    put("ruleTag", RULE_TAG_IPS)
                    put("ip", buildJsonArray { add(JsonPrimitive("ext:$GEOIP_FILE:direct")) })
                    put("outboundTag", blackhole)
                },
            )
            val apiIndex = others.indexOfFirst { rule -> (rule["inboundTag"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == "api" } == true }
            others.take(apiIndex + 1) + own + others.drop(apiIndex + 1)
        } else {
            // Drop the blackhole this app added if nothing else routes to it any more.
            if (others.none { it.string("outboundTag") == OWN_BLACKHOLE }) outbounds = outbounds.filter { it.string("tag") != OWN_BLACKHOLE }
            others
        }
        val routing = (config["routing"] as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(
            config + mapOf(
                "routing" to JsonObject(routing + ("rules" to JsonArray(newRules))),
                "outbounds" to JsonArray(outbounds),
            ),
        )
    }

    private val OWN_TAGS = setOf(RULE_TAG_DOMAINS, RULE_TAG_IPS)

    private fun rules(config: JsonObject): List<JsonObject> =
        ((config["routing"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.tag(): String? = string("ruleTag")
}
