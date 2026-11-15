package com.z1pendant.link.makera

import kotlinx.coroutines.sync.withLock

import com.z1pendant.link.ConnState
import com.z1pendant.link.Dialect
import com.z1pendant.link.GrblishDialect
import com.z1pendant.link.MachineLink
import com.z1pendant.link.MachineStatus
import com.z1pendant.link.MachineTarget
import com.z1pendant.link.StatusParser
import com.z1pendant.platform.PLog
import com.z1pendant.platform.nowMs
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Stock Makera Z1 over Wi-Fi: the ESP32 bridges TCP 2222 straight to the LPC's UART.
 *
 * Flow control: up to two lines are out at a time, and each waits for its "ok"/"error" reply, however
 * long that takes (`$H` answers once homing is done). Replies come back in order, so the first one
 * belongs to the oldest line out. A status query goes out every [pollMs]; no reply for [lossMs] = link lost, and
 * the link reconnects by itself.
 *
 * Sockets are ktor-network (the same code on Android and iOS).
 */
class MakeraLink(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) : MachineLink {
    override val conn = MutableStateFlow(ConnState.Disconnected)
    override val status = MutableStateFlow<MachineStatus?>(null)
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val latencyMs = MutableStateFlow<Int?>(null)
    override val outputs = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    @Volatile private var lastDiagAt = 0L
    override val dialect: Dialect = GrblishDialect
    val firmware = MutableStateFlow<String?>(null)
    override val jobFile = MutableStateFlow<com.z1pendant.link.JobFile?>(null)

    // ---- file download (the Wi-Fi board's protocol; reference/download_protocol_notes.md) ----
    // Stop-and-wait: one request, wait for its reply (the board keeps only one request). One
    // download at a time across all clients; Studio can't upload while we hold it, so it's kept short.
    private val dlLock = kotlinx.coroutines.sync.Mutex()
    @kotlin.concurrent.Volatile private var dlReply: kotlinx.coroutines.CompletableDeferred<Pair<Int, ByteArray>>? = null

    private fun onDownloadPacket(type: Int, payload: ByteArray): Boolean {
        val d = dlReply ?: return false
        if (type !in MakeraFrame.DL_START..0xB6 && type != MakeraFrame.REFUSED) return false
        d.complete(type to payload)
        return true
    }

    private suspend fun dlRequest(type: Int, payload: ByteArray = ByteArray(0)): Pair<Int, ByteArray>? {
        repeat(5) {
            val d = kotlinx.coroutines.CompletableDeferred<Pair<Int, ByteArray>>()
            dlReply = d
            sendRaw(MakeraFrame.build(type, payload))
            val r = kotlinx.coroutines.withTimeoutOrNull(1500) { d.await() }
            if (r != null) return r
        }
        return null
    }

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u32(b: ByteArray, at: Int = 0): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    override suspend fun downloadFile(path: String): String? = dlLock.withLock {
        if (conn.value != ConnState.Connected) return null
        var owner = false
        try {
            val start = dlRequest(MakeraFrame.DL_START, "download ${path.replace(' ', '\u0001')}\n".encodeToByteArray())
            if (start?.first != MakeraFrame.DL_MD5) {
                com.z1pendant.platform.PLog.w("MakeraLink", "download $path refused: ${start?.second?.decodeToString()}"); return null
            }
            owner = true
            val size = dlRequest(MakeraFrame.DL_SIZE) ?: return null
            if (size.first != MakeraFrame.DL_SIZE || size.second.size < 4) return null
            val count = u32(size.second)
            if (count > 1024) { com.z1pendant.platform.PLog.w("MakeraLink", "download $path: $count packets, too big"); return null }   // > 8 MB
            val parts = ArrayList<ByteArray>()
            for (seq in 1..count) {
                val r = dlRequest(MakeraFrame.DL_DATA, u32(seq)) ?: return null
                if (r.first != MakeraFrame.DL_DATA || r.second.size < 4 || u32(r.second) != seq) return null
                parts += r.second.copyOfRange(4, r.second.size)
                kotlinx.coroutines.delay(25)              // paced: the board is also streaming the job
            }
            dlRequest(MakeraFrame.DL_END)
            owner = false
            val bytes = ByteArray(parts.sumOf { it.size }).also { b -> var o = 0; for (x in parts) { x.copyInto(b, o); o += x.size } }
            // A QuickLZ copy (from /sd/gcodes/.lz) starts with 00 00: not handled yet.
            if (bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte()) {
                com.z1pendant.platform.PLog.w("MakeraLink", "download $path came compressed; skipped"); return null
            }
            bytes.decodeToString()
        } finally {
            if (owner) dlRequest(MakeraFrame.DL_CANCEL)
            dlReply = null
        }
    }
    override fun queryJobFile() {
        if (conn.value != ConnState.Connected) return
        scope.launch { sendRaw(MakeraFrame.build(MakeraFrame.JOB_FILE, ByteArray(0))) }
    }

