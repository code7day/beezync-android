package com.beezync.sdk

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackoffTest {
    @Test
    fun growsAndCaps() {
        val b = Backoff(random = Random(1))
        val values = (1..10).map { b.next() }
        assertTrue(values[0] in 1_000..1_250, "first=${values[0]}")
        assertTrue(values[1] in 2_000..2_500)
        assertTrue(values[2] in 4_000..5_000)
        assertTrue(values.all { it <= 30_000 })
        assertEquals(30_000, values.last())
    }

    @Test
    fun resetStartsOver() {
        val b = Backoff(random = Random(2))
        repeat(5) { b.next() }
        b.reset()
        assertTrue(b.next() in 1_000..1_250)
    }

    @Test
    fun jitterVaries() {
        val b1 = Backoff(random = Random(3)).next()
        val b2 = Backoff(random = Random(4)).next()
        assertTrue(b1 != b2 || b1 in 1_000..1_250)
    }
}

class ProtocolTest {
    private val config = BeezyncConfig("11111111-1111-1111-1111-111111111111", "pk_abc", "https://api.example.com/")

    @Test
    fun wsUrlUsesSecureScheme() {
        assertEquals("wss://api.example.com/v1/ws?app_id=11111111-1111-1111-1111-111111111111&key=pk_abc", Protocol.wsUrl(config))
        assertTrue(Protocol.wsUrl(config.copy(baseUrl = "http://127.0.0.1:8099")).startsWith("ws://127.0.0.1:8099/v1/ws"))
    }

    @Test
    fun clientFrames() {
        assertEquals("""{"action":"subscribe","channel":"x","auth":"ab"}""", Protocol.subscribeFrame("x", "ab", null))
        assertEquals("""{"action":"subscribe","channel":"x","token":"t"}""", Protocol.subscribeFrame("x", "ab", "t"))
        assertEquals("""{"action":"subscribe","channel":"x"}""", Protocol.subscribeFrame("x", null, null))
        assertEquals("""{"action":"unsubscribe","channel":"x"}""", Protocol.unsubscribeFrame("x"))
    }

    @Test
    fun parsesServerFrames() {
        assertEquals(ServerFrame.Established("s1"), Protocol.parse("""{"event":"connection_established","data":{"socket_id":"s1"}}"""))
        assertEquals(ServerFrame.Established("s2"), Protocol.parse("""{"event":"connection_established","data":"{\"socket_id\":\"s2\"}"}"""))
        assertEquals(ServerFrame.Subscribed("c"), Protocol.parse("""{"event":"subscription_succeeded","channel":"c"}"""))
        assertEquals(ServerFrame.SubscribeFailed("c", "nope"), Protocol.parse("""{"event":"subscription_error","channel":"c","error":"nope"}"""))
        assertEquals(ServerFrame.Pong, Protocol.parse("""{"event":"pong"}"""))
        assertEquals(ServerFrame.Unknown, Protocol.parse("not json"))
    }

    @Test
    fun parsesPublishedAndNotification() {
        val pub = assertIs<ServerFrame.Message>(Protocol.parse("""{"event":"order.created","channel":"orders","data":{"n":1},"id":"i1","ts":1700000000}""")).message
        assertEquals(MessageKind.PUBLISHED, pub.kind)
        assertEquals("order.created", pub.event)
        assertEquals("orders", pub.channel)
        assertEquals(1700000000L, pub.timestamp)
        assertEquals("""{"n":1}""", pub.data.toString())

        val note = assertIs<ServerFrame.Message>(Protocol.parse("""{"id":"n1","title":"T","body":"B","data":{"bz_id":"n1"}}""")).message
        assertEquals(MessageKind.NOTIFICATION, note.kind)
        assertEquals("T", note.title)
        assertEquals("n1", note.id)
    }

    @Test
    fun notificationIdFromFcmData() {
        assertEquals("abc", BeezyncPush.notificationId(mapOf("bz_id" to "abc", "x" to "y")))
        assertEquals(null, BeezyncPush.notificationId(mapOf("x" to "y")))
        assertEquals(null, BeezyncPush.notificationId(mapOf("bz_id" to " ")))
        assertEquals(null, BeezyncPush.notificationId(null))
    }
}
