package com.beezync.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BeezyncApiTest {
    private lateinit var server: MockWebServer
    private lateinit var beezync: Beezync

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        beezync = Beezync(BeezyncConfig("app-1", "pk_test", server.url("/").toString().trimEnd('/')), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun registerDeviceSendsBearerAndBody() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"registered"}"""))
        val r = beezync.registerDevice("tok", "user-1", "android", mapOf("plan" to "pro", "n" to 3))
        assertTrue(r.isSuccess)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/api/register-device", req.path)
        assertEquals("Bearer pk_test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains(""""token":"tok""""), body)
        assertTrue(body.contains(""""platform":"android""""), body)
        assertTrue(body.contains(""""external_user_id":"user-1""""), body)
        assertTrue(body.contains(""""metadata":{"plan":"pro","n":3}"""), body)
        assertEquals("tok", beezync.deviceToken)
    }

    @Test
    fun setUserAndTagsUsePatch() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        beezync.registerDevice("tok")
        server.takeRequest()
        assertTrue(beezync.setUser("u2").isSuccess)
        assertTrue(beezync.setTags(mapOf("a" to true)).isSuccess)
        val a = server.takeRequest()
        assertEquals("PATCH", a.method)
        assertEquals("/v1/api/devices", a.path)
        assertTrue(a.body.readUtf8().contains(""""external_user_id":"u2""""))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""tags":{"a":true}"""))
    }

    @Test
    fun setUserBeforeRegisterFails() = runBlocking {
        assertTrue(beezync.setUser("u").isFailure)
    }

    @Test
    fun trackEventsIncludeToken() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody("{}")) }
        beezync.registerDevice("tok")
        server.takeRequest()
        beezync.trackReceived("n1")
        beezync.trackClicked("n1")
        beezync.trackDismissed("n1")
        val events = (1..3).map { server.takeRequest().body.readUtf8() }
        assertTrue(events[0].contains(""""event":"received"""") && events[0].contains(""""token":"tok""""))
        assertTrue(events[1].contains(""""event":"clicked""""))
        assertTrue(events[2].contains(""""event":"dismissed""""))
    }

    @Test
    fun errorsAreReturnedNotThrown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key","code":"unauthorized"}"""))
        val r = beezync.registerDevice("tok")
        assertTrue(r.isFailure)
        val e = assertIs<BeezyncException>(r.exceptionOrNull())
        assertEquals(401, e.httpStatus)
        assertEquals("unauthorized", e.code)
        assertEquals("bad key", e.message)
        assertEquals(null, beezync.deviceToken)
    }

    @Test
    fun networkFailureIsReturned() = runBlocking {
        server.shutdown()
        assertFalse(beezync.trackReceived("x").isSuccess)
    }

    @Test
    fun unregisterClearsToken() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        beezync.registerDevice("tok")
        assertTrue(beezync.unregisterDevice().isSuccess)
        server.takeRequest()
        assertEquals("/v1/api/unregister-device", server.takeRequest().path)
        assertEquals(null, beezync.deviceToken)
    }
}