    private class Pending(val line: String, val quiet: Boolean, val onAck: ((Boolean, String) -> Unit)?, var sentAt: Long = 0) {
        var slowLogged = false
        /** Cancelled by a reset while out: already reported as failed; until this time it still takes its late reply. */
        var ghostUntil = 0L
    }

    private val lock = SynchronizedObject()
    /** One frame at a time on the socket. */
    private val writeLock = Mutex()
    private val selector by lazy { SelectorManager(Dispatchers.IO) }
    private val queue = ArrayDeque<Pending>()
    private val inFlight = ArrayDeque<Pending>()
    @Volatile private var out: ByteWriteChannel? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var target: MachineTarget? = null
    @Volatile private var pollMs = 200L
    @Volatile private var queryAt = 0L
    @Volatile private var lastStatusAt = 0L
    private var job: Job? = null
    /** null = not known yet, true = machine replies "ok" per line, false = it doesn't. */
    @Volatile var acksSeen: Boolean? = null; private set

    private val window = 2
    /** A line out this long without a reply is logged (once); it keeps waiting. */
    private val slowAckMs = 4000L
    /** How long a line cancelled by a reset may still get its reply (on the Z1, `$H`'s came 80 ms after). */
    private val ghostMs = 1000L
    private val lossMs = 1500L

    override val pendingLines: Int get() = synchronized(lock) { queue.size + inFlight.size }

    override fun setPollIntervalMs(ms: Long) { pollMs = ms.coerceIn(40, 2000) }

    /**
     * The "diagnose" line is answered with a 0x82 frame and no "ok" (the firmware intercepts it
     * before the command queue), so it's sent outside the line queue. Confirmed on the Z1:
     * `{S:…|L:…|V:…|F:…|G:light,…|T:…|C:…|E:…|P:…|I:…|RSSI:…}`; G[0] follows M821/M822.
     */
    override fun refreshOutputs() {
        if (conn.value != ConnState.Connected) return
        lastDiagAt = nowMs()
        scope.launch { sendRaw(MakeraFrame.line("diagnose")) }
    }

    override fun assumeOutput(name: String, on: Boolean) { outputs.value = outputs.value + (name to on) }

    private fun parseDiag(text: String) {
        val body = text.substringAfter('{').substringBefore('}')
        val f = body.split('|').associate { it.substringBefore(':') to it.substringAfter(':').split(',') }
        val m = outputs.value.toMutableMap()
        // G = light, beep, extendin, extendout state, extendout pwm (community SimpleShell::diagnose_command)
        f["G"]?.getOrNull(0)?.trim()?.let { m["light"] = it == "1" }
        f["G"]?.getOrNull(3)?.trim()?.let { m["vacuum"] = it == "1" }
        outputs.value = m
    }

    override fun connect(target: MachineTarget) {
        disconnect()
        firmware.value = null              // belongs to the previous machine
        jobFile.value = null               // asked again for whatever job is running now
        this.target = target
        job = scope.launch { runLoop(target) }
    }

