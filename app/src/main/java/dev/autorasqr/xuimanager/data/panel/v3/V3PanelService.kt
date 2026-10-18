package dev.autorasqr.xuimanager.data.panel.v3

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 3x-ui v3 routes (verified against v3.10.2 docs/public/openapi.json and internal/web/controller).
 * Authenticated with an API token, so no CSRF header is needed. Bodies are parsed leniently in [V3PanelApi]
 * because several endpoints answer with an empty body.
 */
interface V3PanelService {
    @GET("panel/api/server/status")
    suspend fun status(): ResponseBody

    @GET("panel/api/clients/list")
    suspend fun clients(): ResponseBody

    @GET("panel/api/clients/get/{email}")
    suspend fun client(@Path("email") email: String): ResponseBody

    @POST("panel/api/clients/onlines")
    suspend fun onlines(): ResponseBody

    @POST("panel/api/clients/add")
    suspend fun addClient(@Body body: JsonObject): ResponseBody

    @POST("panel/api/clients/update/{email}")
    suspend fun updateClient(@Path("email") email: String, @Body body: JsonObject): ResponseBody

    @POST("panel/api/clients/del/{email}")
    suspend fun deleteClient(@Path("email") email: String): ResponseBody

    @POST("panel/api/clients/{email}/attach")
    suspend fun attach(@Path("email") email: String, @Body body: JsonObject): ResponseBody

    @POST("panel/api/clients/{email}/detach")
    suspend fun detach(@Path("email") email: String, @Body body: JsonObject): ResponseBody

    @POST("panel/api/clients/bulkEnable")
    suspend fun bulkEnable(@Body body: JsonObject): ResponseBody

    @POST("panel/api/clients/bulkDisable")
    suspend fun bulkDisable(@Body body: JsonObject): ResponseBody

    @POST("panel/api/clients/resetTraffic/{email}")
    suspend fun resetTraffic(@Path("email") email: String): ResponseBody

    @GET("panel/api/inbounds/list")
    suspend fun inbounds(): ResponseBody

    @POST("panel/api/inbounds/add")
    suspend fun addInbound(@Body body: JsonObject): ResponseBody

    @GET("panel/api/inbounds/get/{id}")
    suspend fun inbound(@Path("id") id: Int): ResponseBody

    /** Stored clients and the enable flag are kept by the panel whatever the body says. */
    @POST("panel/api/inbounds/update/{id}")
    suspend fun updateInbound(@Path("id") id: Int, @Body body: JsonObject): ResponseBody

    @POST("panel/api/inbounds/del/{id}")
    suspend fun deleteInbound(@Path("id") id: Int): ResponseBody

    @POST("panel/api/inbounds/setEnable/{id}")
    suspend fun setInboundEnable(@Path("id") id: Int, @Body body: JsonObject): ResponseBody

    @POST("panel/api/setting/all")
    suspend fun allSettings(): ResponseBody

    @POST("panel/api/setting/update")
    suspend fun updateSettings(@Body body: JsonObject): ResponseBody

    /** obj is a JSON string: {xraySetting: {...}, outboundTestUrl: "...", ...}. */
    @POST("panel/api/xray/")
    suspend fun xraySetting(): ResponseBody

    @FormUrlEncoded
    @POST("panel/api/xray/update")
    suspend fun updateXraySetting(
        @Field("xraySetting") xraySetting: String,
        @Field("outboundTestUrl") outboundTestUrl: String,
    ): ResponseBody

    @POST("panel/api/setting/restartPanel")
    suspend fun restartPanel(): ResponseBody

    @POST("panel/api/server/restartXrayService")
    suspend fun restartXray(): ResponseBody

    @GET("panel/api/server/getNewX25519Cert")
    suspend fun newX25519(): ResponseBody

    @FormUrlEncoded
    @POST("panel/api/server/scanRealityTargets")
    suspend fun scanRealityTargets(@Field("targets") targets: String): ResponseBody

    @FormUrlEncoded
    @POST("panel/api/server/scanRealityTarget")
    suspend fun scanRealityTarget(
        @Field("target") target: String,
        @Field("sni") sni: String,
        @Field("xver") xver: Int,
        @Field("allowPrivate") allowPrivate: Boolean,
    ): ResponseBody

    @GET("panel/api/server/getNewmldsa65")
    suspend fun newMldsa65(): ResponseBody

    @GET("panel/api/server/getNewVlessEnc")
    suspend fun newVlessEnc(): ResponseBody

    @FormUrlEncoded
    @POST("panel/api/server/getNewEchCert")
    suspend fun newEchCert(@Field("sni") sni: String): ResponseBody

    @FormUrlEncoded
    @POST("panel/api/server/getCertHash")
    suspend fun certHash(@Field("certFile") certFile: String, @Field("certContent") certContent: String): ResponseBody

    @FormUrlEncoded
    @POST("panel/api/server/getRemoteCertHash")
    suspend fun remoteCertHash(@Field("server") server: String, @Field("allowPrivate") allowPrivate: Boolean): ResponseBody

    @GET("panel/api/inbounds/{id}/fallbacks")
    suspend fun fallbacks(@Path("id") id: Int): ResponseBody

    @POST("panel/api/inbounds/{id}/fallbacks")
    suspend fun setFallbacks(@Path("id") id: Int, @Body body: JsonObject): ResponseBody

    @GET("panel/api/nodes/list")
    suspend fun nodes(): ResponseBody

    @POST("panel/api/nodes/add")
    suspend fun addNode(@Body body: JsonObject): ResponseBody

    /** apiToken is write-only: omitted keeps the stored one. */
    @POST("panel/api/nodes/update/{id}")
    suspend fun updateNode(@Path("id") id: Int, @Body body: JsonObject): ResponseBody

    @POST("panel/api/nodes/del/{id}")
    suspend fun deleteNode(@Path("id") id: Int): ResponseBody

    @POST("panel/api/nodes/setEnable/{id}")
    suspend fun setNodeEnable(@Path("id") id: Int, @Body body: JsonObject): ResponseBody

    @POST("panel/api/nodes/probe/{id}")
    suspend fun probeNode(@Path("id") id: Int): ResponseBody

    @POST("panel/api/nodes/test")
    suspend fun testNode(@Body body: JsonObject): ResponseBody

    @POST("panel/api/nodes/certFingerprint")
    suspend fun nodeCertFingerprint(@Body body: JsonObject): ResponseBody

    @POST("panel/api/nodes/inbounds")
    suspend fun nodeInbounds(@Body body: JsonObject): ResponseBody

    @GET("panel/api/nodes/webCert/{id}")
    suspend fun nodeWebCert(@Path("id") id: Int): ResponseBody
}
