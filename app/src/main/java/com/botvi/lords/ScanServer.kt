package com.botvi.lords

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny loopback-only HTTP server (127.0.0.1:8765) that hands the scanning bot the next map spot.
 *   GET /next            -> {"ok":true,"x":..,"y":..,"note":".."}   or {"ok":false}
 *   GET /done?x=..&y=..  -> marks that spot as looked at
 *   GET /status          -> coverage numbers
 * Nothing is sent to the game and nothing leaves the device.
 */
class ScanServer(private val port: Int = 8765) {
    @Volatile private var ss: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val s = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
                ss = s
                Store.log("scan server on 127.0.0.1:$port")
                while (running) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} }
                }
            } catch (e: Exception) {
                Store.log("scan server failed: ${e.message}")
            } finally {
                running = false
                ss = null
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Exception) {}
        ss = null
    }

    private fun handle(c: Socket) {
        c.soTimeout = 3000
        val rd = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
        val line = rd.readLine() ?: return
        while (true) { val h = rd.readLine() ?: break; if (h.isEmpty()) break }
        val target = line.split(" ").getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val q = HashMap<String, String>()
        target.substringAfter('?', "").split('&').forEach {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) q[kv[0]] = kv[1]
        }
        val body = when (path) {
            "/next" -> {
                val t = ScanPlanner.nextTarget((q["revisit"]?.toLongOrNull() ?: 60L) * 1000L)
                if (t == null) "{\"ok\":false}" else "{\"ok\":true,\"x\":${t.x},\"y\":${t.y},\"note\":\"${t.note}\"}"
            }
            "/done" -> {
                val x = q["x"]?.toIntOrNull(); val y = q["y"]?.toIntOrNull()
                if (x != null && y != null) { ScanPlanner.markVisited(x, y); "{\"ok\":true}" } else "{\"ok\":false}"
            }
            "/status" -> "{\"covered\":${ScanPlanner.coveredBlocks()},\"blocks\":${ScanPlanner.totalBlocks()},\"due\":${ScanPlanner.dueCount()},\"castles\":${Store.stats().castles}}"
            else -> "{\"ok\":false}"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }
}
