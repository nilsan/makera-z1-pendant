package com.z1pendant

import android.os.SystemClock
import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineTarget
import com.z1pendant.Haptics
import com.z1pendant.platform.Platform
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.PrefsEditor
import com.z1pendant.platform.TextFile
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A job on the machine simulator (python/z1sim.py, `play`), through the real view model and link:
 * |P: makes a job, 0xB7 names the file, the file downloads and its tools are read, and when the
 * job ends the feed / spindle overrides go back to 100 %. Skipped when Python isn't available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSimTest {
    private val port = 22231
    private var sim: Process? = null
    private lateinit var simDir: File

    @Before fun startSim() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "python/z1sim.py") }.firstOrNull { it.exists() }
        assumeTrue("z1sim.py not found", script != null)
        // A short job: one move, a tool change, another move.
        simDir = File(System.getProperty("java.io.tmpdir"), "z1sim_sd_${System.nanoTime()}").apply { mkdirs() }
        File(simDir, "t.cnc").writeText("""
            (T1  Flat 3.175  D=3.175 - flat end mill)
            (T2  Ball 1/8  D=3.175 CR=1.5 - ball end mill)
            G90 G94 G21
            T1M6
            S8000 M3
            G0 X5 Y5
            G1 X15 F1200
            T2M6
            G1 X5 F1200
            M5
            M30
        """.trimIndent() + "\n")
        sim = try {
            ProcessBuilder("python", script!!.path, "--port", "$port", "--no-broadcast", "--sd", simDir.path)
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "z1sim_job_test.log")).start()
        } catch (_: Exception) { null }
        assumeTrue("python not runnable", sim != null)
        val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
        assumeTrue("simulator didn't start", up)
    }

    @After fun stopSim() {
        sim?.destroy(); sim?.waitFor(5, TimeUnit.SECONDS)
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
        simDir.deleteRecursively()
    }

    @Test fun jobFileToolsAndOverrideReset() = runBlocking {
        val vm = AppViewModel(FakePlatform())
        val link = vm.link
        vm.connect(MachineTarget("sim", "127.0.0.1", port))
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        // Home, set a work zero mid-table, leave overrides off 100 %, then play.
        for (l in listOf("\$H", "G53 G0 X-100 Y-100 Z-60", "G10L20P0X0Y0Z0", "M220 S120", "M3 S6000", "M223 S85", "M5")) link.sendLine(l)
        // \$H is answered once homing is done (like the Z1), and the lines after it wait for that.
        withTimeout(15_000) { while (link.pendingLines > 0) delay(50) }
        withTimeout(15_000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -100.0 } != true) delay(50) }
        delay(500)
        assertEquals(85.0, link.status.value!!.spindleOverride!!, 0.01)
        link.sendLine("play t.cnc")

        withTimeout(10_000) { while (vm.job.value == null) delay(20) }
        withTimeout(10_000) { while (vm.job.value?.file == null) delay(20) }
        assertEquals("t.cnc", vm.job.value!!.file!!.name)
        withTimeout(15_000) { while (vm.jobTools.value == null) delay(20) }
        val tools = vm.jobTools.value!!
        assertEquals(listOf(1, 2), tools.changes.map { it.tool })
        assertEquals("Flat 3.175 · flat end mill", tools.describe(1))
        assertNotNull(tools.describe(2))

        // Job ends → both overrides back to 100 %.
        withTimeout(40_000) { while (vm.job.value != null) delay(50) }
        withTimeout(5_000) {
            while (link.status.value?.let { it.feedOverride == 100.0 && it.spindleOverride == 100.0 } != true) delay(50)
        }
        link.disconnect()
    }

    /** The pendant leaves mid-job; the job ends while it's away; back on an idle machine → overrides reset. */
    @Test fun jobEndedWhileAway() = runBlocking {
        val vm = AppViewModel(FakePlatform())
        val link = vm.link
        val target = MachineTarget("sim", "127.0.0.1", port)
        vm.connect(target)
        withTimeout(5000) { while (link.conn.value != ConnState.Connected) delay(20) }
        for (l in listOf("\$H", "G53 G0 X-100 Y-100 Z-60", "G10L20P0X0Y0Z0", "M3 S6000", "M223 S85", "M5")) link.sendLine(l)
        // \$H is answered once homing is done (like the Z1), and the lines after it wait for that.
        withTimeout(15_000) { while (link.pendingLines > 0) delay(50) }
        withTimeout(15_000) { while (link.status.value?.let { it.isIdle && it.mpos[0] == -100.0 } != true) delay(50) }
        link.sendLine("play t.cnc")
        withTimeout(10_000) { while (vm.job.value == null) delay(20) }
        vm.disconnect()
        assertEquals(null, vm.job.value)                            // forgotten while away
        // Let the job finish without us (another client watches the sim).
        val spy = com.z1pendant.link.makera.MakeraLink()
        spy.connect(target)
        withTimeout(5000) { while (spy.conn.value != ConnState.Connected) delay(20) }
        withTimeout(40_000) { while (spy.status.value?.job != null || spy.status.value == null) delay(50) }
        assertEquals(85.0, spy.status.value!!.spindleOverride!!, 0.01)  // nobody reset it yet
        vm.connect(target)
        withTimeout(10_000) { while (spy.status.value?.spindleOverride != 100.0) delay(50) }
        spy.disconnect(); link.disconnect()
    }
}

private class FakePlatform : Platform {
    private val map = HashMap<String, Any?>()
    override val prefs = object : Prefs {
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun getString(key: String, default: String?) = map[key] as? String ?: default
        override fun edit(block: PrefsEditor.() -> Unit) {
            object : PrefsEditor {
                override fun putBoolean(key: String, value: Boolean) { map[key] = value }
                override fun putInt(key: String, value: Int) { map[key] = value }
                override fun putLong(key: String, value: Long) { map[key] = value }
                override fun putFloat(key: String, value: Float) { map[key] = value }
                override fun putString(key: String, value: String) { map[key] = value }
            }.block()
        }
    }
    override val hotkeyFile = object : TextFile {
        var text: String? = null
        override fun exists() = text != null
        override fun readText() = text ?: ""
        override fun writeText(text: String) { this.text = text }
    }
    override val haptics = object : Haptics {
        override var strength = 0f
        override val description = "none"
        override fun tick() {}
        override fun majorTick() {}
        override fun thud() {}
        override fun limit() {}
        override fun press() {}
    }
}
