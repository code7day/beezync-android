package com.beezync.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed class ServerFrame {
    data class Established(val socketId: String) : ServerFrame()
    data class Subscribed(val channel: String) : ServerFrame()
    data class SubscribeFailed(val channel: String, val error: String) : ServerFrame()
    object Pong : ServerFrame()
    data class Message(val message: BeezyncMessage) : ServerFrame()
    object Unknown : ServerFrame()
}

object Protocol {
    const val CLOSE_RECONNECT_NOW = 4200

    internal val json = Json { ignoreUnknownKeys = true }

    fun wsUrl(config: BeezyncConfig): String {
        val base = config.httpBase
        val scheme = if (base.startsWith("https://")) "wss://" + base.removePrefix("https://") else "ws://" + base.removePrefix("http://")
        return "$scheme/v1/ws?app_id=${enc(config.appId)}&key=${enc(config.publicKey)}"
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    fun subscribeFrame(channel: String, auth: String?, token: String?): String = buildJsonObject {
        put("action", "subscribe")
        put("channel", channel)
        if (token != null) put("token", token) else if (auth != null) put("auth", auth)
    }.toString()

    fun unsubscribeFrame(channel: String): String = buildJsonObject {
        put("action", "unsubscribe")
        put("channel", channel)
    }.toString()

    fun pingFrame(): String = """{"action":"ping"}"""

    fun parse(text: String): ServerFrame {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return ServerFrame.Unknown

        val event = obj.str("event")
        val channel = obj.str("channel")
        return when (event) {
            "connection_established" -> {
                val socketId = payload(obj["data"])?.str("socket_id")
                if (socketId != null) ServerFrame.Established(socketId) else ServerFrame.Unknown
            }
            "subscription_succeeded", "pusher_internal:subscription_succeeded" ->
                if (channel != null) ServerFrame.Subscribed(channel) else ServerFrame.Unknown
            "subscription_error" ->
                ServerFrame.SubscribeFailed(channel ?: "", obj.str("error") ?: payload(obj["data"])?.str("error") ?: "subscription_error")
            "pong" -> ServerFrame.Pong
            null -> {
                val id = obj.str("id")
                val title = obj.str("title")
                val body = obj.str("body")
                if (id == null && title == null && body == null) {
                    ServerFrame.Unknown
                } else {
                    ServerFrame.Message(
                        BeezyncMessage(MessageKind.NOTIFICATION, id, channel, null, obj["data"], null, title, body)
                    )
                }
            }
            else -> ServerFrame.Message(
                BeezyncMessage(
                    MessageKind.PUBLISHED, obj.str("id"), channel, event, obj["data"],
                    (obj["ts"] as? JsonPrimitive)?.longOrNull, null, null,
                )
            )
        }
    }

    private fun payload(data: JsonElement?): JsonObject? = when (data) {
        is JsonObject -> data
        is JsonPrimitive -> if (data.isString) try { json.parseToJsonElement(data.content) as? JsonObject } catch (e: Exception) { null } else null
        else -> null
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key]
        return if (v == null || v is JsonNull) null else v.jsonPrimitive.contentOrNull
    }

    internal fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }
}
