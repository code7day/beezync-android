package com.beezync.sdk

import kotlinx.serialization.json.JsonElement

enum class MessageKind { PUBLISHED, NOTIFICATION }

data class BeezyncMessage(
    val kind: MessageKind,
    val id: String?,
    val channel: String?,
    val event: String?,
    val data: JsonElement?,
    val timestamp: Long?,
    val title: String?,
    val body: String?,
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class SubscriptionError(val channel: String, val error: String)

class BeezyncException(val httpStatus: Int, val code: String?, message: String) : Exception(message)
