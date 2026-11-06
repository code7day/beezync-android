package com.beezync.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveEngineTest {
    private val baseUrl = System.getenv("BEEZYNC_BASE_URL")
    private val appId = System.getenv("BEEZYNC_APP_ID")
    private val publicKey = System.getenv("BEEZYNC_PUBLIC_KEY")
    private val secretKey = System.getenv("BEEZYNC_SECRET_KEY")
    private val http = OkHttpClient()

    private fun secretCall(method: String, path: String, body: String? = null): String {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Authorization", "Bearer $secretKey")
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use {
            val text = it.body!!.string()
            assertTrue(it.isSuccessful, "$method $path -> ${it.code} $text")
            return text
        }
    }

    private fun field(json: String, key: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.getValue(key).jsonPrimitive.content

    @Test
    fun endToEnd() = runBlocking {
        assumeTrue(baseUrl != null && appId != null && publicKey != null && secretKey != null)
        val sdk = Beezync(BeezyncConfig(appId, publicKey, baseUrl))
        val user = "sdk-android-" + UUID.randomUUID()
        val token = "android-live-" + UUID.randomUUID()
        val channel = "live-" + UUID.randomUUID().toString().take(8)

        assertTrue(sdk.registerDevice(token, user, "android", mapOf("lang" to "es")).isSuccess)
        assertTrue(sdk.setTags(mapOf("plan" to "pro")).isSuccess)
        assertTrue(sdk.setUser("$user-b").isSuccess)

        sdk.connect()
        withTimeout(10_000) { sdk.connectionState.first { it == ConnectionState.CONNECTED } }
        sdk.subscribe(channel)
        Thread.sleep(500)

        val published = kotlinx.coroutines.CompletableDeferred<BeezyncMessage>()
        val sub = sdk.onMessage { if (it.kind == MessageKind.PUBLISHED && !published.isCompleted) published.complete(it) }
        secretCall("POST", "/v1/api/publish", """{"channel":"$channel","event":"live.test","data":{"hello":"world"}}""")
        val pub = withTimeout(10_000) { published.await() }
        assertEquals("live.test", pub.event)
        assertEquals(channel, pub.channel)
        assertEquals("""{"hello":"world"}""", pub.data.toString())
        sub.close()

        val notified = kotlinx.coroutines.CompletableDeferred<BeezyncMessage>()
        val sub2 = sdk.onMessage { if (it.kind == MessageKind.NOTIFICATION && !notified.isCompleted) notified.complete(it) }
        val sent = secretCall("POST", "/v1/api/send-push",
            """{"title":"Hola","body":"Mundo","target_type":"channel","target_value":"$channel","transport":"ws"}""")
        val id = field(sent, "id")
        val note = withTimeout(10_000) { notified.await() }
        assertEquals("Hola", note.title)
        assertEquals(id, note.id)
        sub2.close()

        assertTrue(sdk.trackReceived(id).isSuccess)
        assertTrue(sdk.trackClicked(id).isSuccess)
        assertTrue(sdk.trackReceived(id).isSuccess)
        Thread.sleep(500)
        val stats = secretCall("GET", "/v1/api/notifications/$id")
        println("notification stats: $stats")
        assertEquals("1", field(stats, "received"))
        assertEquals("1", field(stats, "clicked"))

        sdk.unsubscribe(channel)
        sdk.disconnect()
        assertTrue(sdk.unregisterDevice().isSuccess)
        assertNotNull(id)
        Unit
    }
}
