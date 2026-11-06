package com.beezync.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BeezyncApi(
    private val config: BeezyncConfig,
    private val client: OkHttpClient,
) {
    suspend fun registerDevice(
        token: String,
        platform: String,
        externalUserId: String? = null,
        tags: Map<String, Any?>? = null,
    ): Result<Unit> = send("POST", "/v1/api/register-device", buildJsonObject {
        put("token", token)
        put("platform", platform)
        if (externalUserId != null) put("external_user_id", externalUserId)
        if (!tags.isNullOrEmpty()) put("metadata", Protocol.toJson(tags))
    })

    suspend fun unregisterDevice(token: String): Result<Unit> =
        send("POST", "/v1/api/unregister-device", buildJsonObject { put("token", token) })

    suspend fun updateDevice(token: String, externalUserId: String? = null, tags: Map<String, Any?>? = null): Result<Unit> =
        send("PATCH", "/v1/api/devices", buildJsonObject {
            put("token", token)
            if (externalUserId != null) put("external_user_id", externalUserId)
            if (tags != null) put("tags", Protocol.toJson(tags))
        })

    suspend fun trackEvent(notificationId: String, event: String, token: String? = null): Result<Unit> =
        send("POST", "/v1/api/notification-events", buildJsonObject {
            put("notification_id", notificationId)
            put("event", event)
            if (token != null) put("token", token)
        })

    private suspend fun send(method: String, path: String, body: JsonObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(config.httpBase + path)
                    .header("Authorization", "Bearer ${config.publicKey}")
                    .method(method, body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        Result.failure(parseError(response.code, response.body?.string().orEmpty()))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseError(status: Int, raw: String): BeezyncException {
        var message = "HTTP $status"
        var code: String? = null
        try {
            val obj: JsonElement = Protocol.json.parseToJsonElement(raw)
            if (obj is JsonObject) {
                obj["error"]?.jsonPrimitive?.contentOrNull?.let { message = it }
                code = obj["code"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) {
        }
        return BeezyncException(status, code, message)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
