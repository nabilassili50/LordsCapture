package com.botvi.lords

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusTv: TextView
    private lateinit var summaryTv: TextView
    private lateinit var logTv: TextView
    private lateinit var prefixEt: EditText
    private lateinit var portEt: EditText
    private lateinit var pdPortEt: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun field(h: String, v: String, number: Boolean) = EditText(this).apply {
        hint = h
        setText(v)
        setSingleLine(true)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("lc", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "Lords Capture v1"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(label("1) شغّل PCAPdroid: اختر لوردز موبايل كتطبيق هدف، نمط التفريغ HTTP Server، ثم ابدأ الالتقاط.\n2) اضغط ابدأ هنا ثم العب."))

        root.addView(label("بادئة IP السيرفر (من كودك: 192.243.)"))
        prefixEt = field("192.243.", prefs.getString("prefix", "192.243.") ?: "192.243.", false)
        root.addView(prefixEt)

        root.addView(label("منفذ السيرفر (اتركه 0 للتحديد التلقائي)"))
        portEt = field("0", prefs.getString("sport", "0") ?: "0", true)
        root.addView(portEt)

        root.addView(label("منفذ HTTP في PCAPdroid"))
        pdPortEt = field("8080", prefs.getString("pd", "8080") ?: "8080", true)
        root.addView(pdPortEt)

        root.addView(btn("▶ ابدأ") {
            prefs.edit()
                .putString("prefix", prefixEt.text.toString().trim())
                .putString("sport", portEt.text.toString().trim())
                .putString("pd", pdPortEt.text.toString().trim())
                .apply()
            val i = Intent(this, CaptureService::class.java)
                .putExtra("prefix", prefixEt.text.toString().trim())
                .putExtra("serverPort", portEt.text.toString().trim().toIntOrNull() ?: 0)
                .putExtra("pdPort", pdPortEt.text.toString().trim().toIntOrNull() ?: 8080)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        })
        root.addView(btn("■ إيقاف") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        })
        root.addView(btn("💾 تصدير CSV") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "LordsCapture_v1_${System.currentTimeMillis() / 1000}.csv")
            }
            startActivityForResult(i, REQ_EXPORT)
        })
        root.addView(btn("🗑 مسح البيانات") {
            Store.clear()
            refresh()
        })

        statusTv = TextView(this).apply { textSize = 15f; setPadding(0, dp(12), 0, dp(4)) }
        summaryTv = TextView(this).apply { textSize = 14f }
        logTv = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(statusTv)
        root.addView(summaryTv)
        root.addView(logTv)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refresh() {
        statusTv.text = "الحالة: " + Store.status
        summaryTv.text = Store.summary()
        logTv.text = Store.tail(40)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(Store.csv().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val REQ_EXPORT = 42
    }
}
