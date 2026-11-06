package com.beezync.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealtimeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val received = LinkedBlockingQueue<String>()
    private val allSockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile private var connections = 0

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections++
            serverSockets.add(webSocket)
            allSockets.add(webSocket)
            webSocket.send("""{"event":"connection_established","data":{"socket_id":"sock-$connections"}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repeat(5) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        allSockets.forEach { runCatching { it.close(1000, null) } }
        runCatching { server.shutdown() }
    }

    private fun client(authorizer: ChannelAuthorizer? = null) = RealtimeClient(
        BeezyncConfig("app-1", "pk_test", server.url("/").toString().trimEnd('/')),
        OkHttpClient(), scope,
        Backoff(baseMs = 50, maxMs = 200, random = Random(1)),
        authorizer, resubscribeGapMs = 1,
    )

    private fun next(): String = assertNotNull(received.poll(5, TimeUnit.SECONDS), "no frame received")

    @Test
    fun connectsWithCredentialsAndReceivesMessages() = runBlocking {
        val c = client()
        val first = CompletableDeferred<BeezyncMessage>()
        val job = scope.launch { first.complete(c.messages.first()) }
        c.connect()
        val ws = serverSockets.poll(5, TimeUnit.SECONDS)!!
        val path = server.takeRequest(5, TimeUnit.SECONDS)!!.path!!
        assertEquals("/v1/ws?app_id=app-1&key=pk_test", path)
        waitConnected(c)
        ws.send("""{"event":"hi","channel":"c","data":{"a":1},"id":"m1","ts":5}""")
        val m = withTimeout(5_000) { first.await() }
        assertEquals("hi", m.event)
        job.cancel()
        c.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, c.state.value)
    }

    @Test
    fun subscribeBeforeAndAfterConnectSendsFrames() = runBlocking {
        val c = client()
        c.subscribe("early", token = "tk")
        c.connect()
        assertEquals(Protocol.subscribeFrame("early", null, "tk"), next())
        c.subscribe("late", auth = "hex")
        assertEquals(Protocol.subscribeFrame("late", "hex", null), next())
        c.unsubscribe("late")
        assertEquals(Protocol.unsubscribeFrame("late"), next())
        assertEquals(setOf("early"), c.subscribedChannels)
        c.disconnect()
    }

    @Test
    fun reconnectsAfterDropAndResubscribes() = runBlocking {
        val c = client()
        c.subscribe("room")
        c.connect()
        assertEquals(Protocol.subscribeFrame("room", null, null), next())
        serverSockets.poll(5, TimeUnit.SECONDS)!!.close(1001, "going away")
        assertEquals(Protocol.subscribeFrame("room", null, null), next())
        assertEquals(2, connections)
        c.disconnect()
    }

    @Test
    fun closeCode4200ReconnectsImmediately() = runBlocking {
        val slow = RealtimeClient(
            BeezyncConfig("app-1", "pk_test", server.url("/").toString().trimEnd('/')),
            OkHttpClient(), scope, Backoff(baseMs = 20_000, maxMs = 30_000), resubscribeGapMs = 1,
        )
        slow.subscribe("room")
        slow.connect()
        next()
        val t0 = System.nanoTime()
        serverSockets.poll(5, TimeUnit.SECONDS)!!.close(4200, "reconnect")
        assertEquals(Protocol.subscribeFrame("room", null, null), next())
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        slow.disconnect()
    }

    @Test
    fun authorizerIsUsedAgainOnReconnect() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val c = client { socketId, channel -> seen += "$socketId:$channel"; "auth-$socketId" }
        c.subscribe("private-x", auth = "stale")
        c.connect()
        assertEquals(Protocol.subscribeFrame("private-x", "auth-sock-1", null), next())
        serverSockets.poll(5, TimeUnit.SECONDS)!!.close(4200, null)
        assertEquals(Protocol.subscribeFrame("private-x", "auth-sock-2", null), next())
        c.disconnect()
    }

    @Test
    fun subscriptionErrorsAreSurfaced() = runBlocking {
        val c = client()
        val err = CompletableDeferred<SubscriptionError>()
        scope.launch { err.complete(c.subscriptionErrors.first()) }
        c.connect()
        val ws = serverSockets.poll(5, TimeUnit.SECONDS)!!
        waitConnected(c)
        ws.send("""{"event":"subscription_error","channel":"private-a","error":"forbidden"}""")
        assertEquals(SubscriptionError("private-a", "forbidden"), withTimeout(5_000) { err.await() })
        c.disconnect()
    }

    @Test
    fun disconnectStopsReconnecting() = runBlocking {
        val c = client()
        c.connect()
        val ws = serverSockets.poll(5, TimeUnit.SECONDS)!!
        waitConnected(c)
        c.disconnect()
        Thread.sleep(500)
        assertEquals(1, connections)
    }

    private suspend fun waitConnected(c: RealtimeClient) = withTimeout(5_000) {
        c.state.first { it == ConnectionState.CONNECTED }
    }
}
