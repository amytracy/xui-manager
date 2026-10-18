package dev.autorasqr.xuimanager.data.panel.v2

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 3x-ui 2.x routes (verified against web/controller at v2.4.7, v2.6.0, v2.7.0 and v2.9.4). Server routes moved
 * from /server/... (POST) to /panel/api/server/... in v2.7.0; the `Legacy` variants serve 2.4–2.6.
 * Raw [Response]s are returned so an expired session (401/404/redirect) can be detected and re-established.
 */
interface V2PanelService {
    @FormUrlEncoded
    @POST("login")
    suspend fun login(@Field("username") username: String, @Field("password") password: String): Response<ResponseBody>

    @GET("panel/api/inbounds/list")
    suspend fun inbounds(): Response<ResponseBody>

    @POST("panel/api/inbounds/onlines")
    suspend fun onlines(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("panel/api/inbounds/addClient")
    suspend fun addClient(@Field("id") inboundId: Int, @Field("settings") settings: String): Response<ResponseBody>

    @FormUrlEncoded
    @POST("panel/api/inbounds/updateClient/{clientId}")
    suspend fun updateClient(
        @Path("clientId") clientId: String,
        @Field("id") inboundId: Int,
        @Field("settings") settings: String,
    ): Response<ResponseBody>

    @POST("panel/api/inbounds/{id}/delClient/{clientId}")
    suspend fun deleteClient(@Path("id") inboundId: Int, @Path("clientId") clientId: String): Response<ResponseBody>

    @POST("panel/api/inbounds/{id}/resetClientTraffic/{email}")
    suspend fun resetClientTraffic(@Path("id") inboundId: Int, @Path("email") email: String): Response<ResponseBody>

    @FormUrlEncoded
    @POST("panel/api/inbounds/add")
    suspend fun addInbound(@FieldMap fields: Map<String, String>): Response<ResponseBody>

    @GET("panel/api/inbounds/get/{id}")
    suspend fun inbound(@Path("id") id: Int): Response<ResponseBody>

    /** Replaces the whole inbound, clients in settings included. */
    @FormUrlEncoded
    @POST("panel/api/inbounds/update/{id}")
    suspend fun updateInbound(@Path("id") id: Int, @FieldMap fields: Map<String, String>): Response<ResponseBody>

    @POST("panel/api/inbounds/del/{id}")
    suspend fun deleteInbound(@Path("id") id: Int): Response<ResponseBody>

    @POST("panel/setting/all")
    suspend fun allSettings(): Response<ResponseBody>

    @POST("panel/setting/update")
    suspend fun updateSettings(@Body body: JsonObject): Response<ResponseBody>

    @POST("panel/setting/restartPanel")
    suspend fun restartPanel(): Response<ResponseBody>

    @GET("panel/api/server/status")
    suspend fun status(): Response<ResponseBody>

    @POST("server/status")
    suspend fun statusLegacy(): Response<ResponseBody>

    @POST("panel/api/server/restartXrayService")
    suspend fun restartXray(): Response<ResponseBody>

    @POST("server/restartXrayService")
    suspend fun restartXrayLegacy(): Response<ResponseBody>

    @GET("panel/api/server/getNewX25519Cert")
    suspend fun newX25519(): Response<ResponseBody>

    @POST("server/getNewX25519Cert")
    suspend fun newX25519Legacy(): Response<ResponseBody>
}