    override fun disconnect() {
        job?.cancel(); job = null
        closeSocket()
        target = null
        failAll("disconnected")
        conn.value = ConnState.Disconnected
        status.value = null
        latencyMs.value = null
    }

    private suspend fun runLoop(t: MachineTarget) {
        var first = true
        while (scope.isActive && target == t) {
            conn.value = if (first) ConnState.Connecting else ConnState.Lost
            first = false
            try {
                val s = withTimeout(3000) { aSocket(selector).tcp().connect(t.host, t.port) { noDelay = true } }
                socket = s; out = s.openWriteChannel(autoFlush = false)
                lastStatusAt = nowMs()
                queryAt = 0
                val reader = scope.launch { readLoop(s) }
                sendRaw(MakeraFrame.rt('?'.code)); queryAt = nowMs()
                // Wait for the first status before calling it connected.
                val t0 = nowMs()
                while (status.value == null || status.value!!.atMs < t0) {
                    if (nowMs() - t0 > 2500) throw LinkException("no status reply")
                    delay(20)
                }
                outputs.value = emptyMap()           // light state etc. belong to the machine we just reached
                conn.value = ConnState.Connected
                pumpLoop()
                reader.cancel()
            } catch (e: Exception) {
                PLog.w(TAG, "link: ${e.message}")
            }
            // Disconnected (this job cancelled): disconnect() cleans up and sets the state.
            currentCoroutineContext().ensureActive()
            closeSocket()
            failAll("link lost")
            if (target != t) break
            conn.value = ConnState.Lost
            latencyMs.value = null
            delay(1500)
        }
    }

    /** Runs while connected: status polls, line sending, ack timeouts, loss detection. */
    private suspend fun pumpLoop() {
        while (scope.isActive && socket?.isClosed == false) {
            val now = nowMs()
            // Output states every 3 s, but not while jogging (fast status polls mean a jog is on).
            if (now - lastDiagAt > 3000 && pollMs >= 200) refreshOutputs()
            if (now - lastStatusAt > lossMs) throw LinkException("no status for ${now - lastStatusAt} ms")
            if (queryAt == 0L && now - lastStatusAt >= pollMs || queryAt != 0L && now - queryAt > 600) {
                queryAt = now
                sendRaw(MakeraFrame.rt('?'.code))
            }
            var toSend: Pending? = null
            synchronized(lock) {
                // No timeout on replies: the Z1 (fw 1.1.3.0.1.13) answers every G-code / $ line, but
                // some only when done ($H after homing, ~4 s even from the home position). Giving up
                // on one would hand its late "ok" to the next line.
                for (p in inFlight) if (!p.slowLogged && p.ghostUntil == 0L && now - p.sentAt > slowAckMs) {
                    p.slowLogged = true
                    PLog.w(TAG, "no reply for '${p.line}' after $slowAckMs ms, still waiting")
                }
                // Lines cancelled by a reset whose reply never came: stop waiting for it.
                while (inFlight.firstOrNull()?.let { it.ghostUntil in 1..now } == true) inFlight.removeFirst()
                val ghosts = inFlight.any { it.ghostUntil != 0L }
                // Nothing new goes out while a reset's late replies may still come, or one would be taken as its.
                if (!ghosts && queue.isNotEmpty() && inFlight.size < window) {
                    val p = queue.removeFirst()
                    p.sentAt = now
                    // Shell commands (version, ls, …) reply with text and no "ok": don't wait for one,
                    // or the next line's "ok" would be matched to the wrong command.
                    if (expectsOk(p.line)) inFlight.addLast(p) else p.onAck?.invoke(true, "")
                    toSend = p
                }
            }
            toSend?.let {
                PLog.d(TAG, "-> ${it.line}")
                sendRaw(MakeraFrame.line(it.line))
                if (!it.quiet) sent.tryEmit(it.line)
            }
            delay(if (toSend != null) 1 else 5)
        }
    }

