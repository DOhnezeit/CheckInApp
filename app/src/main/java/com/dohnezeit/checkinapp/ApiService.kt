package com.dohnezeit.checkinapp

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

data class RegisterCheckerRequest(
    val checker_id: String,
    val checker_token: String
)

data class RegisterWatcherRequest(
    val checker_id: String,
    val watcher_id: String,
    val watcher_token: String
)

data class CheckinRequest(
    val checker_id: String,
    val timestamp: Long? = null,
    val check_interval: Float? = 1f,  // in minutes
    val check_window: Float? = 0.5f   // in minutes
)

data class SleepRequest(
    val checker_id: String
)

data class ApiResponse(
    val ok: Boolean,
    val timestamp: Long? = null
)

data class StatusResponse(
    val checker_id: String,
    val last_checkin: Long?,
    val missed_notified: Boolean?,
    val check_interval: Float?,
    val check_window: Float?,
    val sleeping: Boolean?,
    val watchers: List<String>
)

data class AcknowledgeAlarmRequest(
    val checker_id: String
)

interface ApiService {
    @POST("register_checker")
    suspend fun registerChecker(
        @Body request: RegisterCheckerRequest
    ): Response<ApiResponse>

    @POST("register_watcher")
    suspend fun registerWatcher(
        @Body request: RegisterWatcherRequest
    ): Response<ApiResponse>

    @POST("checkin")
    suspend fun checkin(
        @Body request: CheckinRequest
    ): Response<ApiResponse>

    @POST("sleep")
    suspend fun sleep(
        @Body request: SleepRequest
    ): Response<ApiResponse>

    @POST("/acknowledge_alarm")
    suspend fun acknowledgeAlarm(
        @Body request: AcknowledgeAlarmRequest
    ): Response<ApiResponse>

    @GET("status/{checker_id}")
    suspend fun getStatus(
        @Path("checker_id") checkerId: String
    ): Response<StatusResponse>

    @DELETE("unregister_checker/{checker_id}")
    suspend fun unregisterChecker(
        @Path("checker_id") checkerId: String
    ): Response<ApiResponse>

    @DELETE("unregister_watcher/{checker_id}/{watcher_id}")
    suspend fun unregisterWatcher(
        @Path("checker_id") checkerId: String,
        @Path("watcher_id") watcherId: String
    ): Response<ApiResponse>
}

object RetrofitClient {
    private const val BASE_URL = "https://api.atempora.de/"
    private var apiKey: String = ""

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun create(): ApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}