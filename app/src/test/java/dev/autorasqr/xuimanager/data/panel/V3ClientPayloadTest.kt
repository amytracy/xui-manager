package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.data.panel.v3.V3ClientPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class V3ClientPayloadTest {

    private val changes = ClientChanges("anna-phone", "newsub1234", 1024, 1_900_000_000_000, enabled = false, inboundIds = listOf(3))

    @Test
    fun `record uuid becomes id and the numeric row id is dropped`() {
        val record = Json.parseToJsonElement(
            """{"id":17,"email":"anna","subId":"abcd1234","uuid":"u-1","password":"","flow":"xtls-rprx-vision","security":"",
               "limitIp":2,"limitHwid":1,"totalGB":0,"expiryTime":0,"enable":true,"tgId":42,"group":"family","comment":"hi",
               "reset":0,"trafficReset":"monthly","trafficResetDay":5,"reverse":null,"allowedIPs":"","createdAt":1}""",
        ).jsonObject
        val payload = V3ClientPayload.update(record, changes)
        assertEquals("u-1", payload["id"]!!.jsonPrimitive.content)
        assertEquals("anna-phone", payload["email"]!!.jsonPrimitive.content)
        assertEquals("newsub1234", payload["subId"]!!.jsonPrimitive.content)
        assertEquals(1024, payload["totalGB"]!!.jsonPrimitive.long)
        assertEquals("false", payload["enable"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", payload["flow"]!!.jsonPrimitive.content)
        assertEquals("auto", payload["security"]!!.jsonPrimitive.content)
        assertEquals("monthly", payload["trafficReset"]!!.jsonPrimitive.content)
        assertEquals(42, payload["tgId"]!!.jsonPrimitive.long)
        assertEquals("family", payload["group"]!!.jsonPrimitive.content)
        assertFalse("allowedIPs" in payload)
        assertFalse("reverse" in payload)
        assertFalse("createdAt" in payload)
    }

    @Test
    fun `wireguard peers keep their keys and allowed IPs as an array`() {
        val record = Json.parseToJsonElement(
            """{"id":1,"email":"wg","uuid":"","privateKey":"pk","publicKey":"pub","allowedIPs":"10.0.0.2/32, 10.0.0.3/32","keepAlive":25}""",
        ).jsonObject
        val payload = V3ClientPayload.update(record, changes)
        assertEquals("pk", payload["privateKey"]!!.jsonPrimitive.content)
        assertEquals(listOf("10.0.0.2/32", "10.0.0.3/32"), payload["allowedIPs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(25, payload["keepAlive"]!!.jsonPrimitive.long)
    }
}
