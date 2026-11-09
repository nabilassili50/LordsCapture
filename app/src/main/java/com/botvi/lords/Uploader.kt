package com.botvi.lords

import java.net.HttpURLConnection
import java.net.URL

/** POSTs a JSON snapshot of the Store to the PHP server (ingest.php). delta = only items changed since the last successful upload. */
object Uploader {
    @Volatile private var busy = false

    fun post(url: String, token: String, delta: Boolean = false, onResult: (String) -> Unit) {
        if (busy) {
            onResult("رفع قيد التنفيذ...")
            return
        }
        busy = true
        Thread {
            val msg = try {
                val snap = Store.json(token, delta)
                if (delta && snap.items == 0) {
                    "لا تغييرات للرفع"
                } else {
                    val body = snap.json.toByteArray(Charsets.UTF_8)
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 10000
                    c.readTimeout = 20000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    c.disconnect()
                    if (code in 200..299) {
                        Store.commitSent()
                        "تم الرفع (${snap.items}): " + text.take(100)
                    } else {
                        "فشل ($code): " + text.take(100)
                    }
                }
            } catch (e: Exception) {
                "فشل الرفع: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
            onResult(msg)
        }.also {
            it.isDaemon = true
            it.start()
        }
    }
}
