package com.tideclock.messmachine

import com.tideclock.messmachine.cloud.Batch
import com.tideclock.messmachine.cloud.Clock
import com.tideclock.messmachine.cloud.Presence
import com.tideclock.messmachine.cloud.RealtimeListener
import com.tideclock.messmachine.engine.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accounts, Slice C: the live socket's protocol (Realtime.kt) against a scripted socket, frame by frame, as
 * docs/ACCOUNTS-API.md s2 writes it. No Android and no network: the real websocket (OkHttp, in SupabaseApi) only
 * carries these strings.
 */
class RealtimeTest {
    private var now = 1_000_000L
    private val sent = ArrayList<Map<String, Any?>>()
    private var events: RtEvents? = null
    private var connects = 0
    private var closed = 0
    private var token = "tok-1"
    private val heard = ArrayList<String>()
    private var lastBatch: Batch? = null
    private var peers: List<Presence> = emptyList()

    private val listener = object : RealtimeListener {
        override fun onJoin(topic: String, ok: Boolean, reason: String) { heard.add("join $topic $ok $reason") }
        override fun onOps(topic: String, b: Batch) { heard.add("ops $topic ${b.rev}"); lastBatch = b }
        override fun onMeta(topic: String, kind: String, gone: String, sha256: String) { heard.add("meta $kind $gone $sha256") }
        override fun onPresence(topic: String, peers: List<Presence>) { heard.add("presence ${peers.size}"); this@RealtimeTest.peers = peers }
        override fun onDrop(topic: String) { heard.add("drop $topic") }
    }

    private fun client() = RealtimeClient("wss://x.test/realtime/v1/websocket?apikey=k&vsn=1.0.0", RtConnector { _, ev ->
        connects++
        events = ev
        object : RtSocket {
            override fun send(text: String) { sent.add(Json.map(Json.parse(text))) }
            override fun close() { closed++ }
        }
    }, Clock { now }, { token }, { "user-1" })

    private fun server(topic: String, event: String, payload: String, ref: String = "null") {
        events!!.onText("{\"topic\":\"realtime:$topic\",\"event\":\"$event\",\"payload\":$payload,\"ref\":$ref,\"join_ref\":null}")
    }
    private fun last() = sent[sent.size - 1]
    private fun joined(c: RealtimeClient, topic: String) {
        c.join(topic, listener)
        events!!.onOpen()
        server(topic, "phx_reply", "{\"status\":\"ok\",\"response\":{}}", "\"" + Json.str(last()["ref"], "") + "\"")
    }

    @Test fun realtimeJoinCarriesTheTokenAndMyPresenceKey() {
        val c = client()
        c.join("mess:m1:00aa", listener)
        assertEquals("one socket", 1, connects)
        assertEquals("nothing is sent before the socket is open", 0, sent.size)
        events!!.onOpen()
        val j = last()
        assertEquals("realtime:mess:m1:00aa", j["topic"])
        assertEquals("phx_join", j["event"])
        assertEquals(j["ref"], j["join_ref"])
        val p = Json.map(j["payload"])
        assertEquals("tok-1", p["access_token"])
        val cfg = Json.map(p["config"])
        assertEquals(true, cfg["private"])
        assertEquals("user-1", Json.map(cfg["presence"])["key"])
        assertEquals(true, Json.map(cfg["presence"])["enabled"])
        assertEquals(false, Json.map(cfg["broadcast"])["self"])
        assertEquals(false, Json.map(cfg["broadcast"])["ack"])
        assertTrue("no answer yet, no join yet", heard.isEmpty())
        server("mess:m1:00aa", "phx_reply", "{\"status\":\"ok\",\"response\":{}}", "\"" + Json.str(j["ref"], "") + "\"")
        assertEquals(listOf("join mess:m1:00aa true "), heard)
    }

    @Test fun realtimeARefusedJoinSaysWhy() {
        val c = client()
        c.join("mess:m1:old", listener)
        events!!.onOpen()
        server("mess:m1:old", "phx_reply", "{\"status\":\"error\",\"response\":{\"reason\":\"Unauthorized\"}}", "\"" + Json.str(last()["ref"], "") + "\"")
        assertEquals(listOf("join mess:m1:old false Unauthorized"), heard)
        assertEquals("the channel is forgotten", 0, c.channels())
        assertEquals("and the socket closed, since nothing else uses it", 1, closed)
    }

    @Test fun realtimeOpsAndMetaBroadcasts() {
        val c = client()
        joined(c, "t1")
        heard.clear()
        server("t1", "broadcast", "{\"type\":\"broadcast\",\"event\":\"ops\",\"payload\":{\"rev\":7,\"user\":\"u2\",\"batch\":\"b1\",\"v\":1,\"ops\":[{\"t\":\"step.set\"}],\"at\":\"x\"}}")
        assertEquals("ops t1 7", heard[0])
        assertEquals(1, lastBatch!!.ops!!.size)
        // over 32 KB the server leaves the ops out: the phone fetches them with ops_since
        server("t1", "broadcast", "{\"type\":\"broadcast\",\"event\":\"ops\",\"payload\":{\"rev\":8,\"user\":\"u2\",\"batch\":\"b2\",\"v\":1,\"ops\":null,\"at\":\"x\"}}")
        assertEquals("ops t1 8", heard[1])
        assertTrue(lastBatch!!.ops == null)
        server("t1", "broadcast", "{\"type\":\"broadcast\",\"event\":\"meta\",\"payload\":{\"kind\":\"members\",\"gone\":\"u9\"}}")
        server("t1", "broadcast", "{\"type\":\"broadcast\",\"event\":\"meta\",\"payload\":{\"kind\":\"sound\",\"sha256\":\"abc\"}}")
        assertEquals("meta members u9 ", heard[2])
        assertEquals("meta sound  abc", heard[3])
        // a frame for a topic nobody joined, and junk, do nothing
        server("other", "broadcast", "{\"type\":\"broadcast\",\"event\":\"ops\",\"payload\":{\"rev\":9}}")
        events!!.onText("not json")
        assertEquals(4, heard.size)
    }

