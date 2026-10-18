package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Browser-style sign-in, as the panel's own login page does it:
 * v3 — GET csrf-token (creates the session cookie), then POST login with the X-CSRF-Token header;
 * v2 — POST login as a form. The session cookie ends up in the client's cookie jar.
 */
internal object PanelWebLogin {

    fun login(client: OkHttpClient, base: HttpUrl, version: String, username: String, password: String, json: Json) {
        val major = PanelVersion.major(version) ?: throw AppException(AppError.UnsupportedPanelVersion(version))
        val request = if (major >= 3) {
            val token = client.newCall(Request.Builder().url(base.resolve("csrf-token")!!).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw AppException(AppError.PanelError("HTTP ${response.code}"))
                (PanelJson.unwrap(json, response.body.string()) as? JsonPrimitive)?.contentOrNull
            } ?: throw AppException(AppError.PanelError("no CSRF token"))
            val body = buildJsonObject {
                put("username", username)
                put("password", password)
            }.toString().toRequestBody("application/json".toMediaType())
            Request.Builder().url(base.resolve("login")!!).header("X-CSRF-Token", token).post(body).build()
        } else {
            val body = FormBody.Builder().add("username", username).add("password", password).build()
            Request.Builder().url(base.resolve("login")!!).post(body).build()
        }
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AppException(AppError.PanelError("HTTP ${response.code}"))
            try {
                PanelJson.unwrap(json, response.body.string())
            } catch (e: AppException) {
                // {success:false}: wrong credentials, a second factor is required, or the login limiter kicked in.
                throw AppException(AppError.PanelUnauthorized, e)
            }
        }
    }
}
