package com.z1pendant

import android.os.SystemClock
import com.z1pendant.jog.JogEngine
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.makera.MakeraLink
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The ktor-network link against the machine simulator (python/z1sim.py), on the host JVM:
 * connect, status polling, a line with its "ok", disconnect. Skipped when Python isn't available.
 */
class MakeraLinkSimTest {
    private val port = 22229
    private var sim: Process? = null

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        unmockkStatic(SystemClock::class)
    }

    /**
     * Jog engine + link + sim end to end: ten 0.1 mm clicks move X by 1 mm, and the machine is not
     * left in relative mode: a plain absolute G0 afterwards lands where it says.
     */
    @Test fun jogLeavesNoRelativeModeBehind() = runBlocking {
        val link = MakeraLink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jog = JogEngine(link, scope)
        jog.axis = 'X'; jog.step = 0.1
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        val x0 = link.status.value!!.mpos[0]
        jog.start()
        repeat(10) { jog.click(1); delay(30) }
        withTimeout(10000) {
            while (link.status.value?.let { it.isIdle && kotlin.math.abs(it.mpos[0] - (x0 + 1.0)) < 1e-6 } != true) delay(20)
        }
        delay(600)                                   // the engine's quiet time after a jog
        jog.stop()

        val ack = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G0 X-90") { ok, reply -> ack.complete(ok to reply) }
        assertTrue("ok for G0 X-90", withTimeout(3000) { ack.await() }.first)
        withTimeout(10000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -90.0 } != true) delay(20) }
        link.disconnect(); scope.cancel()
    }

    @Test fun connectPollSendDisconnect() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        val st = link.status.value!!
        assertTrue(st.raw, st.mpos.size >= 4)
        val first = st.atMs
        withTimeout(3000) { while (link.status.value!!.atMs == first) delay(20) }        // polled again
        assertTrue("latency measured", link.latencyMs.value != null)

        val ack = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G91 G0 X-1") { ok, reply -> ack.complete(ok to reply) }
        val (ok, reply) = withTimeout(3000) { ack.await() }
        assertTrue("ok for the move: $reply", ok)
        link.sendLine("G90")
        withTimeout(3000) { while (link.pendingLines > 0) delay(10) }

        link.disconnect()
        assertEquals(ConnState.Disconnected, link.conn.value)
        delay(300)
        assertEquals("stays disconnected", ConnState.Disconnected, link.conn.value)
    }

    /**
     * Homing from far away takes more than 4 s, and the Z1 answers \$H only when it's done. The "ok"
     * belongs to \$H (not reported early), and the line after it gets its own reply.
     */
    @Test fun slowHomingAnswerGoesToHoming() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        link.sendLine("G0 X-200")
        withTimeout(10000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -200.0 } != true) delay(20) }

        var idleAt = 0L
        val homed = CompletableDeferred<Pair<Boolean, Long>>()
        val next = CompletableDeferred<Pair<Boolean, String>>()
        val t0 = System.currentTimeMillis()
        link.sendLine("\$H") { ok, _ -> homed.complete(ok to System.currentTimeMillis()) }
        link.sendLine("G0 X-150") { ok, reply -> next.complete(ok to reply) }
        withTimeout(5000) { while (link.status.value?.state != "Home") delay(10) }
        withTimeout(15000) { while (link.status.value?.isIdle != true) delay(10) }
        idleAt = System.currentTimeMillis()
        val (ok, at) = withTimeout(3000) { homed.await() }
        assertTrue("homing took over 4 s (${idleAt - t0} ms)", idleAt - t0 > 4500)
        assertTrue("\$H ok", ok)
        assertTrue("\$H answered when homing ended, not before (${idleAt - at} ms early)", at > idleAt - 400)
        val (ok2, reply2) = withTimeout(3000) { next.await() }
        assertTrue("next line's own ok: $reply2", ok2)
        withTimeout(10000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -150.0 } != true) delay(20) }
        link.disconnect()
    }

    /**
     * Reset during homing: \$H fails ("reset") right away, but the Z1 still sends its "ok" after the
     * alarm. That late "ok" must not be taken as the next line's reply: in Alarm, the next line gets
     * "error:Alarm lock".
     */
    @Test fun resetSwallowsTheLateReplyOfALineCut() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        val homed = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("\$H") { ok, reply -> homed.complete(ok to reply) }
        withTimeout(5000) { while (link.status.value?.state != "Home") delay(10) }
        link.reset()
        assertEquals(false to "reset", withTimeout(1000) { homed.await() })

        val next = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G0 X-50") { ok, reply -> next.complete(ok to reply) }
        val (ok, reply) = withTimeout(5000) { next.await() }
        assertTrue("the next line got its own reply, not \$H's late ok: $ok $reply", !ok && reply.contains("Alarm lock"))
        withTimeout(2000) { while (link.status.value?.isAlarm != true) delay(10) }

        val unlock = CompletableDeferred<Boolean>()
        link.sendLine("\$X") { ok3, _ -> unlock.complete(ok3) }
        assertTrue("\$X ok", withTimeout(3000) { unlock.await() })
        withTimeout(3000) { while (link.pendingLines > 0) delay(10) }
        link.disconnect()
    }

    /** \$H ends at the soft max (-1), like the real Z1, so the first move after homing doesn't alarm. */
    @Test fun moveAfterHomingStaysInsideSoftLimits() = runBlocking {
        val link = MakeraLink()
        link.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        link.sendLine("\$H")
        withTimeout(15000) {
            while (link.status.value?.let { it.isIdle && it.mpos.take(3).all { m -> m == -1.0 } } != true) delay(50)
        }
        val ack = CompletableDeferred<Pair<Boolean, String>>()
        link.sendLine("G91 G0 X-1") { ok, reply -> ack.complete(ok to reply) }
        val (ok, reply) = withTimeout(3000) { ack.await() }
        assertTrue("ok for the first move after homing: $reply", ok)
        link.sendLine("G90")
        // The firmware (and the sim) waits 100 ms before running a queue that was empty: wait for the position.
        withTimeout(5000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -2.0 } != true) delay(20) }
        assertEquals(link.status.value!!.raw, "Idle", link.status.value!!.state)
        link.disconnect()
    }
}