    private suspend fun readLoop(s: Socket) {
        val dec = MakeraFrame.Decoder()
        val buf = ByteArray(4096)
        try {
            val ins = s.openReadChannel()         // throws if the socket was already replaced/closed
            while (true) {
                val n = ins.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                dec.feed(buf, n) { type, payload -> if (!onDownloadPacket(type, payload)) onPacket(type, payload.decodeToString()) }
            }
        } catch (_: Exception) {
        }
        if (socket === s) closeSocket()
    }

    private fun onPacket(type: Int, text: String) {
        val now = nowMs()
        when (type) {
            MakeraFrame.STATUS_RES -> {
                lastStatusAt = now
                if (queryAt != 0L) { latencyMs.value = (now - queryAt).toInt(); queryAt = 0 }
                StatusParser.parse(text, now)?.let { status.value = it }
            }
            MakeraFrame.FIRM_VER -> firmware.value = text.trim()
            MakeraFrame.DIAG_RES -> parseDiag(text)
            // "path|md5" of the playing file, or "|" when none.
            MakeraFrame.JOB_FILE -> {
                val path = text.substringBefore('|').trim(); val md5 = text.substringAfter('|', "").trim()
                jobFile.value = if (path.isEmpty()) null else com.z1pendant.link.JobFile(path, md5)
            }
            else -> {
                for (raw in text.split('\n')) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    val isOk = line.startsWith("ok", true)
                    val isErr = line.startsWith("error", true)
                    if (isOk || isErr) {
                        acksSeen = true
                        val p = synchronized(lock) { inFlight.removeFirstOrNull() }
                        if (p != null && p.ghostUntil == 0L) p.onAck?.invoke(isOk, line)
                        if (isOk && p?.quiet == true) continue      // a jog move's "ok"
                    }
                    messages.tryEmit(line)
                }
            }
        }
    }

    override fun sendLine(line: String, quiet: Boolean, onAck: ((ok: Boolean, reply: String) -> Unit)?) {
        if (conn.value != ConnState.Connected) {
            if (!quiet) messages.tryEmit("not sent (not connected): $line")
            onAck?.invoke(false, "not connected"); return
        }
        synchronized(lock) { queue.addLast(Pending(line, quiet, onAck)) }
    }

    override fun feedHold() { rt('!'.code); sent.tryEmit("! (feed hold)") }
    override fun resume() { rt('~'.code); sent.tryEmit("~ (resume)") }
    /**
     * Lines waiting to go out and lines out without a reply all fail ("reset"). The lines out stay
     * in line for up to [ghostMs]: on the Z1 a `$H` cut short by a reset still sends its "ok"
     * afterwards, and that must not be taken as the next line's.
     */
    override fun reset() {
        val until = nowMs() + ghostMs
        val cancelled = synchronized(lock) {
            val out = inFlight.filter { it.ghostUntil == 0L }
            out.forEach { it.ghostUntil = until }
            (out + queue).also { queue.clear() }
        }
        rt(0x18); sent.tryEmit("Ctrl-X (reset)")
        cancelled.forEach { it.onAck?.invoke(false, "reset") }
    }

    private fun rt(c: Int) { scope.launch { sendRaw(MakeraFrame.rt(c)) } }

    private suspend fun sendRaw(frame: ByteArray) {
        try {
            writeLock.withLock { out?.let { o -> o.writeFully(frame); o.flush() } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PLog.w(TAG, "send: ${e.message}")
            closeSocket()
        }
    }

    private fun failAll(why: String) {
        val dropped = synchronized(lock) { (inFlight + queue).also { inFlight.clear(); queue.clear() } }
        dropped.forEach { it.onAck?.invoke(false, why) }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
    }

    private class LinkException(msg: String) : Exception(msg)

    companion object {
        const val TAG = "MakeraLink"
        private val GCODE = Regex("""^\s*(\$|[GMTFSXYZABN]\s*[-+.\d])""", RegexOption.IGNORE_CASE)
        fun expectsOk(line: String) = GCODE.containsMatchIn(line)
    }
}
