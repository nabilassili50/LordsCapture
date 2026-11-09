package com.botvi.lords

import java.net.HttpURLConnection
import java.net.URL

/** POSTs a JSON snapshot of the Store to the PHP server (ingest.php). Read-only w.r.t. the game. */
object Uploader {
    fun post(url: String, token: String, onResult: (String) -> Unit) {
        Thread {
            val msg = try {
                val body = Store.json(token).toByteArray(Charsets.UTF_8)
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
                if (code in 200..299) "تم الرفع: " + text.take(120) else "فشل ($code): " + text.take(120)
            } catch (e: Exception) {
                "فشل الرفع: " + (e.message ?: e.javaClass.simpleName)
            }
            onResult(msg)
        }.also {
            it.isDaemon = true
            it.start()
        }
    }
}
