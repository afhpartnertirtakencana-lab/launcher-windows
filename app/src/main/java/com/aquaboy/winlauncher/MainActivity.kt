package com.aquaboy.winlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CalendarView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal data class AppEntry(val label: String, val pkg: String, val cn: ComponentName) {
    val key: String = cn.flattenToString()
}

internal class QsTile(
    val kind: GlyphView.Kind,
    val label: String,
    val active: () -> Boolean,
    val click: () -> Unit
)

class MainActivity : Activity() {

    private enum class Mode { PINNED, ALL }

    private companion object {
        const val P_NONE = 0
        const val P_START = 1
        const val P_CAL = 2
        const val P_QS = 3
    }

    // ---------- state ----------
    private lateinit var pm: PackageManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var apps: List<AppEntry> = emptyList()
    private var byKey: Map<String, AppEntry> = emptyMap()
    private val icons = HashMap<String, Bitmap>()

    private var startPins: MutableList<String> = mutableListOf()
    private var taskbarPins: MutableList<String> = mutableListOf()
    private var desktopPins: MutableList<String> = mutableListOf()
    private var recents: MutableList<String> = mutableListOf()

    private var useSystemWallpaper = false
    private var mode = Mode.PINNED
    private var query = ""
    private var suppressWatcher = false
    private var panel = P_NONE
    private var seenRev = 0
    private var askedWriteSettings = false

    private var insL = 0
    private var insT = 0
    private var insR = 0
    private var insB = 0
    private var imeH = 0
    private var lastX = 0f
    private var lastY = 0f

    private val tablet: Boolean by lazy { resources.configuration.smallestScreenWidthDp >= 600 }

    // ---------- views ----------
    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: WallpaperView
    private lateinit var desktop: FrameLayout
    private lateinit var scrim: View
    private lateinit var anchor: View

    private lateinit var startPanel: LinearLayout
    private lateinit var startScroll: ScrollView
    private lateinit var startContent: LinearLayout
    private lateinit var searchInput: EditText

    private lateinit var calPanel: ScrollView
    private lateinit var calTime: TextView
    private lateinit var calDate: TextView

    private lateinit var qsPanel: LinearLayout
    private lateinit var qsGrid: LinearLayout
    private lateinit var qsBatt: TextView
    private lateinit var brightSeek: SeekBar
    private lateinit var volSeek: SeekBar

    private lateinit var taskbar: FrameLayout
    private lateinit var center: LinearLayout
    private lateinit var startBtn: FrameLayout
    private lateinit var searchBtn: FrameLayout
    private lateinit var startPill: View
    private lateinit var netGlyph: GlyphView
    private lateinit var volGlyph: GlyphView
    private lateinit var battGlyph: GlyphView
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView

    // ---------- preferensi ----------
    private fun pb(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun pi(k: String, d: Int) = prefs.getInt(k, d)

    private fun tbHeight(): Int = dp(if (tablet) 52 else 48)
    private fun tbIconDp(): Int = intArrayOf(22, 26, 30)[clampInt(pi("tb_size", 1), 0, 2)]
    private fun deskIconDp(): Int = intArrayOf(36, 44, 56)[clampInt(pi("desk_size", 1), 0, 2)]

    private fun load(key: String): MutableList<String> =
        (prefs.getString(key, "") ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()

    private fun save(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString("\n")).apply()
    }

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return if (n.isNullOrBlank()) Build.MODEL else n
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Th.load(this)
        pm = packageManager
        prefs = getSharedPreferences(Th.PREFS, MODE_PRIVATE)
        seenRev = prefs.getInt("rev", 0)
        useSystemWallpaper = pb("syswp", false)
        setupWindow()
        buildUi()
        loadApps()
        refreshAll()
    }

