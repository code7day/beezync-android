package com.beezync.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.TimeUnit

interface BeezyncStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore : BeezyncStore {
    private val map = HashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class Beezync(
    val config: BeezyncConfig,
    httpClient: OkHttpClient? = null,
    private val store: BeezyncStore = MemoryStore(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    backoff: Backoff = Backoff(),
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val api = BeezyncApi(config, client)
    val realtime = RealtimeClient(config, client, scope, backoff)

    val messages: SharedFlow<BeezyncMessage> get() = realtime.messages
    val connectionState: StateFlow<ConnectionState> get() = realtime.state
    val subscriptionErrors: SharedFlow<SubscriptionError> get() = realtime.subscriptionErrors

    val deviceToken: String? get() = store.get(KEY_TOKEN)
    val externalUserId: String? get() = store.get(KEY_USER)
    val platform: String get() = store.get(KEY_PLATFORM) ?: "android"

    suspend fun registerDevice(
        token: String,
        externalUserId: String? = null,
        platform: String = "android",
        tags: Map<String, Any?>? = null,
    ): Result<Unit> {
        val user = externalUserId ?: this.externalUserId
        val result = api.registerDevice(token, platform, user, tags)
        if (result.isSuccess) {
            store.put(KEY_TOKEN, token)
            store.put(KEY_PLATFORM, platform)
            store.put(KEY_USER, user)
        }
        return result
    }

    suspend fun unregisterDevice(token: String? = null): Result<Unit> {
        val t = token ?: deviceToken ?: return Result.failure(IllegalStateException("no device token"))
        val result = api.unregisterDevice(t)
        if (result.isSuccess && t == deviceToken) {
            store.put(KEY_TOKEN, null)
            store.put(KEY_USER, null)
        }
        return result
    }

    suspend fun setUser(externalUserId: String): Result<Unit> {
        val t = deviceToken ?: return Result.failure(IllegalStateException("registerDevice must be called first"))
        val result = api.updateDevice(t, externalUserId = externalUserId)
        if (result.isSuccess) store.put(KEY_USER, externalUserId)
        return result
    }

    suspend fun setTags(tags: Map<String, Any?>): Result<Unit> {
        val t = deviceToken ?: return Result.failure(IllegalStateException("registerDevice must be called first"))
        return api.updateDevice(t, tags = tags)
    }

    fun connect() = realtime.connect()
    fun disconnect() = realtime.disconnect()
    fun subscribe(channel: String, auth: String? = null, token: String? = null) = realtime.subscribe(channel, auth, token)
    fun unsubscribe(channel: String) = realtime.unsubscribe(channel)

    fun onMessage(handler: (BeezyncMessage) -> Unit): Closeable {
        val job = scope.launch { realtime.messages.collect { handler(it) } }
        return Closeable { job.cancel() }
    }

    suspend fun trackReceived(id: String): Result<Unit> = api.trackEvent(id, "received", deviceToken)
    suspend fun trackClicked(id: String): Result<Unit> = api.trackEvent(id, "clicked", deviceToken)
    suspend fun trackDismissed(id: String): Result<Unit> = api.trackEvent(id, "dismissed", deviceToken)

    companion object {
        private const val KEY_TOKEN = "device_token"
        private const val KEY_USER = "external_user_id"
        private const val KEY_PLATFORM = "platform"

        @Volatile
        var shared: Beezync? = null
            private set

        fun init(config: BeezyncConfig, store: BeezyncStore = MemoryStore()): Beezync {
            val instance = Beezync(config, store = store)
            shared = instance
            return instance
        }

        fun requireShared(): Beezync = shared ?: error("Beezync.init(...) was not called")
    }
}

object BeezyncPush {
    const val DATA_KEY = "bz_id"

    fun notificationId(data: Map<String, String?>?): String? =
        data?.get(DATA_KEY)?.takeIf { it.isNotBlank() }
}
