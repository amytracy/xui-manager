package dev.autorasqr.xuimanager.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/**
 * An in-memory 3x-ui v3.10.2 panel for the emulator stand: the API routes the app uses, with the same envelope and
 * the same rules for inbound updates (stored clients and the enable flag are kept). Not a full panel.
 */
class FakePanel(private val apiToken: String, private val log: (String) -> Unit) : Dispatcher() {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val inbounds = mutableListOf<JsonObject>()
    private val clients = mutableListOf<JsonObject>()
    private var nextInboundId = 1
    /** Nodes answer unless their address contains "down", which stands in for an unreachable panel. */
    private val nodes = mutableListOf<JsonObject>()
    private var nextNodeId = 1
    private var settings = buildJsonObject {
        put("subEnable", false)
        put("subPort", 2096)
        put("subPath", "/sub/")
        put("subDomain", "")
        put("subCertFile", "")
        put("subKeyFile", "")
        put("subURI", "")
    }
    @Volatile var username: String = ""
    @Volatile var password: String = ""
    private val sessions = mutableSetOf<String>()
    private val startedAt = System.currentTimeMillis()

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val apiIndex = path.indexOf("/panel/api/")
        val method = request.method
        log("panel ${method} ${path.substringAfter("/panel/api/", path)}")
        if (apiIndex < 0) return web(request, path)
        if (!authorized(request)) return MockResponse.Builder().code(401).body("").build()
        val route = path.substring(apiIndex + "/panel/api/".length)
        val body = request.body?.utf8().orEmpty()
        return try {
            route(method, route, body)
        } catch (e: Exception) {
            log("panel error: $e")
            fail(e.message ?: "error")
        }
    }

    private fun authorized(request: RecordedRequest): Boolean {
        if (request.headers["Authorization"] == "Bearer $apiToken") return true
        val cookie = request.headers["Cookie"].orEmpty()
        return sessions.any { cookie.contains("3x-ui=$it") }
    }

    private fun route(method: String, route: String, body: String): MockResponse {
        val parts = route.split('/')
        return when {
            route == "server/status" -> ok(status())
            route == "setting/all" -> ok(settings)
            route == "setting/update" -> {
                settings = json.parseToJsonElement(body).jsonObject
                ok(null)
            }
            route == "setting/restartPanel" || route == "server/restartXrayService" -> ok(null)
            route == "server/getNewX25519Cert" -> ok(buildJsonObject { put("privateKey", key()); put("publicKey", key()) })
            route == "server/scanRealityTargets" -> ok(scan(form(body)["targets"].orEmpty()))
            route == "inbounds/list" -> ok(JsonArray(inbounds.map(::withClients)))
            parts[0] == "inbounds" && parts.getOrNull(1) == "get" -> ok(withClients(inbound(parts[2].toInt())))
            route == "inbounds/add" -> ok(addInbound(json.parseToJsonElement(body).jsonObject))
            parts[0] == "inbounds" && parts.getOrNull(1) == "update" -> ok(updateInbound(parts[2].toInt(), json.parseToJsonElement(body).jsonObject))
            parts[0] == "inbounds" && parts.getOrNull(1) == "del" -> {
                val id = parts[2].toInt()
                inbounds.removeAll { it.int("id") == id }
                clients.replaceAll { c -> c.with("inboundIds", JsonArray(c.ids().filter { it != id }.map(::JsonPrimitive))) }
                ok(JsonPrimitive(id))
            }
            parts[0] == "inbounds" && parts.getOrNull(1) == "setEnable" -> {
                val id = parts[2].toInt()
                val enable = json.parseToJsonElement(body).jsonObject["enable"]!!.jsonPrimitive.booleanOrNull ?: true
                replaceInbound(id) { it.with("enable", JsonPrimitive(enable)) }
                ok(null)
            }
            route == "clients/list" -> ok(JsonArray(clients.map(::clientRow)))
            route == "clients/onlines" -> ok(JsonArray(emptyList()))
            route == "clients/add" -> {
                val payload = json.parseToJsonElement(body).jsonObject
                val client = payload["client"]!!.jsonObject
                val email = client.str("email")
                if (clients.any { it.str("email") == email }) return fail("email already exists")
                clients += JsonObject(client + mapOf("uuid" to JsonPrimitive(java.util.UUID.randomUUID().toString()), "inboundIds" to payload["inboundIds"]!!, "up" to JsonPrimitive(0), "down" to JsonPrimitive(0)))
                ok(null)
            }
            parts[0] == "clients" && parts.getOrNull(1) == "get" -> {
                val client = client(decode(parts[2]))
                ok(buildJsonObject { put("client", client); put("inboundIds", client["inboundIds"] ?: JsonArray(emptyList())) })
            }
            parts[0] == "clients" && parts.getOrNull(1) == "update" -> {
                val email = decode(parts[2])
                val changes = json.parseToJsonElement(body).jsonObject
                replaceClient(email) { JsonObject(it + changes.filterKeys { k -> k !in setOf("inboundIds") }) }
                ok(null)
            }
            parts[0] == "clients" && parts.getOrNull(1) == "del" -> {
                clients.removeAll { it.str("email") == decode(parts[2]) }
                ok(null)
            }
            parts[0] == "clients" && (parts.getOrNull(2) == "attach" || parts.getOrNull(2) == "detach") -> {
                val ids = json.parseToJsonElement(body).jsonObject["inboundIds"]!!.jsonArray.map { it.jsonPrimitive.intOrNull!! }
                replaceClient(decode(parts[1])) { c ->
                    val now = if (parts[2] == "attach") (c.ids() + ids).distinct() else c.ids() - ids.toSet()
                    c.with("inboundIds", JsonArray(now.map(::JsonPrimitive)))
                }
                ok(null)
            }
            route == "clients/bulkEnable" || route == "clients/bulkDisable" -> {
                val emails = json.parseToJsonElement(body).jsonObject["emails"]!!.jsonArray.map { it.jsonPrimitive.content }
                emails.forEach { e -> replaceClient(e) { it.with("enable", JsonPrimitive(route.endsWith("Enable"))) } }
                ok(buildJsonObject { put("changed", emails.size); put("skipped", JsonArray(emptyList())) })
            }
            parts[0] == "clients" && parts.getOrNull(1) == "resetTraffic" -> {
                replaceClient(decode(parts[2])) { it.with("up", JsonPrimitive(0)).with("down", JsonPrimitive(0)) }
                ok(null)
            }
            route == "nodes/list" -> ok(JsonArray(nodes.map(::nodeView)))
            route == "nodes/test" -> ok(probe(json.parseToJsonElement(body).jsonObject))
            route == "nodes/certFingerprint" -> ok(JsonPrimitive("k3b1e2eFakeFingerprintBase64Sha256="))
            route == "nodes/inbounds" -> ok(
                JsonArray(listOf(buildJsonObject { put("tag", "in-443-tcp"); put("remark", "edge"); put("protocol", "vless"); put("port", 443) })),
            )
            route == "nodes/add" -> {
                val payload = json.parseToJsonElement(body).jsonObject
                if (payload.str("address").contains("down")) return fail("Add node: node unreachable (dial tcp: i/o timeout)")
                if (nodes.any { it.str("name").equals(payload.str("name"), ignoreCase = true) }) return fail("node name already exists")
                val node = JsonObject(payload - "apiToken" + mapOf("id" to JsonPrimitive(nextNodeId++), "hasApiToken" to JsonPrimitive(payload.str("apiToken").isNotEmpty())))
                nodes += node
                ok(nodeView(node))
            }
            parts[0] == "nodes" && parts.getOrNull(1) == "update" -> {
                val id = parts[2].toInt()
                val payload = json.parseToJsonElement(body).jsonObject
                if (payload.str("address").contains("down")) return fail("Update node: node unreachable")
                nodes.replaceAll { if (it.int("id") == id) JsonObject(it + (payload - "apiToken") + ("id" to JsonPrimitive(id))) else it }
                ok(null)
            }
            parts[0] == "nodes" && parts.getOrNull(1) == "del" -> {
                nodes.removeAll { it.int("id") == parts[2].toInt() }
                ok(null)
            }
            parts[0] == "nodes" && parts.getOrNull(1) == "setEnable" -> {
                val id = parts[2].toInt()
                val enable = json.parseToJsonElement(body).jsonObject["enable"]!!.jsonPrimitive.booleanOrNull ?: true
                nodes.replaceAll { if (it.int("id") == id) it.with("enable", JsonPrimitive(enable)) else it }
                ok(null)
            }
            parts[0] == "nodes" && parts.getOrNull(1) == "probe" -> ok(probe(nodes.first { it.int("id") == parts[2].toInt() }))
            parts[0] == "nodes" && parts.getOrNull(1) == "webCert" -> ok(
                buildJsonObject { put("webCertFile", "/root/cert/node/fullchain.pem"); put("webKeyFile", "/root/cert/node/privkey.pem") },
            )
            else -> MockResponse.Builder().code(404).body("").build()
        }
    }

    private fun online(node: JsonObject) = !node.str("address").contains("down")

    private fun probe(node: JsonObject) = buildJsonObject {
        val up = online(node)
        put("status", if (up) "online" else "offline")
        put("error", if (up) "" else "dial tcp ${node.str("address")}:${node.int("port")}: i/o timeout")
        put("latencyMs", if (up) 30 + node.str("address").length else 0)
        put("cpuPct", if (up) 7.5 else 0.0)
        put("memPct", if (up) 41.0 else 0.0)
        put("uptimeSecs", if (up) (System.currentTimeMillis() - startedAt) / 1000 + 3600 else 0)
        put("xrayState", if (up) "running" else "")
        put("xrayVersion", if (up) "25.10.31" else "")
        put("xrayError", "")
        put("panelVersion", if (up) "v3.10.2" else "")
    }

    private fun nodeView(node: JsonObject): JsonObject {
        val id = node.int("id")
        val hosted = inbounds.filter { it.int("nodeId") == id }
        val enabled = (node["enable"] as? JsonPrimitive)?.booleanOrNull ?: true
        return JsonObject(
            node + probe(node) + mapOf(
                "status" to JsonPrimitive(if (online(node) && enabled) "online" else "offline"),
                "lastError" to JsonPrimitive(if (online(node)) "" else "dial tcp: i/o timeout"),
                "inboundCount" to JsonPrimitive(hosted.size),
                "clientCount" to JsonPrimitive(clients.count { c -> c.ids().any { cid -> hosted.any { it.int("id") == cid } } }),
                "onlineCount" to JsonPrimitive(0),
                "transitive" to JsonPrimitive(false),
            ),
        )
    }

    /** csrf-token, login and a placeholder panel page for the WebView. */
    private fun web(request: RecordedRequest, path: String): MockResponse = when {
        path.endsWith("/csrf-token") -> ok(JsonPrimitive("csrf-e2e"))
        path.endsWith("/login") && request.method == "POST" -> {
            val form = form(request.body?.utf8().orEmpty())
            if (form["username"] == username && form["password"] == password) {
                val session = key()
                sessions += session
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .addHeader("Set-Cookie", "3x-ui=$session; Path=/; HttpOnly; Secure")
                    .body("""{"success":true,"msg":"Login successful"}""").build()
            } else {
                fail("Wrong username or password")
            }
        }
        else -> MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8")
            .body(
                "<!doctype html><html><head><meta name=viewport content='width=device-width'><title>3x-ui</title></head>" +
                    "<body style='font-family:sans-serif;padding:24px'><h2>Fake 3x-ui panel (emulator stand)</h2>" +
                    "<p>Inbounds: ${inbounds.size}, clients: ${clients.size}</p></body></html>",
            ).build()
    }

    private fun status() = buildJsonObject {
        put("cpu", 3.0 + random.nextInt(20))
        putObj("mem") { put("current", 412_000_000L + random.nextInt(50_000_000)); put("total", 1_024_000_000L) }
        put("uptime", (System.currentTimeMillis() - startedAt) / 1000 + 86_400)
        put("loads", buildJsonArray { add(JsonPrimitive(0.12)); add(JsonPrimitive(0.08)); add(JsonPrimitive(0.05)) })
        putObj("xray") { put("state", "running"); put("version", "26.9.5"); put("errorMsg", "") }
        put("panelVersion", "v3.10.2")
    }

    private fun scan(targets: String) = JsonArray(
        targets.split(',').filter { it.isNotBlank() }.mapIndexed { index, host ->
            buildJsonObject {
                put("target", "$host:443")
                put("host", host)
                // Anything with "bad" in the name plays an unsuitable site.
                put("feasible", !host.contains("bad"))
                put("latencyMs", 20 + index * 7)
            }
        },
    )

    private fun addInbound(body: JsonObject): JsonObject {
        val port = body.int("port")
        // Ports collide per machine: the panel's server or one node.
        if (inbounds.any { it.int("port") == port && it.int("nodeId") == body.int("nodeId") }) error("port $port is already in use")
        val inbound = normalize(body)
            .with("id", JsonPrimitive(nextInboundId++))
            .with("up", JsonPrimitive(random.nextInt(900_000_000).toLong()))
            .with("down", JsonPrimitive(random.nextInt(2_000_000_000).toLong()))
        inbounds += inbound
        return inbound
    }

    private fun updateInbound(id: Int, body: JsonObject): JsonObject {
        val stored = inbound(id)
        val incoming = normalize(body)
        // As 3x-ui v3: the clients and the enable flag have their own endpoints.
        val merged = JsonObject(stored + incoming.filterKeys { it !in setOf("id", "enable", "up", "down", "clientStats") })
        replaceInbound(id) { merged }
        return merged
    }

    private fun normalize(body: JsonObject) = JsonObject(
        body.mapValues { (key, value) ->
            if (key in setOf("settings", "streamSettings", "sniffing") && value is JsonPrimitive && value.isString) {
                json.parseToJsonElement(value.content)
            } else {
                value
            }
        },
    )

    private fun withClients(inbound: JsonObject): JsonObject {
        val id = inbound.int("id")
        val attached = clients.filter { id in it.ids() }.map { c -> buildJsonObject { put("email", c.str("email")); put("enable", c["enable"] ?: JsonPrimitive(true)) } }
        val settings = inbound["settings"] as? JsonObject ?: JsonObject(emptyMap())
        return inbound.with("settings", settings.with("clients", JsonArray(attached)))
    }

    private fun clientRow(c: JsonObject) = buildJsonObject {
        put("email", c.str("email"))
        put("subId", c.str("subId"))
        put("totalGB", c["totalGB"]?.jsonPrimitive?.longOrNull ?: 0)
        put("expiryTime", c["expiryTime"]?.jsonPrimitive?.longOrNull ?: 0)
        put("enable", c["enable"]?.jsonPrimitive?.booleanOrNull ?: true)
        put("comment", c.str("comment"))
        put("inboundIds", c["inboundIds"] ?: JsonArray(emptyList()))
        putObj("traffic") { put("up", c["up"]?.jsonPrimitive?.longOrNull ?: 0); put("down", c["down"]?.jsonPrimitive?.longOrNull ?: 0) }
    }

    private fun inbound(id: Int) = inbounds.firstOrNull { it.int("id") == id } ?: error("inbound $id not found")
    private fun client(email: String) = clients.firstOrNull { it.str("email") == email } ?: error("client $email not found")
    private fun replaceInbound(id: Int, change: (JsonObject) -> JsonObject) = inbounds.replaceAll { if (it.int("id") == id) change(it) else it }
    private fun replaceClient(email: String, change: (JsonObject) -> JsonObject) = clients.replaceAll { if (it.str("email") == email) change(it) else it }

    private fun ok(obj: JsonElement?) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body(buildJsonObject { put("success", true); put("msg", ""); put("obj", obj ?: JsonNull) }.toString()).build()

    private fun fail(message: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body(buildJsonObject { put("success", false); put("msg", message); put("obj", JsonNull) }.toString()).build()

    private fun key() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private fun form(body: String) = body.split('&').filter { '=' in it }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: -1
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.ids() = (this["inboundIds"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun kotlinx.serialization.json.JsonObjectBuilder.putObj(key: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        put(key, buildJsonObject(block))
}
