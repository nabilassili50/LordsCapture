package com.botvi.lords

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    // palette
    private val cBg = Color.parseColor("#0E1320")
    private val cCard = Color.parseColor("#1A2133")
    private val cTile = Color.parseColor("#232C44")
    private val cField = Color.parseColor("#11182A")
    private val cStroke = Color.parseColor("#2C3752")
    private val cAccent = Color.parseColor("#4C8DFF")
    private val cGreen = Color.parseColor("#35C37D")
    private val cRed = Color.parseColor("#FF5A5F")
    private val cOrange = Color.parseColor("#FFB020")
    private val cText = Color.parseColor("#E8ECF5")
    private val cMuted = Color.parseColor("#8D98B3")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var dot: View
    private lateinit var statusTv: TextView
    private lateinit var extrasTv: TextView
    private lateinit var coverTv: TextView
    private lateinit var reportTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var prefixEt: EditText
    private lateinit var portEt: EditText
    private lateinit var pdPortEt: EditText
    private lateinit var urlEt: EditText
    private lateinit var tokEt: EditText
    private lateinit var uploadTv: TextView
    private lateinit var logTv: TextView
    private val tileVals = ArrayList<TextView>()
    private val modeChips = ArrayList<Pair<Int, Button>>()
    private val intervalChips = ArrayList<Pair<Int, Button>>()
    private var viewMode = -1
    private var lastVer = -1L
    private var lastStatus = ""

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- small view helpers ----------
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(title: String? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 16)
        setPadding(dp(14), dp(12), dp(14), dp(14))
        layoutParams = lp(MATCH, WRAP, 0f, 0, 0, 0, 10)
        if (title != null) addView(tv(title, 14f, cMuted, true).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun pill(t: String, bg: Int, fg: Int, stroke: Int? = null, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 14f
        setTextColor(fg)
        background = rounded(bg, 12, stroke)
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick() }
    }

    private fun field(h: String, v: String, number: Boolean) = EditText(this).apply {
        hint = h
        setText(v)
        setSingleLine(true)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setTextColor(cText)
        setHintTextColor(cMuted)
        textSize = 14f
        background = rounded(cField, 10, cStroke)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(MATCH, WRAP, 0f, 0, 2, 0, 6)
    }

    private fun label(t: String) = tv(t, 12f, cMuted).apply { setPadding(0, dp(6), 0, dp(2)) }

    private fun chip(t: String, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        setPadding(dp(14), dp(6), dp(14), dp(6))
        layoutParams = lp(WRAP, dp(38), 0f, 0, 0, 6, 0)
        setOnClickListener { onClick() }
    }

    private fun styleChip(b: Button, on: Boolean) {
        b.background = rounded(if (on) cAccent else cTile, 20)
        b.setTextColor(if (on) Color.WHITE else cMuted)
    }

    private fun restyleModes() { for ((m, b) in modeChips) styleChip(b, m == viewMode) }

    private fun restyleIntervals(cur: Int) { for ((m, b) in intervalChips) styleChip(b, m == cur) }

    private fun rowOf(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp(MATCH, WRAP, 0f, 0, 0, 0, 6)
        v.forEach { addView(it) }
    }

    private fun half(b: View, first: Boolean = false): View = b.apply {
        layoutParams = lp(0, WRAP, 1f, if (first) 0 else 6, 0, 0, 0)
    }

    private fun toggler(label: String, content: View, open: Boolean): TextView {
        content.visibility = if (open) View.VISIBLE else View.GONE
        val h = tv(label + (if (open) "  ▴" else "  ▾"), 13f, cAccent, true)
        h.setPadding(0, dp(8), 0, dp(4))
        h.setOnClickListener {
            val show = content.visibility != View.VISIBLE
            content.visibility = if (show) View.VISIBLE else View.GONE
            h.text = label + (if (show) "  ▴" else "  ▾")
            refresh(true)
        }
        return h
    }

    private fun saveServerPrefs() {
        if (!::urlEt.isInitialized) return
        getSharedPreferences("lc", MODE_PRIVATE).edit()
            .putString("up_url", urlEt.text.toString().trim())
            .putString("up_tok", tokEt.text.toString().trim())
            .apply()
    }

    private fun createDoc(mime: String, name: String, req: Int) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, req)
    }

    // ---------- screen ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        val prefs = getSharedPreferences("lc", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // header
        root.addView(tv("Lords Capture", 24f, Color.WHITE, true))
        root.addView(tv("التقاط سلبي لبيانات اللعبة — لا يعدّل ولا يرسل شيئاً للعبة", 12f, cMuted).apply {
            setPadding(0, dp(2), 0, dp(12))
        })

        // status card
        val statusCard = card()
        dot = View(this).apply { layoutParams = lp(dp(12), dp(12), 0f, 10, 0, 0, 0) }
        statusTv = tv("", 14f, cText, true).apply { layoutParams = lp(0, WRAP, 1f) }
        statusCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
            addView(dot)
            addView(statusTv)
        })
        coverTv = tv("", 12f, cMuted).apply { setPadding(0, dp(8), 0, 0) }
        extrasTv = tv("", 12f, cOrange).apply { setPadding(0, dp(4), 0, 0) }
        statusCard.addView(coverTv)
        statusCard.addView(extrasTv)
        root.addView(statusCard)

        // stat tiles (3 x 2)
        val names = listOf("حزم", "لاعبون", "قلاع", "نوافذ", "هدايا", "تغيّرات")
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(MATCH, WRAP, 0f, 0, 0, 0, 10)
        }
        for (r in 0..1) {
            val rowL = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(MATCH, WRAP, 0f, 0, if (r == 0) 0 else 8, 0, 0)
            }
            for (c in 0..2) {
                val idx = r * 3 + c
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setGravity(Gravity.CENTER)
                    background = rounded(cTile, 14)
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    layoutParams = lp(0, WRAP, 1f, if (c == 0) 0 else 8, 0, 0, 0)
                }
                val v = tv("0", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                tileVals.add(v)
                tile.addView(v)
                tile.addView(tv(names[idx], 11f, cMuted).apply { gravity = Gravity.CENTER })
                rowL.addView(tile)
            }
            grid.addView(rowL)
        }
        root.addView(grid)

        // control card
        val ctl = card()
        prefixEt = field("192.243.", prefs.getString("prefix", "192.243.") ?: "192.243.", false)
        portEt = field("0", prefs.getString("sport", "0") ?: "0", true)
        pdPortEt = field("8080", prefs.getString("pd", "8080") ?: "8080", true)
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("بادئة IP السيرفر"))
            addView(prefixEt)
            addView(label("منفذ السيرفر (0 = تلقائي)"))
            addView(portEt)
            addView(label("منفذ HTTP في PCAPdroid"))
            addView(pdPortEt)
        }
        ctl.addView(rowOf(
            half(pill("▶  ابدأ الالتقاط", cAccent, Color.WHITE) {
                prefs.edit()
                    .putString("prefix", prefixEt.text.toString().trim())
                    .putString("sport", portEt.text.toString().trim())
                    .putString("pd", pdPortEt.text.toString().trim())
                    .apply()
                saveServerPrefs()
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("prefix", prefixEt.text.toString().trim())
                    .putExtra("serverPort", portEt.text.toString().trim().toIntOrNull() ?: 0)
                    .putExtra("pdPort", pdPortEt.text.toString().trim().toIntOrNull() ?: 8080)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }, true),
            half(pill("■  إيقاف", cField, cRed, cRed) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            })
        ))
        ctl.addView(toggler("⚙ إعدادات الالتقاط", settings, false))
        ctl.addView(settings)
        root.addView(ctl)

        // reports card
        val rep = card("القوائم والتقارير")
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            "👑 القوة" to Store.MODE_POWER, "🏰 قلاع" to Store.MODE_CASTLES, "🎁 هدايا" to Store.MODE_GIFTS,
            "🛡 تحالفات" to Store.MODE_ALLIANCE, "💤 خاملون" to Store.MODE_INACTIVE, "📊 تغيّرات" to Store.MODE_CHANGES
        )
        for ((t, m) in defs) {
            val b = chip(t) { viewMode = m; restyleModes(); refresh(true) }
            modeChips.add(m to b)
            chipRow.addView(b)
        }
        restyleModes()
        rep.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = lp(MATCH, WRAP, 0f, 0, 0, 0, 8)
            addView(chipRow)
        })
        searchEt = field("بحث بالاسم / التحالف", "", false)
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(true) }
        })
        rep.addView(searchEt)
        reportTv = tv("اختر قائمة من الأزرار أعلاه", 13f, cText).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        rep.addView(reportTv)
        rep.addView(pill("📤  مشاركة التقرير", cTile, cText) {
            val t = reportTv.text.toString()
            if (viewMode < 0 || t.isEmpty()) {
                Toast.makeText(this, "اختر قائمة أولاً", Toast.LENGTH_SHORT).show()
            } else {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, t)
                }
                startActivity(Intent.createChooser(i, "مشاركة"))
            }
        })
        root.addView(rep)

        // tools card
        val tools = card("الأدوات والتصدير")
        val dumpBtn = pill("🔍 تفريغ الحزم: متوقف", cTile, cText) {}
        dumpBtn.setOnClickListener {
            Store.dumpOn = !Store.dumpOn
            dumpBtn.text = "🔍 تفريغ الحزم: " + (if (Store.dumpOn) "يعمل" else "متوقف")
        }
        tools.addView(rowOf(
            half(pill("💾 CSV", cTile, cText) {
                createDoc("text/csv", "LordsCapture_v1_${System.currentTimeMillis() / 1000}.csv", REQ_EXPORT)
            }, true),
            half(pill("📈 سجل القوة", cTile, cText) {
                createDoc("text/csv", "LordsCapture_might_history_${System.currentTimeMillis() / 1000}.csv", REQ_HIST)
            })
        ))
        tools.addView(rowOf(
            half(dumpBtn, true),
            half(pill("📄 تصدير التفريغ", cTile, cText) {
                createDoc("text/plain", "LordsCapture_dump_${System.currentTimeMillis() / 1000}.txt", REQ_DUMP)
            })
        ))
        tools.addView(pill("🗑 مسح كل البيانات", cField, cRed, cRed) {
            Store.clear()
            refresh(true)
        })
        root.addView(tools)

        // server card
        val srv = card("رفع للسيرفر (PHP / MySQL)")
        urlEt = field("http://192.168.1.5:8080/lords/ingest.php", prefs.getString("up_url", "") ?: "", false)
        tokEt = field("token", prefs.getString("up_tok", "") ?: "", false)
        srv.addView(label("رابط ingest.php"))
        srv.addView(urlEt)
        srv.addView(label("مفتاح الرفع"))
        srv.addView(tokEt)
        srv.addView(label("الرفع التلقائي (التغييرات فقط، أثناء الالتقاط)"))
        val curMin = prefs.getInt("up_min", 0)
        val ivRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(MATCH, WRAP, 0f, 0, 2, 0, 10)
        }
        for ((t, m) in listOf("يدوي" to 0, "5 د" to 5, "15 د" to 15, "30 د" to 30)) {
            val b = chip(t) {
                saveServerPrefs()
                prefs.edit().putInt("up_min", m).apply()
                restyleIntervals(m)
                if (m > 0 && urlEt.text.toString().trim().isEmpty()) {
                    Toast.makeText(this, "أدخل رابط السيرفر ليعمل الرفع التلقائي", Toast.LENGTH_SHORT).show()
                }
            }
            intervalChips.add(m to b)
            ivRow.addView(b)
        }
        restyleIntervals(curMin)
        srv.addView(ivRow)
        srv.addView(pill("☁  رفع الآن (كل البيانات)", cAccent, Color.WHITE) {
            val u = urlEt.text.toString().trim()
            if (u.isEmpty()) {
                Toast.makeText(this, "أدخل رابط السيرفر أولاً", Toast.LENGTH_SHORT).show()
            } else {
                saveServerPrefs()
                Toast.makeText(this, "جاري الرفع...", Toast.LENGTH_SHORT).show()
                Uploader.post(u, tokEt.text.toString().trim(), false) { msg ->
                    Store.setUpload(msg)
                    Store.log(msg)
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                }
            }
        })
        uploadTv = tv("", 12f, cMuted).apply { setPadding(0, dp(8), 0, 0) }
        srv.addView(uploadTv)
        root.addView(srv)

        // log (collapsed by default)
        logTv = tv("", 11f, cMuted).apply {
            typeface = Typeface.MONOSPACE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(4), 0, 0)
        }
        val logCard = card()
        logCard.addView(toggler("📜 السجل", logTv, false))
        logCard.addView(logTv)
        root.addView(logCard)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh(true)
    }

    private fun refresh(forceNow: Boolean = false) {
        if (!::logTv.isInitialized) return
        val v = Store.version
        val s = Store.status
        if (!forceNow && v == lastVer && s == lastStatus) return
        lastVer = v
        lastStatus = s

        val col = when {
            s.contains("متوقف") -> cRed
            s.contains("يلتقط") -> cGreen
            else -> cOrange
        }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
        statusTv.text = s

        val st = Store.stats()
        val vals = listOf(st.frames.toString(), st.might.toString(), st.castles.toString(),
            st.popups.toString(), st.gifts.toString(), st.changed.toString())
        for (i in vals.indices) tileVals[i].text = vals[i]
        coverTv.text = "حزم الخريطة: ${st.mapFrames} • نوافذ القلاع: ${st.popupFrames}\n" +
            "تغطية الخريطة: ${st.covered}/${st.blocks} • قلاع تحتاج فحص: ${st.due}"

        val ex = Store.extras()
        extrasTv.text = ex
        extrasTv.visibility = if (ex.isEmpty()) View.GONE else View.VISIBLE

        val up = Store.lastUpload
        uploadTv.text = up
        uploadTv.visibility = if (up.isEmpty()) View.GONE else View.VISIBLE

        if (viewMode >= 0) {
            val t = Store.report(viewMode, searchEt.text.toString())
            if (reportTv.text.toString() != t) reportTv.text = t
        }
        if (logTv.visibility == View.VISIBLE) logTv.text = Store.tail(30)
    }

    override fun onResume() {
        super.onResume()
        refresh(true)
        handler.post(ticker)
    }

    override fun onPause() {
        saveServerPrefs()
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
