package com.botvi.lords

/**
 * Read-only version of CapturePackets.cpp: picks the game server by IP prefix (default "192.243."),
 * reassembles the server->client TCP byte stream per client port, finds game packet boundaries
 * and passes every game payload to [onGamePayload]. Never sends anything.
 */
class FlowTracker(
    private val serverPrefix: String,
    private val serverPort: Int,
    private val onGamePayload: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var locked = false
    private var lockedIp = 0
    private var lockedPort = 0
    private val flows = HashMap<Int, Flow>()

    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun i32(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun ipStr(i: Int) = "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"

    fun onIp(d: ByteArray, off: Int, len: Int) {
        if (len < 40) return
        if (((d[off].toInt() ushr 4) and 0xF) != 4) return
        val ihl = (d[off].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 20) return
        if ((d[off + 9].toInt() and 0xFF) != 6) return          // TCP only
        if ((u16(d, off + 6) and 0x3FFF) != 0) return            // fragments not supported
        val totalLen = u16(d, off + 2)
        val end = if (totalLen >= ihl + 20 && totalLen <= len) off + totalLen else off + len
        val src = i32(d, off + 12)
        val dst = i32(d, off + 16)
        val t = off + ihl
        val sport = u16(d, t)
        val dport = u16(d, t + 2)
        val seq = i32(d, t + 4)
        val dataOff = ((d[t + 12].toInt() ushr 4) and 0xF) * 4
        val flags = d[t + 13].toInt() and 0xFF
        if (dataOff < 20) return
        val payStart = t + dataOff
        val payLen = end - payStart

        if (!locked) {
            if (serverPrefix.isEmpty()) return
            val fromServer = ipStr(src).startsWith(serverPrefix) && (serverPort == 0 || sport == serverPort)
            val toServer = ipStr(dst).startsWith(serverPrefix) && (serverPort == 0 || dport == serverPort)
            if (fromServer) {
                lockedIp = src; lockedPort = sport
            } else if (toServer) {
                lockedIp = dst; lockedPort = dport
            } else return
            locked = true
            val s = "${ipStr(lockedIp)}:$lockedPort"
            log("server locked: $s")
            onStatus("يلتقط من السيرفر $s")
        }

        // client -> server: only watch for a new connection (SYN) on a reused port
        if (dst == lockedIp && dport == lockedPort) {
            if ((flags and 0x02) != 0 && (flags and 0x10) == 0) flows.remove(sport)
            return
        }
        if (src != lockedIp || sport != lockedPort) return

        val flow = flows.getOrPut(dport) { Flow() }
        val pay = if (payLen > 0) d.copyOfRange(payStart, payStart + payLen) else EMPTY
        flow.onSeg(seq, flags, pay)
    }

    private inner class Flow {
        var nextSeq = 0
        var haveSeq = false
        var aligned = false
        val ooo = HashMap<Int, ByteArray>()
        val framer = StreamFramer(onDesync = {
            aligned = false
            log("lost sync, waiting for next aligned packet")
        }) { onGamePayload(it) }

        fun reset() {
            haveSeq = false; aligned = false; ooo.clear(); framer.reset()
        }

        fun onSeg(seq: Int, flags: Int, pay: ByteArray) {
            if ((flags and 0x02) != 0) {              // SYN-ACK: stream starts at seq+1
                reset()
                nextSeq = seq + 1; haveSeq = true; aligned = true
                return
            }
            if ((flags and 0x04) != 0) { reset(); return }   // RST
            if (pay.isEmpty()) {
                if ((flags and 0x01) != 0) reset()          // FIN
                return
            }
            if (!haveSeq) { nextSeq = seq; haveSeq = true; aligned = false }

            var s = seq
            var p = pay
            var diff = s - nextSeq
            if (diff < 0) {                                  // retransmission / overlap
                val overlap = -diff
                if (overlap >= p.size) return
                p = p.copyOfRange(overlap, p.size)
                s = nextSeq
                diff = 0
            }
            if (diff > 0) {                                  // gap: keep it for later
                if (ooo.size > 64) {                         // too much missing, jump ahead
                    ooo.clear(); aligned = false; framer.reset()
                    nextSeq = s
                    deliver(p)
                } else {
                    ooo[s] = p
                }
                return
            }
            deliver(p)
            while (true) {
                val q = ooo.remove(nextSeq) ?: break
                deliver(q)
            }
        }

        private fun deliver(p: ByteArray) {
            nextSeq += p.size
            if (!aligned) {
                if (StreamFramer.looksAligned(p)) {
                    aligned = true
                    framer.reset()
                } else return
            }
            framer.feed(p)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}