    override fun onStart() {
        super.onStart()
        reg(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        reg(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { updateBattery(it) }
        reg(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerNet()
        updateClock()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(timeReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(battReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        unregisterNet()
        handler.removeCallbacks(reloadApps)
    }

    override fun onResume() {
        super.onResume()
        val rev = prefs.getInt("rev", 0)
        if (rev != seenRev) {
            // Pengaturan berubah (tema, aksen, taskbar, dll.): bangun ulang seluruh tampilan.
            seenRev = rev
            recreate()
            return
        }
        updateClock()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closePanels()
    }

    override fun onBackPressed() {
        // Launcher tidak boleh keluar; tombol Back hanya menutup panel.
        closePanels()
    }

    // ---------- window ----------
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    // ---------- UI construction ----------
    @Suppress("DEPRECATION")
    private fun buildUi() {
        root = FrameLayout(this)

        wallpaperView = WallpaperView(this)
        root.addView(wallpaperView, FrameLayout.LayoutParams(MATCH, MATCH))

        desktop = FrameLayout(this)
        root.addView(desktop, FrameLayout.LayoutParams(MATCH, MATCH))
        setupDesktopInteractions()

        scrim = View(this).apply {
            visibility = View.GONE
            setOnClickListener { closePanels() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        buildStartPanel()
        buildCalendarPanel()
        buildQsPanel()
        buildTaskbar()

        anchor = View(this)
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        setContentView(root)
        applyBarIcons()

        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insL = i.left
                insT = i.top
                insR = i.right
                insB = i.bottom
            } else {
                insL = ins.systemWindowInsetLeft
                insT = ins.systemWindowInsetTop
                insR = ins.systemWindowInsetRight
                insB = ins.systemWindowInsetBottom
            }
            applyLayout()
            ins
        }

        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            root.getWindowVisibleDisplayFrame(r)
            val kb = root.rootView.height - r.bottom
            val newIme = if (kb > insB + dp(120)) kb else 0
            if (newIme != imeH) {
                imeH = newIme
                applyLayout()
            }
        }

        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                applyLayout()
                renderTaskbar()
            }
        }

        applyLayout()
    }

    private fun setupDesktopInteractions() {
        desktop.setOnTouchListener { _, e ->
            lastX = e.rawX
            lastY = e.rawY
            false
        }
        desktop.setOnLongClickListener {
            showDesktopMenu()
            true
        }
        desktop.addOnLayoutChangeListener { _, _, t, _, b, _, oldT, _, oldB ->
            if (b - t != oldB - oldT) renderDesktop()
        }
    }

    private fun buildStartPanel() {
        startPanel = ll(true).apply {
            background = rounded(Th.panel(), dpf(12f), Th.p.stroke, dp(1))
            clipToOutline = true
            elevation = dpf(18f)
            visibility = View.GONE
        }

        searchInput = EditText(this).apply {
            hint = "Ketik di sini untuk mencari"
            setHintTextColor(Th.p.faint)
            setTextColor(Th.p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), 0, dp(8), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatcher) return
                    query = s?.toString() ?: ""
                    renderStart()
                }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    runFirstResult()
                    true
                } else {
                    false
                }
            }
        }

        val searchBox = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.input, dpf(20f), Th.p.stroke, dp(1))
            setPadding(dp(14), 0, dp(12), 0)
            addView(icon(GlyphView.Kind.SEARCH, Th.p.dim), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(searchInput, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        startPanel.addView(
            searchBox,
            LinearLayout.LayoutParams(MATCH, dp(42)).apply { setMargins(dp(16), dp(16), dp(16), dp(8)) }
        )

        startScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        startContent = ll(true)
        startScroll.addView(startContent, FrameLayout.LayoutParams(MATCH, WRAP))
        startPanel.addView(startScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val name = deviceName()
        val footer = ll(false, Gravity.CENTER_VERTICAL).apply {
            setBackgroundColor(if (Th.dark) 0x33000000 else 0x14000000)
            setPadding(dp(20), 0, dp(12), 0)
        }
        val userBtn = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = ripple(dpf(8f))
            setPadding(dp(6), dp(6), dp(12), dp(6))
            setOnClickListener { openSettingsApp() }
        }
        val avatar = tv(name.take(1).uppercase(), 13f, Th.onAccent, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Th.accent, dpf(16f))
        }
        userBtn.addView(avatar, LinearLayout.LayoutParams(dp(32), dp(32)))
        userBtn.addView(
            tv(name, 13f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )
        footer.addView(userBtn, LinearLayout.LayoutParams(0, WRAP, 1f))

        footer.addView(footerIcon(GlyphView.Kind.GEAR) { openSettingsApp() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        footer.addView(footerIcon(GlyphView.Kind.POWER) { showPowerMenu(it) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        startPanel.addView(footer, LinearLayout.LayoutParams(MATCH, dp(60)))

        root.addView(startPanel, FrameLayout.LayoutParams(dp(320), dp(400)))
    }

    private fun footerIcon(kind: GlyphView.Kind, onClick: (View) -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = ripple(dpf(8f))
            addView(icon(kind), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { onClick(it) }
        }

    private fun buildCalendarPanel() {
        calTime = tv("", 40f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        calDate = tv("", 14f, Th.p.dim)
        val cal = CalendarView(Th.themed(this)).apply { firstDayOfWeek = Calendar.MONDAY }

        val inner = ll(true).apply { setPadding(dp(20), dp(16), dp(20), dp(12)) }
        inner.addView(calTime)
        inner.addView(calDate)
        inner.addView(cal, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        calPanel = ScrollView(this).apply {
            background = rounded(Th.panel(), dpf(12f), Th.p.stroke, dp(1))
            clipToOutline = true
            elevation = dpf(18f)
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(calPanel, FrameLayout.LayoutParams(dp(330), WRAP))
    }

    private fun buildQsPanel() {
        qsPanel = ll(true).apply {
            background = rounded(Th.panel(), dpf(12f), Th.p.stroke, dp(1))
            setPadding(dp(16), dp(16), dp(16), dp(10))
            clipToOutline = true
            elevation = dpf(18f)
            visibility = View.GONE
        }
        qsGrid = ll(true)
        qsPanel.addView(qsGrid, LinearLayout.LayoutParams(MATCH, WRAP))

        brightSeek = SeekBar(this)
        volSeek = SeekBar(this)
        qsPanel.addView(
            sliderRow(GlyphView.Kind.SUN, brightSeek),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
        )
        qsPanel.addView(
            sliderRow(GlyphView.Kind.VOLUME, volSeek),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        )

        brightSeek.max = 255
        brightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(value)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        volSeek.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val foot = ll(false, Gravity.CENTER_VERTICAL)
        qsBatt = tv("", 13f, Th.p.dim)
        foot.addView(qsBatt, LinearLayout.LayoutParams(0, WRAP, 1f))
        foot.addView(footerIcon(GlyphView.Kind.GEAR) { openSettingsApp() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        qsPanel.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        root.addView(qsPanel, FrameLayout.LayoutParams(dp(360), WRAP))
    }

    private fun sliderRow(kind: GlyphView.Kind, bar: SeekBar): View {
        bar.progressTintList = ColorStateList.valueOf(Th.accent)
        bar.thumbTintList = ColorStateList.valueOf(Th.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Th.blend(Th.p.faint, Th.p.bg, 0.4f))
        val row = ll(false, Gravity.CENTER_VERTICAL)
        row.addView(icon(kind), LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(10) })
        return row
    }

    private fun glyphButton(g: GlyphView, onClick: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = ripple(dpf(6f))
            addView(g, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { onClick() }
        }

    private fun buildTaskbar() {
        val tbH = tbHeight()
        taskbar = FrameLayout(this).apply {
            setBackgroundColor(Th.p.taskbar)
            isClickable = true
        }

        center = ll(false, Gravity.CENTER_VERTICAL)
        startBtn = glyphButton(icon(GlyphView.Kind.START)) { toggleStart() }
        startPill = View(this).apply {
            background = rounded(Th.accent, dpf(2f))
            visibility = View.INVISIBLE
        }
        startBtn.addView(startPill, FrameLayout.LayoutParams(dp(12), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(1)
        })
        searchBtn = glyphButton(icon(GlyphView.Kind.SEARCH)) { openStart(true) }

        netGlyph = icon(GlyphView.Kind.NET)
        volGlyph = icon(GlyphView.Kind.VOLUME)
        battGlyph = icon(GlyphView.Kind.BATTERY)

        val trayIcons = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(8), 0, dp(8), 0)
            background = ripple(dpf(6f))
            addView(netGlyph, LinearLayout.LayoutParams(dp(18), dp(18)))
            if (tablet) addView(volGlyph, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(8) })
            addView(battGlyph, LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) })
            setOnClickListener { toggleQs() }
        }

        timeText = tv("", 12f).apply { gravity = Gravity.END }
        dateText = tv("", 12f).apply { gravity = Gravity.END }
        val clock = ll(true, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(8), 0, dp(10), 0)
            background = ripple(dpf(6f))
            addView(timeText)
            if (tablet && pb("tb_date", true)) addView(dateText)
            setOnClickListener { toggleCalendar() }
        }

        val trayWrap = ll(false, Gravity.END or Gravity.CENTER_VERTICAL)
        trayWrap.addView(trayIcons, LinearLayout.LayoutParams(WRAP, dp(40)))
        trayWrap.addView(clock, LinearLayout.LayoutParams(WRAP, dp(40)))

        val bar = ll(false, Gravity.CENTER_VERTICAL)
        if ((pi("tb_center", if (tablet) 1 else 0) == 1)) {
            bar.addView(Space(this), LinearLayout.LayoutParams(0, MATCH, 1f))
            bar.addView(center, LinearLayout.LayoutParams(WRAP, MATCH))
            bar.addView(trayWrap, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else {
            bar.addView(center, LinearLayout.LayoutParams(0, MATCH, 1f))
            bar.addView(trayWrap, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        taskbar.addView(bar, FrameLayout.LayoutParams(MATCH, tbH, Gravity.TOP))

        val divider = View(this).apply { setBackgroundColor(Th.p.stroke) }
        taskbar.addView(divider, FrameLayout.LayoutParams(MATCH, 1, Gravity.TOP))

        root.addView(taskbar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    // ---------- layout ----------
    private fun applyLayout() {
        if (!::taskbar.isInitialized || !::startPanel.isInitialized ||
            !::calPanel.isInitialized || !::qsPanel.isInitialized
        ) return
        val dm = resources.displayMetrics
        val rw = if (root.width > 0) root.width else dm.widthPixels
        val rh = if (root.height > 0) root.height else dm.heightPixels
        val tbH = tbHeight()

        taskbar.setPadding(insL, 0, insR, insB)

        (desktop.layoutParams as FrameLayout.LayoutParams).setMargins(
            insL + dp(4), insT + dp(8), insR, tbH + insB
        )
        desktop.requestLayout()

        val availW = rw - insL - insR
        val baseBottom = tbH + insB + dp(12)
        val bottom = max(baseBottom, if (imeH > 0) imeH + dp(8) else 0)

        val pw = if (tablet) min(dp(640), availW - dp(24)) else availW - dp(16)
        val ph = min(dp(if (tablet) 720 else 660), rh - insT - bottom - dp(8))
        startPanel.layoutParams = FrameLayout.LayoutParams(pw, ph).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = bottom
            leftMargin = insL
            rightMargin = insR
        }

        val cw = min(dp(340), availW - dp(16))
        calPanel.layoutParams = FrameLayout.LayoutParams(cw, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = baseBottom
            rightMargin = insR + dp(8)
        }

        val qw = min(dp(380), availW - dp(16))
        qsPanel.layoutParams = FrameLayout.LayoutParams(qw, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = baseBottom
            rightMargin = insR + dp(8)
        }
    }

    // ---------- data ----------
    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = ArrayList<AppEntry>()
        val selfMain = MainActivity::class.java.name
        for (ri in pm.queryIntentActivities(i, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == packageName && ai.name == selfMain) continue
            val label = ri.loadLabel(pm).toString()
            out.add(AppEntry(label, ai.packageName, ComponentName(ai.packageName, ai.name)))
        }
        apps = out.sortedBy { it.label.lowercase() }
        byKey = apps.associateBy { it.key }
        icons.clear()
        ensureDefaults()
    }

    private fun defaultPackages(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(Settings.ACTION_SETTINGS),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media"), "image/*"),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
            Intent(AlarmClock.ACTION_SHOW_ALARMS)
        )
        val result = ArrayList<String>()
        for (probe in intents) {
            try {
                val p = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                    .firstOrNull()?.activityInfo?.packageName
                if (p != null && p != "android" && p != packageName && p !in result) result.add(p)
            } catch (e: Exception) {
                // abaikan intent yang gagal di-resolve
            }
        }
        return result
    }

    private fun ensureDefaults() {
        if (!prefs.contains("start")) resetPins()
        val keys = byKey.keys
        startPins = load("start").filter { it in keys }.toMutableList()
        taskbarPins = load("taskbar").filter { it in keys }.toMutableList()
        desktopPins = load("desktop").filter { it in keys }.toMutableList()
        recents = load("recents").filter { it in keys }.toMutableList()
    }

    private fun resetPins() {
        val own = apps.filter { it.pkg == packageName }
            .sortedBy { if (it.cn.className.endsWith("FileManagerActivity")) 0 else 1 }
        val defApps = defaultPackages()
            .mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
            .distinctBy { it.key }
        val start = ArrayList<String>()
        for (a in own + defApps) if (a.key !in start) start.add(a.key)
        for (a in apps) {
            if (start.size >= 12) break
            if (a.key !in start) start.add(a.key)
        }
        save("start", start)
        save("taskbar", (own + defApps).take(6).map { it.key })
        save("desktop", (own.take(1) + defApps.take(2)).map { it.key })
        prefs.edit().remove("recents").apply()
    }

    private fun iconOf(app: AppEntry): Bitmap = icons.getOrPut(app.key) {
        val size = dp(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = try {
            pm.getActivityIcon(app.cn)
        } catch (e: Exception) {
            pm.defaultActivityIcon
        }
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        bmp
    }

    private fun launch(app: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka ${app.label}", Toast.LENGTH_SHORT).show()
        }
        recents.remove(app.key)
        recents.add(0, app.key)
        while (recents.size > 6) recents.removeAt(recents.size - 1)
        save("recents", recents)
        closePanels()
        renderTaskbar()
    }

    private fun togglePin(list: MutableList<String>, key: String, prefKey: String) {
        if (!list.remove(key)) list.add(key)
        save(prefKey, list)
        renderTaskbar()
        renderDesktop()
        renderStart()
    }

    private fun refreshAll() {
        applyWallpaper()
        renderTaskbar()
        renderDesktop()
        renderStart()
    }

    private fun applyWallpaper() {
        wallpaperView.visibility = if (useSystemWallpaper) View.GONE else View.VISIBLE
    }

    private fun openSettingsApp() {
        closePanels()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------- taskbar ----------
    private fun renderTaskbar() {
        if (!::center.isInitialized) return
        center.removeAllViews()
        val btnLp = { LinearLayout.LayoutParams(dp(44), dp(40)) }
        center.addView(startBtn, btnLp())
        val showSearch = pb("tb_search", true)
        if (showSearch) center.addView(searchBtn, btnLp())

        val dm = resources.displayMetrics
        val rw = if (root.width > 0) root.width else dm.widthPixels
        val availDp = (rw - insL - insR) / dm.density
        val trayDp = if (tablet) 170f else 110f
        val centered = (pi("tb_center", if (tablet) 1 else 0) == 1)
        val room = if (centered) availDp - 2 * trayDp else availDp - trayDp
        val fixed = if (showSearch) 2 else 1
        val maxIcons = max(0, ((room - fixed * 44f) / 44f).toInt())

        for (app in taskbarPins.mapNotNull { byKey[it] }.take(maxIcons)) {
            center.addView(taskIcon(app), btnLp())
        }
    }

    private fun taskIcon(app: AppEntry): View =
        FrameLayout(this).apply {
            background = ripple(dpf(6f))
            val s = dp(tbIconDp())
            addView(
                ImageView(this@MainActivity).apply { setImageBitmap(iconOf(app)) },
                FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            )
            if (recents.take(3).contains(app.key)) {
                addView(
                    View(this@MainActivity).apply { background = rounded(Th.p.faint, dpf(2f)) },
                    FrameLayout.LayoutParams(dp(8), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                        bottomMargin = dp(1)
                    }
                )
            }
            setOnClickListener { launch(app) }
            setOnLongClickListener {
                showAppMenu(this, app)
                true
            }
        }

    // ---------- desktop ----------
    private fun renderDesktop() {
        if (!::desktop.isInitialized) return
        desktop.removeAllViews()
        if (!pb("desk_show", true)) return
        val h = desktop.height
        if (h <= 0) return
        val big = deskIconDp()
        val cellW = dp(big + 44)
        val cellH = dp(big + 52)
        val rows = max(1, h / cellH)
        desktopPins.mapNotNull { byKey[it] }.forEachIndexed { idx, app ->
            val col = idx / rows
            val row = idx % rows
            val lp = FrameLayout.LayoutParams(cellW, cellH)
            lp.leftMargin = col * cellW
            lp.topMargin = row * cellH
            desktop.addView(desktopIcon(app, big), lp)
        }
    }

    private fun desktopIcon(app: AppEntry, sizeDp: Int): View {
        val v = ll(true, Gravity.CENTER_HORIZONTAL).apply {
            setPadding(dp(4), dp(8), dp(4), dp(4))
            background = ripple(dpf(6f))
        }
        v.addView(
            ImageView(this).apply { setImageBitmap(iconOf(app)) },
            LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        )
        // Latar belakang wallpaper bisa terang atau gelap, jadi label memakai bayangan.
        val t = tv(app.label, 12f, 0xFFFFFFFF.toInt()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        v.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        v.setOnClickListener { launch(app) }
        v.setOnLongClickListener {
            showAppMenu(v, app)
            true
        }
        return v
    }

    // ---------- start menu ----------
    private fun renderStart() {
        if (!::startContent.isInitialized) return
        startContent.removeAllViews()
        val q = query.trim()
        when {
            q.isNotEmpty() -> renderSearch(q)
            mode == Mode.ALL -> renderAll()
            else -> renderPinned()
        }
        startScroll.scrollTo(0, 0)
    }

    private fun headerRow(title: String, action: String?, onAction: (() -> Unit)?): View {
        val row = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(24), dp(10), dp(16), dp(4)) }
        row.addView(tv(title, 14f, Th.p.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (action != null) {
            val b = tv(action, 12f).apply {
                background = ripple(dpf(6f), Th.p.cardHover)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onAction?.invoke() }
            }
            row.addView(b)
        }
        return row
    }

    private fun renderPinned() {
        startContent.addView(headerRow("Disematkan", "Semua aplikasi  ›") {
            mode = Mode.ALL
            renderStart()
        })
        val cols = pi("start_cols", 0).let { if (it == 0) (if (tablet) 6 else 4) else it }
        val pinned = startPins.mapNotNull { byKey[it] }
        if (pinned.isEmpty()) {
            startContent.addView(tv("Belum ada aplikasi yang disematkan.", 12f, Th.p.dim).apply {
                setPadding(dp(24), dp(8), dp(24), dp(16))
            })
        } else {
            startContent.addView(gridOf(pinned, cols) { tile(it) })
        }

        if (pb("start_recents", true)) {
            startContent.addView(headerRow("Direkomendasikan", null, null))
            val rec = recents.mapNotNull { byKey[it] }
            if (rec.isEmpty()) {
                startContent.addView(tv("Aplikasi yang baru dibuka akan muncul di sini.", 12f, Th.p.dim).apply {
                    setPadding(dp(24), dp(8), dp(24), dp(16))
                })
            } else {
                startContent.addView(gridOf(rec, 2) { recentCell(it) })
            }
        }
    }

    private fun renderAll() {
        startContent.addView(headerRow("Semua aplikasi", "‹  Kembali") {
            mode = Mode.PINNED
            renderStart()
        })
        var last = ""
        for (a in apps) {
            val l = letterOf(a)
            if (l != last) {
                last = l
                startContent.addView(tv(l, 13f, Th.accent, true).apply { setPadding(dp(24), dp(12), 0, dp(4)) })
            }
            startContent.addView(listRow(a))
        }
        startContent.addView(Space(this), LinearLayout.LayoutParams(MATCH, dp(12)))
    }

    private fun searchResults(q: String): List<AppEntry> {
        val ql = q.lowercase()
        return apps
            .filter { it.label.lowercase().contains(ql) }
            .sortedWith(compareBy({ !it.label.lowercase().startsWith(ql) }, { it.label.lowercase() }))
    }

    private fun renderSearch(q: String) {
        val res = searchResults(q)
        startContent.addView(headerRow("Aplikasi", null, null))
        if (res.isEmpty()) {
            startContent.addView(tv("Tidak ada aplikasi yang cocok.", 12f, Th.p.dim).apply {
                setPadding(dp(24), dp(4), dp(24), dp(8))
            })
        } else {
            for (a in res) startContent.addView(listRow(a))
        }
        startContent.addView(headerRow("Web", null, null))
        val web = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(dpf(6f))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(dp(8), 0, dp(8), 0) }
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Tidak ada peramban", Toast.LENGTH_SHORT).show()
                }
                closePanels()
            }
        }
        web.addView(icon(GlyphView.Kind.SEARCH), LinearLayout.LayoutParams(dp(20), dp(20)))
        web.addView(tv("Cari \"$q\" di web", 14f).apply { setPadding(dp(14), 0, 0, 0) })
        startContent.addView(web)
        startContent.addView(Space(this), LinearLayout.LayoutParams(MATCH, dp(12)))
    }

    private fun runFirstResult() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchResults(q).firstOrNull()?.let { launch(it) }
    }

    private fun letterOf(a: AppEntry): String {
        val ch = a.label.trim().firstOrNull()?.uppercaseChar()
        return if (ch != null && ch.isLetter()) ch.toString() else "#"
    }

    private fun gridOf(items: List<AppEntry>, cols: Int, cell: (AppEntry) -> View): View {
        val wrap = ll(true).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        for (rowItems in items.chunked(cols)) {
            val row = ll(false)
            for (i in 0 until cols) {
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                row.addView(if (i < rowItems.size) cell(rowItems[i]) else Space(this), lp)
            }
            wrap.addView(row)
        }
        return wrap
    }

    private fun tile(app: AppEntry): View {
        val v = ll(true, Gravity.CENTER_HORIZONTAL).apply {
            setPadding(dp(4), dp(10), dp(4), dp(8))
            background = ripple(dpf(8f))
        }
        v.addView(
            ImageView(this).apply { setImageBitmap(iconOf(app)) },
            LinearLayout.LayoutParams(dp(38), dp(38))
        )
        val t = tv(app.label, 12f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        v.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        v.setOnClickListener { launch(app) }
        v.setOnLongClickListener {
            showAppMenu(v, app)
            true
        }
        return v
    }

    private fun recentCell(app: AppEntry): View {
        val v = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ripple(dpf(8f))
        }
        v.addView(
            ImageView(this).apply { setImageBitmap(iconOf(app)) },
            LinearLayout.LayoutParams(dp(28), dp(28))
        )
        v.addView(
            tv(app.label, 13f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        v.setOnClickListener { launch(app) }
        v.setOnLongClickListener {
            showAppMenu(v, app)
            true
        }
        return v
    }

    private fun listRow(app: AppEntry): View {
        val v = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(dpf(8f))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(dp(8), 0, dp(8), 0) }
        }
        v.addView(
            ImageView(this).apply { setImageBitmap(iconOf(app)) },
            LinearLayout.LayoutParams(dp(26), dp(26))
        )
        v.addView(
            tv(app.label, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        v.setOnClickListener { launch(app) }
        v.setOnLongClickListener {
            showAppMenu(v, app)
            true
        }
        return v
    }

    // ---------- menus ----------
    private fun showAppMenu(anchorView: View, app: AppEntry) {
        val pop = PopupMenu(Th.themed(this), anchorView)
        val m = pop.menu
        m.add(0, 1, 0, if (app.key in startPins) "Lepas dari Start" else "Sematkan ke Start")
        m.add(0, 2, 0, if (app.key in taskbarPins) "Lepas dari taskbar" else "Sematkan ke taskbar")
        m.add(0, 3, 0, if (app.key in desktopPins) "Hapus dari desktop" else "Tambahkan ke desktop")
        if (app.pkg != packageName) {
            m.add(0, 4, 0, "Info aplikasi")
            m.add(0, 5, 0, "Copot pemasangan")
        }
        pop.setOnMenuItemClickListener { item ->
            try {
                when (item.itemId) {
                    1 -> togglePin(startPins, app.key, "start")
                    2 -> togglePin(taskbarPins, app.key, "taskbar")
                    3 -> togglePin(desktopPins, app.key, "desktop")
                    4 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                    )
                    5 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null)))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Aksi tidak tersedia", Toast.LENGTH_SHORT).show()
            }
            true
        }
        pop.show()
    }

    private fun showDesktopMenu() {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val lp = anchor.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = (lastX - loc[0]).toInt()
        lp.topMargin = (lastY - loc[1]).toInt()
        anchor.layoutParams = lp

        val pop = PopupMenu(Th.themed(this), anchor)
        val m = pop.menu
        m.add(0, 1, 0, if (useSystemWallpaper) "Gunakan wallpaper bawaan" else "Gunakan wallpaper sistem")
        m.add(0, 2, 0, "Ganti wallpaper sistem…")
        m.add(0, 3, 0, if (Th.dark) "Beralih ke mode terang" else "Beralih ke mode gelap")
        m.add(0, 4, 0, "Personalisasi")
        m.add(0, 5, 0, "Segarkan")
        pop.setOnMenuItemClickListener { item ->
            try {
                when (item.itemId) {
                    1 -> {
                        useSystemWallpaper = !useSystemWallpaper
                        prefs.edit().putBoolean("syswp", useSystemWallpaper).apply()
                        applyWallpaper()
                    }
                    2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Pilih wallpaper"))
                    3 -> toggleDark()
                    4 -> startActivity(
                        Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "personalize")
                    )
                    5 -> {
                        loadApps()
                        refreshAll()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Aksi tidak tersedia", Toast.LENGTH_SHORT).show()
            }
            true
        }
        pop.show()
    }

    private fun showPowerMenu(anchorView: View) {
        val pop = PopupMenu(Th.themed(this), anchorView)
        val m = pop.menu
        m.add(0, 1, 0, "Pengaturan")
        m.add(0, 2, 0, "Pengaturan Android")
        m.add(0, 3, 0, "Ganti launcher bawaan")
        m.add(0, 4, 0, "Muat ulang launcher")
        pop.setOnMenuItemClickListener { item ->
            try {
                when (item.itemId) {
                    1 -> openSettingsApp()
                    2 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                    3 -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    4 -> {
                        loadApps()
                        refreshAll()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Aksi tidak tersedia", Toast.LENGTH_SHORT).show()
            }
            true
        }
        pop.show()
    }

    private fun toggleDark() {
        prefs.edit().putInt("theme_mode", if (Th.dark) 1 else 2).apply()
        Th.bump(this)
        seenRev = prefs.getInt("rev", 0)
        recreate()
    }

    // ---------- panels ----------
    private fun panelView(id: Int): View? = when (id) {
        P_START -> startPanel
        P_CAL -> calPanel
        P_QS -> qsPanel
        else -> null
    }

    private fun showPanel(p: View) {
        p.animate().cancel()
        p.visibility = View.VISIBLE
        if (!Th.animations) {
            p.alpha = 1f
            p.translationY = 0f
            return
        }
        p.alpha = 0f
        p.translationY = dpf(28f)
        p.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hidePanel(p: View) {
        if (p.visibility != View.VISIBLE) return
        p.animate().cancel()
        if (!Th.animations) {
            p.visibility = View.GONE
            return
        }
        p.animate().alpha(0f).translationY(dpf(20f)).setDuration(130).withEndAction {
            p.visibility = View.GONE
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun setPanel(id: Int) {
        if (id == panel) return
        if (panel == P_START) {
            hideKeyboard()
            searchInput.clearFocus()
        }
        panelView(panel)?.let { hidePanel(it) }
        panel = id
        scrim.visibility = if (id == P_NONE) View.GONE else View.VISIBLE
        panelView(id)?.let { showPanel(it) }
        startPill.visibility = if (id == P_START) View.VISIBLE else View.INVISIBLE
    }

    private fun prepareStart() {
        suppressWatcher = true
        searchInput.setText("")
        suppressWatcher = false
        query = ""
        mode = Mode.PINNED
        renderStart()
    }

    private fun toggleStart() {
        if (panel == P_START) {
            closePanels()
        } else {
            prepareStart()
            setPanel(P_START)
        }
    }

    private fun openStart(focusSearch: Boolean) {
        if (panel != P_START) {
            prepareStart()
            setPanel(P_START)
        }
        if (focusSearch) {
            startPanel.postDelayed({
                searchInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }, 220)
        }
    }

    private fun toggleCalendar() {
        if (panel == P_CAL) {
            closePanels()
        } else {
            updateClock()
            setPanel(P_CAL)
        }
    }

    private fun toggleQs() {
        if (panel == P_QS) {
            closePanels()
        } else {
            renderQs()
            setPanel(P_QS)
        }
    }

    private fun closePanels() {
        if (!::startPanel.isInitialized) return
        setPanel(P_NONE)
    }

    // ---------- quick settings ----------
    private fun openSys(action: String) {
        try {
            startActivity(Intent(action))
            closePanels()
        } catch (e: Exception) {
            Toast.makeText(this, "Pengaturan tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun airplaneOn(): Boolean = try {
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (e: Exception) {
        false
    }

    private fun powerSave(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (e: Exception) {
        false
    }

    private fun locationOn(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) {
            (getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    private fun qsTiles(): List<QsTile> = listOf(
        QsTile(GlyphView.Kind.NET, "Wi-Fi", { netGlyph.net == 0 }) {
            openSys(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS)
        },
        QsTile(GlyphView.Kind.BLUETOOTH, "Bluetooth", { false }) { openSys(Settings.ACTION_BLUETOOTH_SETTINGS) },
        QsTile(GlyphView.Kind.AIRPLANE, "Pesawat", { airplaneOn() }) { openSys(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
        QsTile(GlyphView.Kind.BATTERY, "Hemat daya", { powerSave() }) { openSys(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
        QsTile(GlyphView.Kind.MOON, "Mode gelap", { Th.dark }) { toggleDark() },
        QsTile(GlyphView.Kind.PIN, "Lokasi", { locationOn() }) { openSys(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        QsTile(GlyphView.Kind.CAST, "Cast", { false }) { openSys(Settings.ACTION_CAST_SETTINGS) },
        QsTile(GlyphView.Kind.ACCESS, "Aksesibilitas", { false }) { openSys(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
    )

    private fun renderQs() {
        qsGrid.removeAllViews()
        for (rowTiles in qsTiles().chunked(4)) {
            val row = ll(false)
            for (t in rowTiles) {
                val on = t.active()
                val cell = ll(true, Gravity.CENTER).apply {
                    background = if (on) rounded(Th.accent, dpf(8f)) else rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
                    setPadding(dp(4), dp(10), dp(4), dp(8))
                    setOnClickListener { t.click() }
                }
                cell.addView(
                    icon(t.kind, if (on) Th.onAccent else Th.p.text),
                    LinearLayout.LayoutParams(dp(22), dp(22))
                )
                cell.addView(
                    tv(t.label, 11f, if (on) Th.onAccent else Th.p.dim).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    },
                    LinearLayout.LayoutParams(MATCH, WRAP)
                )
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            for (i in rowTiles.size until 4) row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            qsGrid.addView(row)
        }
        syncSliders()
    }

    private fun syncSliders() {
        val b = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        brightSeek.progress = clampInt(b, 0, 255)
        volSeek.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun setBrightness(v: Int) {
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, clampInt(v, 1, 255))
            } catch (e: Exception) {
                // abaikan
            }
        } else if (!askedWriteSettings) {
            askedWriteSettings = true
            Toast.makeText(this, "Izinkan perubahan pengaturan sistem untuk mengatur kecerahan", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    // ---------- clock / battery / network ----------
    private fun updateClock() {
        if (!::timeText.isInitialized) return
        val now = Date()
        val time = DateFormat.getTimeFormat(this).format(now)
        timeText.text = time
        dateText.text = DateFormat.getDateFormat(this).format(now)
        calTime.text = time
        calDate.text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
    }

    private fun updateBattery(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (lvl >= 0 && scale > 0) {
            val pct = lvl * 100 / scale
            battGlyph.level = pct
            qsBatt.text = "Baterai $pct%" + if (charging) "  •  mengisi daya" else ""
        }
        battGlyph.charging = charging
    }

    private fun reg(r: BroadcastReceiver, f: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(r, f)
        }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateClock()
        }
    }

    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) updateBattery(intent)
        }
    }

    private val reloadApps = Runnable {
        loadApps()
        refreshAll()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(reloadApps)
            handler.postDelayed(reloadApps, 600)
        }
    }

    private var netRegistered = false

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val m = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 2
            }
            runOnUiThread { netGlyph.net = m }
        }

        override fun onLost(network: Network) {
            runOnUiThread { netGlyph.net = 2 }
        }
    }

    private fun registerNet() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            if (cm.activeNetwork == null) netGlyph.net = 2
            cm.registerDefaultNetworkCallback(netCb)
            netRegistered = true
        } catch (e: Exception) {
            netRegistered = false
        }
    }

    private fun unregisterNet() {
        if (!netRegistered) return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb)
        } catch (e: Exception) {
            // sudah dilepas
        }
        netRegistered = false
    }
}
