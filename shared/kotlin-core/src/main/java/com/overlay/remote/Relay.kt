package com.overlay.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Embedded NCSound Remote relay — the same session model as
 * `ncsound-daw/collab/src/server/relay.ts`, implemented in Kotlin so a phone
 * can host a session with no Node process: NC Studio (or Phone Beat) starts
 * this on a LAN port and the other app joins over Wi-Fi.
 *
 * The wire format is identical, so a desktop DAW running the Node relay (or
 * its future native port) interoperates with this one.
 *
 * Minimal RFC 6455 server: text frames, close, ping/pong, masked client
 * frames, frames up to [RemoteLimits.MAX_FRAME_BYTES].
 */
class RemoteRelay(
    val port: Int = 8787,
    private val bindAddress: String = "0.0.0.0",
    private val onLog: (String) -> Unit = {},
) {
    private class Session(
        val id: String,
        val hostToken: String,
    ) {
        @Volatile var code: String = ""
        @Volatile var codeExpiresAt: Long = 0L
        @Volatile var host: Connection? = null
        val guests = ConcurrentHashMap<String, Guest>()
        val pending = ConcurrentHashMap<String, Pending>()
        val grace = ConcurrentHashMap<String, Guest>() // disconnected seats, resumable
        val graceAt = ConcurrentHashMap<String, Long>()
    }

    private class Guest(
        val id: String,
        val name: String,
        val role: Role,
        val token: String,
    ) {
        @Volatile var conn: Connection? = null
    }

    private class Pending(val conn: Connection, val name: String)

    private inner class Connection(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        @Volatile var hostSession: Session? = null
        @Volatile var guestSession: Session? = null
        @Volatile var guestId: String? = null
        @Volatile var closed = false

        @Synchronized
        fun send(frame: ServerFrame) {
            if (closed) return
            val text = RemoteCodec.encodeServer(frame)
            if (text.length > RemoteLimits.MAX_FRAME_BYTES) return
            try {
                writeFrame(text.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
                close()
            }
        }

        @Synchronized
        fun writeFrame(payload: ByteArray) {
            val header = when {
                payload.size < 126 -> byteArrayOf(0x81.toByte(), payload.size.toByte())
                payload.size < 65536 -> byteArrayOf(
                    0x81.toByte(),
                    126,
                    (payload.size shr 8).toByte(),
                    (payload.size and 0xFF).toByte(),
                )
                else -> byteArrayOf(
                    0x81.toByte(),
                    127,
                    0, 0, 0, 0,
                    (payload.size shr 24).toByte(),
                    (payload.size shr 16).toByte(),
                    (payload.size shr 8).toByte(),
                    (payload.size and 0xFF).toByte(),
                )
            }
            out.write(header)
            out.write(payload)
            out.flush()
        }

        fun close(code: Int = 1000) {
            if (closed) return
            closed = true
            try {
                // Close frame, unmasked, with a 2-byte status code.
                out.write(byteArrayOf(0x88.toByte(), 0x02, (code shr 8).toByte(), (code and 0xFF).toByte()))
                out.flush()
            } catch (_: Exception) {
            }
            try { socket.close() } catch (_: Exception) {}
            detach()
        }

        fun detach() {
            val session = hostSession ?: guestSession ?: return
            if (hostSession != null) {
                if (session.host === this) {
                    session.host = null
                    broadcast(session, ServerFrame.HostOffline)
                }
                hostSession = null
            }
            val gid = guestId ?: return
            val guest = session.guests.remove(gid)
            if (guest != null) {
                guest.conn = null
                session.grace[gid] = guest
                session.graceAt[gid] = System.currentTimeMillis()
                session.host?.send(ServerFrame.GuestLeft(gid))
            }
            session.pending.remove(gid)
            guestId = null
            guestSession = null
        }
    }

    private var server: ServerSocket? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val codes = ConcurrentHashMap<String, String>() // code -> sessionId
    private val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private var sweeper: ScheduledExecutorService? = null
    private val random = java.util.Random()

    val isRunning: Boolean get() = server?.isClosed == false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        server = ss
        workers.execute {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (_: Exception) { break }
                workers.execute { handleConnection(socket) }
            }
        }
        sweeper = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r).apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate({ sweep() }, 5, 5, TimeUnit.SECONDS)
        }
        onLog("relay listening on $bindAddress:$port")
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        sweeper?.shutdownNow()
        sessions.values.forEach { s ->
            s.host?.close(1000)
            s.guests.values.forEach { it.conn?.close(1000) }
        }
        sessions.clear()
        codes.clear()
    }

    // ---- connection / handshake ----

    private fun handleConnection(socket: Socket) {
        val conn = Connection(socket)
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            if (!performHandshake(input, conn)) {
                socket.close()
                return
            }
            readLoop(input, conn)
        } catch (_: Exception) {
        } finally {
            conn.close()
        }
    }

    private fun performHandshake(input: InputStream, conn: Connection): Boolean {
        val header = readHttpHeader(input) ?: return false
        val key = header.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim() ?: return false
        val accept = wsAccept(key)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        conn.out.write(response.toByteArray(Charsets.US_ASCII))
        conn.out.flush()
        return true
    }

    private fun readHttpHeader(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buffer.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return buffer.toString("UTF-8")
                else -> 0
            }
            if (buffer.size() > 16 * 1024) return null
        }
    }

    private fun readLoop(input: InputStream, conn: Connection) {
        val head = ByteArray(2)
        while (!conn.closed) {
            if (!readFully(input, head)) return
            val opcode = head[0].toInt() and 0x0F
            val masked = (head[1].toInt() and 0x80) != 0
            var len = (head[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                val ext = ByteArray(2)
                if (!readFully(input, ext)) return
                len = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val ext = ByteArray(8)
                if (!readFully(input, ext)) return
                len = 0
                for (b in ext) len = (len shl 8) or (b.toLong() and 0xFF)
            }
            if (len > RemoteLimits.MAX_FRAME_BYTES) {
                conn.close(1009)
                return
            }
            val mask = if (masked) ByteArray(4).also { if (!readFully(input, it)) return } else null
            val payload = ByteArray(len.toInt())
            if (len > 0 && !readFully(input, payload)) return
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                0x1 -> handleText(payload.toString(Charsets.UTF_8), conn)
                0x8 -> { conn.close(1000); return }
                0x9 -> synchronized(conn) {
                    try {
                        conn.out.write(byteArrayOf(0x8A.toByte(), payload.size.toByte()))
                        conn.out.write(payload)
                        conn.out.flush()
                    } catch (_: Exception) {
                    }
                }
                0xA -> Unit
                else -> Unit
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    // ---- frame dispatch ----

    private fun handleText(text: String, conn: Connection) {
        val frame = RemoteCodec.decodeClient(text) ?: return
        when (frame) {
            is ClientFrame.HostCreate -> hostCreate(conn)
            is ClientFrame.HostResume -> hostResume(conn, frame.sessionId, frame.token)
            is ClientFrame.HostApprove -> hostApprove(conn, frame.guestId, frame.role)
            is ClientFrame.HostDeny -> hostDeny(conn, frame.guestId)
            is ClientFrame.HostKick -> hostKick(conn, frame.guestId)
            is ClientFrame.HostNewCode -> hostNewCode(conn)
            is ClientFrame.HostEnd -> hostEnd(conn)
            is ClientFrame.GuestJoin -> guestJoin(conn, frame)
            is ClientFrame.Up -> {
                val session = conn.guestSession ?: return
                val gid = conn.guestId ?: return
                val guest = session.guests[gid] ?: return
                session.host?.send(ServerFrame.From(gid, guest.role, frame.msg))
            }
            is ClientFrame.To -> {
                val session = conn.hostSession ?: return
                if (frame.guestId == null) {
                    broadcast(session, ServerFrame.Msg(frame.msg))
                } else {
                    session.guests[frame.guestId]?.conn?.send(ServerFrame.Msg(frame.msg))
                }
            }
        }
    }

    private fun hostCreate(conn: Connection) {
        val session = Session(UUID.randomUUID().toString(), randomToken(32))
        session.code = newCode(session)
        session.codeExpiresAt = System.currentTimeMillis() + RemoteLimits.CODE_TTL_MS
        session.host = conn
        conn.hostSession = session
        sessions[session.id] = session
        codes[session.code] = session.id
        conn.send(ServerFrame.HostCreated(session.id, session.hostToken, session.code, session.codeExpiresAt))
        onLog("session ${session.id.take(8)} created, code ${RemoteCode.display(session.code)}")
    }

    private fun hostResume(conn: Connection, sessionId: String, token: String) {
        val session = sessions[sessionId]
        if (session == null || session.hostToken != token) {
            conn.send(ServerFrame.ErrorFrame("session_gone"))
            return
        }
        session.host?.close(4002)
        session.host = conn
        conn.hostSession = session
        conn.send(ServerFrame.HostResumed(session.id, session.code, session.codeExpiresAt))
        broadcast(session, ServerFrame.HostOnline)
        session.guests.values.forEach { g ->
            conn.send(ServerFrame.GuestOnline(g.id, g.name, g.role))
        }
        session.pending.forEach { (gid, p) ->
            conn.send(ServerFrame.JoinRequest(gid, p.name))
        }
    }

    private fun hostApprove(conn: Connection, guestId: String, role: Role) {
        val session = conn.hostSession ?: return
        val pending = session.pending.remove(guestId) ?: return
        if (session.guests.size >= 64) {
            pending.conn.send(ServerFrame.ErrorFrame("too_many_pending"))
            return
        }
        val guest = Guest(guestId, pending.name, role, randomToken(24))
        session.grace.remove(guestId)
        session.guests[guestId] = guest
        guest.conn = pending.conn
        pending.conn.guestSession = session
        pending.conn.guestId = guestId
        pending.conn.send(ServerFrame.JoinApproved(session.id, guestId, guest.token, role))
        conn.send(ServerFrame.GuestOnline(guestId, guest.name, role))
        onLog("guest ${pending.name} approved as ${role.wire}")
    }

    private fun hostDeny(conn: Connection, guestId: String) {
        val session = conn.hostSession ?: return
        val pending = session.pending.remove(guestId) ?: return
        pending.conn.send(ServerFrame.JoinDenied)
        sweeper?.schedule({ pending.conn.close(4003) }, 60, TimeUnit.MILLISECONDS)
    }

    private fun hostKick(conn: Connection, guestId: String) {
        val session = conn.hostSession ?: return
        val guest = session.guests.remove(guestId) ?: return
        session.grace.remove(guestId)
        guest.conn?.send(ServerFrame.Kicked)
        guest.conn?.let { c -> sweeper?.schedule({ c.close(4004) }, 60, TimeUnit.MILLISECONDS) }
        conn.send(ServerFrame.GuestLeft(guestId))
    }

    private fun hostNewCode(conn: Connection) {
        val session = conn.hostSession ?: return
        session.code = newCode(session)
        session.codeExpiresAt = System.currentTimeMillis() + RemoteLimits.CODE_TTL_MS
        conn.send(ServerFrame.CodeUpdated(session.code, session.codeExpiresAt))
    }

    private fun hostEnd(conn: Connection) {
        val session = conn.hostSession ?: return
        endSession(session)
    }

    private fun endSession(session: Session) {
        session.host?.send(ServerFrame.SessionEnded)
        session.guests.values.forEach { it.conn?.send(ServerFrame.SessionEnded) }
        session.pending.values.forEach { it.conn.send(ServerFrame.SessionEnded) }
        sessions.remove(session.id)
        codes.remove(session.code)
        sweeper?.schedule({
            session.host?.close(1000)
            session.guests.values.forEach { it.conn?.close(1000) }
            session.pending.values.forEach { it.conn.close(1000) }
        }, 60, TimeUnit.MILLISECONDS)
        onLog("session ${session.id.take(8)} ended")
    }

    private fun guestJoin(conn: Connection, frame: ClientFrame.GuestJoin) {
        val code = RemoteCode.normalize(frame.code)
        val sessionId = codes[code]
        val session = sessionId?.let { sessions[it] }

        // Resume first: a returning guest keeps its seat and role.
        frame.resume?.let { resume ->
            val s = sessions[resume.sessionId]
            val guest = s?.guests?.get(resume.guestId)
                ?: s?.grace?.get(resume.guestId)
            if (s != null && guest != null && guest.token == resume.token) {
                s.grace.remove(guest.id)
                s.guests[guest.id] = guest
                guest.conn?.close(4001)
                guest.conn = conn
                conn.guestSession = s
                conn.guestId = guest.id
                conn.send(ServerFrame.JoinApproved(s.id, guest.id, guest.token, guest.role))
                s.host?.send(ServerFrame.GuestOnline(guest.id, guest.name, guest.role))
                return
            }
        }

        if (session == null || System.currentTimeMillis() > session.codeExpiresAt) {
            conn.send(ServerFrame.ErrorFrame("bad_code"))
            return
        }
        if (session.pending.size >= RemoteLimits.MAX_PENDING) {
            conn.send(ServerFrame.ErrorFrame("too_many_pending"))
            return
        }
        if (session.pending.values.any { it.conn === conn }) return

        val guestId = UUID.randomUUID().toString()
        val name = frame.name.trim().take(48).ifEmpty { "Guest" }
        session.pending[guestId] = Pending(conn, name)
        conn.guestSession = session
        conn.guestId = guestId
        conn.send(ServerFrame.JoinPending)
        session.host?.send(ServerFrame.JoinRequest(guestId, name))
        onLog("join request from $name")
    }

    private fun broadcast(session: Session, frame: ServerFrame) {
        session.guests.values.forEach { it.conn?.send(frame) }
        session.pending.values.forEach { it.conn.send(frame) }
    }

    private fun newCode(session: Session): String {
        repeat(100) {
            val code = RemoteCode.generate(random)
            if (!codes.containsKey(code)) {
                codes.remove(session.code)
                codes[code] = session.id
                return code
            }
        }
        return RemoteCode.generate(random)
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        sessions.values.forEach { s ->
            // Seats survive a socket drop for the grace window; after that the
            // guest must be approved again.
            s.grace.entries.removeIf { (id, guest) ->
                val since = s.graceAt[id] ?: now
                val expired = guest.conn == null && now - since > RemoteLimits.GUEST_SEAT_GRACE_MS
                if (expired) {
                    s.graceAt.remove(id)
                    s.host?.send(ServerFrame.GuestLeft(id))
                    true
                } else false
            }
            s.pending.entries.removeIf { (_, p) -> p.conn.closed }
        }
        // Sessions with no host socket for a very long time are reclaimed.
        sessions.entries.removeIf { (_, s) ->
            s.host == null && now > s.codeExpiresAt + TimeUnit.HOURS.toMillis(6)
        }
    }

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }

    private fun wsAccept(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        return base64(digest)
    }

    /** Minimal RFC 4648 base64 (avoids java.util.Base64, API 26+ on Android). */
    private fun base64(data: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(table[b0 shr 2])
            sb.append(table[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) table[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) table[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    companion object {
        /** Best-effort LAN IPv4 addresses a guest could use to reach this device. */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<InetAddress>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
