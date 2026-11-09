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
    private lateinit var urlEt: EditText
    private lateinit var tokEt: EditText
    private lateinit var searchEt: EditText
    private lateinit var reportTv: TextView
    private var viewMode = -1
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

    private fun modeBtn(t: String, m: Int) = btn(t) { viewMode = m; refresh() }.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg b: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        b.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
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

        root.addView(label("رابط رفع السيرفر (ingest.php) — اختياري"))
        urlEt = field("http://192.168.1.5:8080/lords/ingest.php", prefs.getString("up_url", "") ?: "", false)
        root.addView(urlEt)
        root.addView(label("مفتاح الرفع (token)"))
        tokEt = field("token", prefs.getString("up_tok", "") ?: "", false)
        root.addView(tokEt)

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
        root.addView(btn("📈 تصدير سجل القوة (CSV)") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "LordsCapture_might_history_${System.currentTimeMillis() / 1000}.csv")
            }
            startActivityForResult(i, REQ_HIST)
        })
        val dumpBtn = btn("🔍 تفريغ الحزم: متوقف") {}
        dumpBtn.setOnClickListener {
            Store.dumpOn = !Store.dumpOn
            dumpBtn.text = "🔍 تفريغ الحزم: " + (if (Store.dumpOn) "يعمل" else "متوقف")
        }
        root.addView(dumpBtn)
        root.addView(btn("📄 تصدير التفريغ (TXT)") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "LordsCapture_dump_${System.currentTimeMillis() / 1000}.txt")
            }
            startActivityForResult(i, REQ_DUMP)
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
        root.addView(btn("☁ رفع للسيرفر الآن") {
            val u = urlEt.text.toString().trim()
            val tk = tokEt.text.toString().trim()
            if (u.isEmpty()) {
                Toast.makeText(this, "أدخل رابط السيرفر أولاً", Toast.LENGTH_SHORT).show()
                return@btn
            }
            prefs.edit().putString("up_url", u).putString("up_tok", tk).apply()
            Toast.makeText(this, "جاري الرفع...", Toast.LENGTH_SHORT).show()
            Uploader.post(u, tk) { msg ->
                Store.log(msg)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
        })
        root.addView(statusTv)
        root.addView(summaryTv)

        root.addView(label("القوائم والتقارير"))
        searchEt = field("بحث بالاسم / التحالف", "", false)
        root.addView(searchEt)
        root.addView(row(modeBtn("👑 القوة", Store.MODE_POWER), modeBtn("🏰 قلاع", Store.MODE_CASTLES), modeBtn("🎁 هدايا", Store.MODE_GIFTS)))
        root.addView(row(modeBtn("🛡 تحالفات", Store.MODE_ALLIANCE), modeBtn("💤 خاملون", Store.MODE_INACTIVE), modeBtn("📊 تغيّرات", Store.MODE_CHANGES)))
        root.addView(btn("📤 مشاركة التقرير") {
            val t = reportTv.text.toString()
            if (t.isEmpty()) {
                Toast.makeText(this, "اختر قائمة أولاً", Toast.LENGTH_SHORT).show()
                return@btn
            }
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, t)
            }
            startActivity(Intent.createChooser(i, "مشاركة"))
        })
        reportTv = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(reportTv)
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
        if (viewMode >= 0) {
            val t = Store.report(viewMode, searchEt.text.toString())
            if (reportTv.text.toString() != t) reportTv.text = t
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        Store.save()
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQ_EXPORT || requestCode == REQ_DUMP || requestCode == REQ_HIST) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val text = when (requestCode) {
                    REQ_DUMP -> Store.dumpText()
                    REQ_HIST -> Store.historyCsv()
                    else -> Store.csv()
                }
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val REQ_EXPORT = 42
        private const val REQ_DUMP = 43
        private const val REQ_HIST = 44
    }
}