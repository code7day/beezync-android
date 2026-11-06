package com.beezync.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

fun interface ChannelAuthorizer {
    suspend fun authorize(socketId: String, channel: String): String?
}

class RealtimeClient(
    private val config: BeezyncConfig,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    var authorizer: ChannelAuthorizer? = null,
    private val resubscribeGapMs: Long = 55,
) {
    private class Credentials(val auth: String?, val token: String?)

    private val lock = Any()
    private var socket: WebSocket? = null
    private var socketId: String? = null
    private var generation = 0
    private var wanted = false
    private var reconnectJob: Job? = null
    private val subscriptions = LinkedHashMap<String, Credentials>()

    private val _messages = MutableSharedFlow<BeezyncMessage>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _errors = MutableSharedFlow<SubscriptionError>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)

    val messages: SharedFlow<BeezyncMessage> get() = _messages
    val subscriptionErrors: SharedFlow<SubscriptionError> get() = _errors
    val state: StateFlow<ConnectionState> get() = _state

    fun connect() {
        synchronized(lock) {
            if (wanted) return
            wanted = true
        }
        open()
    }

    fun disconnect() {
        val toClose: WebSocket?
        synchronized(lock) {
            wanted = false
            generation++
            reconnectJob?.cancel()
            reconnectJob = null
            toClose = socket
            socket = null
            socketId = null
        }
        toClose?.close(1000, null)
        backoff.reset()
        _state.value = ConnectionState.DISCONNECTED
    }

    fun subscribe(channel: String, auth: String? = null, token: String? = null) {
        val gen: Int
        synchronized(lock) {
            subscriptions[channel] = Credentials(auth, token)
            if (socketId == null) return
            gen = generation
        }
        sendSubscribe(gen, channel)
    }

    fun unsubscribe(channel: String) {
        val ws: WebSocket?
        synchronized(lock) {
            subscriptions.remove(channel)
            ws = if (socketId != null) socket else null
        }
        ws?.send(Protocol.unsubscribeFrame(channel))
    }

    val subscribedChannels: Set<String> get() = synchronized(lock) { subscriptions.keys.toSet() }

    private fun open() {
        val gen: Int
        synchronized(lock) {
            if (!wanted) return
            reconnectJob = null
            gen = ++generation
        }
        _state.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(Protocol.wsUrl(config)).build()
        val ws = client.newWebSocket(request, Listener(gen))
        synchronized(lock) {
            if (gen == generation) socket = ws else ws.cancel()
        }
    }

    private fun sendSubscribe(gen: Int, channel: String) {
        scope.launch {
            val (ws, id, creds) = synchronized(lock) {
                Triple(socket, socketId, subscriptions[channel])
            }
            if (ws == null || id == null || creds == null) return@launch
            val auth = if (creds.token != null) null else (authorizer?.authorize(id, channel) ?: creds.auth)
            synchronized(lock) { if (gen != generation) return@launch }
            ws.send(Protocol.subscribeFrame(channel, auth, creds.token))
        }
    }

    private fun resubscribeAll(gen: Int) {
        val channels = synchronized(lock) { subscriptions.keys.toList() }
        scope.launch {
            for (channel in channels) {
                synchronized(lock) { if (gen != generation) return@launch }
                sendSubscribe(gen, channel)
                delay(resubscribeGapMs)
            }
        }
    }

    private fun handleClosed(gen: Int, code: Int) {
        val wait: Long
        synchronized(lock) {
            if (gen != generation) return
            generation++
            socket = null
            socketId = null
            if (!wanted) {
                _state.value = ConnectionState.DISCONNECTED
                return
            }
            wait = if (code == Protocol.CLOSE_RECONNECT_NOW) 0 else backoff.next()
            _state.value = ConnectionState.DISCONNECTED
            reconnectJob = scope.launch {
                if (wait > 0) delay(wait)
                open()
            }
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) { if (gen != generation) return }
            when (val frame = Protocol.parse(text)) {
                is ServerFrame.Established -> {
                    synchronized(lock) { socketId = frame.socketId }
                    backoff.reset()
                    _state.value = ConnectionState.CONNECTED
                    resubscribeAll(gen)
                }
                is ServerFrame.SubscribeFailed -> _errors.tryEmit(SubscriptionError(frame.channel, frame.error))
                is ServerFrame.Message -> _messages.tryEmit(frame.message)
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code.takeIf { it in 1000..4999 && it != 1005 } ?: 1000, null)
            handleClosed(gen, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleClosed(gen, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleClosed(gen, 0)
        }
    }
}