    @Test fun realtimePresenceIsClampedAndFollowsDiffs() {
        val c = client()
        joined(c, "t1")
        server("t1", "presence_state", "{\"u2\":{\"metas\":[{\"phx_ref\":\"a\",\"u\":\"u2\",\"page\":\"a page name far too long\",\"loop\":99,\"pad\":500,\"playing\":true,\"rec\":false}]}}")
        assertEquals(1, peers.size)
        assertEquals("u2", peers[0].u)
        assertEquals(12, peers[0].page.length)
        assertEquals(7, peers[0].loop)
        assertEquals(63, peers[0].pad)
        assertTrue(peers[0].playing)
        server("t1", "presence_diff", "{\"joins\":{\"u3\":{\"metas\":[{\"u\":\"u3\",\"page\":\"edit\",\"loop\":2,\"pad\":-5,\"playing\":false,\"rec\":true}]}},\"leaves\":{}}")
        assertEquals(2, peers.size)
        assertEquals(-1, peers[1].pad)
        assertEquals("edit", peers[1].page)
        server("t1", "presence_diff", "{\"joins\":{},\"leaves\":{\"u2\":{\"metas\":[{\"u\":\"u2\"}]}}}")
        assertEquals(1, peers.size)
        assertEquals("u3", peers[0].u)
    }

    @Test fun realtimeTrackAtMostEveryTwoSecondsAndOnlyWhenItChanged() {
        val c = client()
        joined(c, "t1")
        val before = sent.size
        c.track("t1", Presence("user-1", "Pads", 0, 3, false, false))
        assertEquals(before + 1, sent.size)
        val f = last()
        assertEquals("presence", f["event"])
        val p = Json.map(f["payload"])
        assertEquals("presence", p["type"]); assertEquals("track", p["event"])
        val s = Json.map(p["payload"])
        assertEquals(setOf("u", "page", "loop", "pad", "playing", "rec"), s.keys)
        assertEquals(3.0, Json.num(s["pad"], -9.0), 0.0)
        // again within 2 s: it waits, and only the newest goes
        now += 500
        c.track("t1", Presence("user-1", "Steps", 0, 4, false, false))
        c.track("t1", Presence("user-1", "Steps", 0, 5, true, false))
        assertEquals(before + 1, sent.size)
        now += 1600
        c.tick()
        assertEquals(before + 2, sent.size)
        assertEquals(5.0, Json.num(Json.map(Json.map(last()["payload"])["payload"])["pad"], -9.0), 0.0)
        // the same state again is not sent
        now += 3000
        c.track("t1", Presence("user-1", "Steps", 0, 5, true, false))
        assertEquals(before + 2, sent.size)
    }

    @Test fun realtimeHeartbeatAndARefreshedToken() {
        val c = client()
        joined(c, "t1")
        val before = sent.size
        now += 24000; c.tick()
        assertEquals("no heartbeat before 25 s", before, sent.size)
        now += 1500; c.tick()
        assertEquals("phoenix", last()["topic"])
        assertEquals("heartbeat", last()["event"])
        token = "tok-2"
        now += 1000; c.tick()
        assertEquals("access_token", last()["event"])
        assertEquals("realtime:t1", last()["topic"])
        assertEquals("tok-2", Json.map(last()["payload"])["access_token"])
        val n = sent.size
        now += 1000; c.tick()
        assertEquals("the token is sent once", n, sent.size)
    }

    @Test fun realtimeADroppedSocketDropsEveryChannelAndJoiningAgainReconnects() {
        val c = client()
        joined(c, "t1")
        heard.clear()
        events!!.onClosed()
        assertEquals(listOf("drop t1"), heard)
        assertEquals(0, c.channels())
        c.join("t1", listener)
        assertEquals("a new socket", 2, connects)
        events!!.onOpen()
        assertEquals("phx_join", last()["event"])
        // the channel alone going away (the key turned) is a drop too
        server("t1", "phx_reply", "{\"status\":\"ok\",\"response\":{}}", "\"" + Json.str(last()["ref"], "") + "\"")
        heard.clear()
        server("t1", "phx_error", "{}")
        assertEquals(listOf("drop t1"), heard)
        // leave says so to the server and closes the socket when it was the last channel
        joined(c, "t2")
        val closedBefore = closed
        c.leave("t2")
        assertEquals("phx_leave", last()["event"])
        assertEquals(closedBefore + 1, closed)
    }
}
