package com.botvi.lords

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class CaptureService : Service() {
    @Volatile private var runFlag = false
    @Volatile private var conn: PcapConnection? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        val prefix = intent?.getStringExtra("prefix") ?: "192.243."
        val serverPort = intent?.getIntExtra("serverPort", 0) ?: 0
        val pdPort = intent?.getIntExtra("pdPort", 8080) ?: 8080
        startCapture(prefix, serverPort, pdPort)
        return START_NOT_STICKY
    }

    private fun startCapture(prefix: String, serverPort: Int, pdPort: Int) {
        if (worker != null) return
        runFlag = true
        worker = Thread {
            while (runFlag) {
                try {
                    Store.status = "اتصال بـ PCAPdroid (127.0.0.1:$pdPort)..."
                    val c = PcapSource.open("127.0.0.1", pdPort)
                    conn = c
                    Store.status = "متصل بـ PCAPdroid — بانتظار حزم اللعبة"
                    Store.log("connected to PCAPdroid")
                    val tracker = FlowTracker(
                        serverPrefix = prefix,
                        serverPort = serverPort,
                        onGamePayload = { handle(it) },
                        log = { Store.log(it) },
                        onStatus = { Store.status = it }
                    )
                    PcapStreamReader(c.stream) { buf, off, len -> tracker.onIp(buf, off, len) }
                        .pump { runFlag }
                } catch (e: Exception) {
                    if (runFlag) {
                        Store.log("error: ${e.message}")
                        Store.status = "انقطع الاتصال — إعادة المحاولة بعد 3 ثوان"
                    }
                } finally {
                    conn?.close()
                    conn = null
                }
                if (runFlag) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                }
            }
            Store.status = "متوقف"
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun handle(payload: ByteArray) {
        Store.frames++
        val items = PacketParser.parse(payload)
        for (p in items) Store.add(p)
        Store.recordOp(payload, items.size)
        if (Store.dumpOn) Store.addDump(payload, items.size)
        if (items.isNotEmpty()) Store.log("parsed ${items.size} item(s) from a ${payload.size}-byte packet")
        for (p in items) {
            if (p is CastlePopup) Store.log("castle popup ${p.x},${p.y} [${p.guildFullName}] vip=${p.vip} rank=${p.guildRank} might=${p.might} kills=${p.kills}")
        }
        val mapCount = items.count { it is MapPlayer }
        if (mapCount > 0) Store.log("map: $mapCount castle(s) with coordinates")
    }

    private fun stopCapture() {
        runFlag = false
        conn?.close()
        worker?.interrupt()
        worker = null
        Store.status = "متوقف"
        Store.save()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Lords Capture", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        return b.setContentTitle("Lords Capture v1")
            .setContentText("الالتقاط يعمل")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.botvi.lords.STOP"
        private const val CH = "lordscapture"
    }
}