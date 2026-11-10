package com.botvi.lords

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class CaptureService : Service() {
    @Volatile private var runFlag = false
    @Volatile private var conn: PcapConnection? = null
    private var worker: Thread? = null
    private var monitor: Thread? = null
    private val scanServer = ScanServer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification("الالتقاط يعمل")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        val prefix = intent?.getStringExtra("prefix") ?: "192.243."
        val serverPort = intent?.getIntExtra("serverPort", 0) ?: 0
        val pdPort = intent?.getIntExtra("pdPort", 8080) ?: 8080
        startCapture(prefix, serverPort, pdPort)
        startMonitor()
        return START_NOT_STICKY
    }

    private fun startCapture(prefix: String, serverPort: Int, pdPort: Int) {
        if (worker != null) return
        runFlag = true
        scanServer.start()
        worker = Thread {
            var fails = 0
            while (runFlag) {
                try {
                    Store.status = "اتصال بـ PCAPdroid (127.0.0.1:$pdPort)..."
                    val c = PcapSource.open("127.0.0.1", pdPort)
                    conn = c
                    if (fails > 0) cancelAlert()
                    fails = 0
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
                        fails++
                        // log the first failure and then every 10th, so the log does not drown in retries
                        if (fails == 1 || fails % 10 == 0) Store.log("PCAPdroid غير متصل (محاولة $fails): ${e.message}")
                        Store.status = "PCAPdroid متوقف — شغّل الالتقاط فيه (محاولة $fails)"
                        if (fails == 5) postAlert()
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

    /** Every few seconds: refresh the notification text and run the optional auto-upload. */
    private fun startMonitor() {
        if (monitor != null) return
        monitor = Thread {
            var lastUp = 0L
            var tick = 0
            while (runFlag) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                if (!runFlag) break
                tick++
                val watchAl = Store.takeAlerts()
                if (watchAl.isNotEmpty()) postWatch(watchAl)
                if (tick % 2 == 0) {
                    val st = Store.stats()
                    updateNotification("حزم ${st.frames} • لاعبون ${st.might} • قلاع ${st.castles}")
                }
                val p = getSharedPreferences("lc", MODE_PRIVATE)
                val mins = p.getInt("up_min", 0)
                val url = p.getString("up_url", "") ?: ""
                if (mins <= 0 || url.isEmpty()) continue
                val now = System.currentTimeMillis()
                if (now - lastUp < mins * 60_000L) continue
                lastUp = now
                Uploader.post(url, p.getString("up_tok", "") ?: "", true) { msg ->
                    Store.setUpload(msg)
                    if (!msg.startsWith("لا تغييرات")) Store.log(msg)
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun handle(payload: ByteArray) {
        Store.onFrame()
        val items = PacketParser.parse(payload)
        for (p in items) Store.add(p)
        Store.recordOp(payload, items.size)
        if (Store.dumpOn && Store.dumpWanted(payload)) Store.addDump(payload, items.size)
        if (items.isNotEmpty()) Store.log("parsed ${items.size} item(s) from a ${payload.size}-byte packet")
        for (p in items) {
            if (p is CastlePopup) Store.log("castle popup ${p.x},${p.y} [${p.guildFullName}] vip=${p.vip} rank=${p.guildRank} might=${p.might} kills=${p.kills}")
        }
        val mapCount = items.count { it is MapPlayer }
        if (mapCount > 0) Store.log("map: $mapCount castle(s) with coordinates")
    }

    private fun stopCapture() {
        runFlag = false
        scanServer.stop()
        conn?.close()
        worker?.interrupt()
        worker = null
        monitor?.interrupt()
        monitor = null
        Store.status = "متوقف"
        Store.save()
        cancelAlert()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Lords Capture", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ALERT_CH, "تنبيهات الالتقاط", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        return b.setContentTitle("Lords Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun postAlert() {
        try {
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ALERT_CH) else Notification.Builder(this)
            val n = b.setContentTitle("PCAPdroid متوقف")
                .setContentText("شغّل الالتقاط في PCAPdroid (HTTP Server، المنفذ 8080) — سيتصل التطبيق تلقائياً")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(2, n)
        } catch (_: Exception) {
        }
    }

    private val recentWatch = ArrayDeque<String>()

    private fun postWatch(alerts: List<String>) {
        try {
            for (a in alerts) recentWatch.addLast(a)
            while (recentWatch.size > 6) recentWatch.removeFirst()
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ALERT_CH) else Notification.Builder(this)
            val n = b.setContentTitle("👁 مراقبة اللاعبين")
                .setContentText(alerts.last())
                .setStyle(Notification.BigTextStyle().bigText(recentWatch.joinToString("\n")))
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(3, n)
        } catch (_: Exception) {
        }
    }

    private fun cancelAlert() {
        try {
            getSystemService(NotificationManager::class.java).cancel(2)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.botvi.lords.STOP"
        private const val CH = "lordscapture"
        private const val ALERT_CH = "lordsalert"
    }
}
