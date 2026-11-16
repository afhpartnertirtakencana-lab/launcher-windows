package com.aquaboy.winlauncher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Sistem tema: palet Azure terang/gelap + warna aksen yang bisa dipilih. */
object Th {
    const val PREFS = "winlauncher"

    class Pal(
        val dark: Boolean,
        val bg: Int,
        val card: Int,
        val cardHover: Int,
        val panel: Int,
        val taskbar: Int,
        val text: Int,
        val dim: Int,
        val faint: Int,
        val stroke: Int,
        val ripple: Int,
        val input: Int
    )

    class Accent(val name: String, val light: Int, val dark: Int)

    private fun c(v: Long): Int = v.toInt()

    val DARK = Pal(
        true,
        c(0xFF1B1F27), c(0xFF262B35), c(0xFF2F3541), c(0xF2202630), c(0xE61A1E26),
        c(0xFFFFFFFF), c(0xFFC5CBD6), c(0xFF8A93A3), c(0x22FFFFFF), c(0x33FFFFFF), c(0xFF2B313C)
    )

    val LIGHT = Pal(
        false,
        c(0xFFEEF3FA), c(0xFFFFFFFF), c(0xFFF3F7FC), c(0xF2F5F8FD), c(0xE6EBF1FA),
        c(0xFF14181F), c(0xFF4A5565), c(0xFF7A8696), c(0x1A000000), c(0x22000000), c(0xFFFFFFFF)
    )

    val ACCENTS = listOf(
        Accent("Azure", c(0xFF0078D4), c(0xFF4CC2FF)),
        Accent("Cyan", c(0xFF0A7E8C), c(0xFF4DD6E8)),
        Accent("Teal", c(0xFF038387), c(0xFF4DD0C8)),
        Accent("Indigo", c(0xFF4F52B2), c(0xFF9FA8FF)),
        Accent("Ungu", c(0xFF8764B8), c(0xFFC8A6F0)),
        Accent("Mawar", c(0xFFC30052), c(0xFFFF7AA8)),
        Accent("Oranye", c(0xFFCA5010), c(0xFFFFAA66)),
        Accent("Hijau", c(0xFF107C10), c(0xFF6CCB5F)),
        Accent("Grafit", c(0xFF5D5A58), c(0xFFB5B8BD))
    )

    lateinit var p: Pal
    var accent: Int = c(0xFF0078D4)
    var onAccent: Int = Color.WHITE
    var accentIdx = 0
    var mode = 0            // 0 = ikuti sistem, 1 = terang, 2 = gelap
    var transparency = true
    var animations = true
    val danger: Int = c(0xFFE5484D)
    val good: Int = c(0xFF3FB950)

    val dark: Boolean get() = p.dark

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = sp.getInt("theme_mode", 0)
        accentIdx = sp.getInt("accent_idx", 0).coerceIn(0, ACCENTS.size - 1)
        transparency = sp.getBoolean("transparency", true)
        animations = sp.getBoolean("animations", true)
        val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (mode) {
            1 -> false
            2 -> true
            else -> sysDark
        }
        p = if (isDark) DARK else LIGHT
        val a = ACCENTS[accentIdx]
        accent = if (isDark) a.dark else a.light
        onAccent = if (isDark) Color.BLACK else Color.WHITE
    }

    /** Naikkan nomor revisi supaya launcher memuat ulang tampilannya. */
    fun bump(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putInt("rev", sp.getInt("rev", 0) + 1).apply()
    }

    fun panel(): Int = if (transparency) p.panel else (p.panel or 0xFF000000.toInt())

    fun blend(a: Int, b: Int, t: Float): Int {
        val ar = Color.alpha(a); val rr = Color.red(a); val gg = Color.green(a); val bb = Color.blue(a)
        val br = Color.alpha(b); val r2 = Color.red(b); val g2 = Color.green(b); val b2 = Color.blue(b)
        return Color.argb(
            (ar + (br - ar) * t).toInt(),
            (rr + (r2 - rr) * t).toInt(),
            (gg + (g2 - gg) * t).toInt(),
            (bb + (b2 - bb) * t).toInt()
        )
    }

    fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    /** Context bertema gelap/terang untuk widget bawaan (popup menu, kalender). */
    fun themed(ctx: Context): Context = ContextThemeWrapper(
        ctx,
        if (p.dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light
    )
}

// ---------- helper Context ----------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeW: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

fun Context.ripple(radius: Float = dpf(6f), base: Int = 0): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = radius
    }
    val content = if (base != 0) rounded(base, radius) else null
    return RippleDrawable(ColorStateList.valueOf(Th.p.ripple), content, mask)
}

fun Context.ll(vertical: Boolean = false, grav: Int = Gravity.NO_GRAVITY): LinearLayout =
    LinearLayout(this).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = grav
    }

fun Context.tv(text: String, sp: Float, color: Int = Th.p.text, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
    }

fun Context.icon(kind: GlyphView.Kind, tint: Int? = null): GlyphView =
    GlyphView(this, kind).also { it.tint = tint }

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i])
    else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))

// ---------- komponen ----------

fun Context.textButton(text: String, primary: Boolean, onClick: () -> Unit): TextView =
    tv(text, 14f, if (primary) Th.accent else Th.p.text, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = ripple(dpf(6f))
        setOnClickListener { onClick() }
    }

fun Context.themedSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch {
    val s = Switch(this)
    s.isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    s.thumbTintList = ColorStateList(states, intArrayOf(Th.onAccent, Th.p.dim))
    s.trackTintList = ColorStateList(states, intArrayOf(Th.accent, Th.blend(Th.p.faint, Th.p.bg, 0.5f)))
    s.setOnCheckedChangeListener { _, v -> onChange(v) }
    return s
}

/** Kontrol segmen (mis. Terang | Gelap | Sistem). */
fun Context.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    val box = ll(false, Gravity.CENTER_VERTICAL).apply {
        background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    val labels = ArrayList<TextView>()
    fun paint(sel: Int) {
        for ((i, t) in labels.withIndex()) {
            val on = i == sel
            t.setTextColor(if (on) Th.onAccent else Th.p.text)
            t.background = if (on) rounded(Th.accent, dpf(6f)) else ripple(dpf(6f))
        }
    }
    for ((i, o) in options.withIndex()) {
        val t = tv(o, 13f, Th.p.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener {
                paint(i)
                onSelect(i)
            }
        }
        labels.add(t)
        box.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    paint(selected)
    return box
}

fun Activity.showDialog(
    title: String,
    body: View?,
    okText: String?,
    cancelText: String? = "Batal",
    onOk: (() -> Unit)? = null
): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = ll(true).apply {
        background = rounded(Th.p.card, dpf(12f), Th.p.stroke, dp(1))
        setPadding(dp(24), dp(22), dp(24), dp(12))
    }
    box.addView(tv(title, 18f, Th.p.text, true))
    if (body != null) {
        box.addView(body, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
    }
    val row = ll(false, Gravity.END)
    if (cancelText != null) row.addView(textButton(cancelText, false) { d.dismiss() })
    if (okText != null) {
        row.addView(textButton(okText, true) {
            d.dismiss()
            onOk?.invoke()
        })
    }
    box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
    d.setContentView(box)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.show()
    val dm = resources.displayMetrics
    d.window?.setLayout(min(dp(440), dm.widthPixels - dp(32)), WRAP)
    return d
}

fun Activity.messageDialog(title: String, message: String, okText: String = "Tutup") {
    val t = tv(message, 14f, Th.p.dim).apply { setLineSpacing(0f, 1.15f) }
    showDialog(title, t, okText, null)
}

fun Activity.confirmDialog(title: String, message: String, okText: String, onOk: () -> Unit) {
    val t = tv(message, 14f, Th.p.dim).apply { setLineSpacing(0f, 1.15f) }
    showDialog(title, t, okText, "Batal", onOk)
}

fun Activity.promptDialog(
    title: String,
    initial: String,
    hint: String,
    okText: String,
    onOk: (String) -> Unit
) {
    val input = EditText(this).apply {
        setText(initial)
        this.hint = hint
        setHintTextColor(Th.p.faint)
        setTextColor(Th.p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputType = InputType.TYPE_CLASS_TEXT
        background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setSelection(initial.length)
    }
    showDialog(title, input, okText, "Batal") { onOk(input.text.toString().trim()) }
}

// ---------- jendela ----------

/** Layar penuh di belakang bilah sistem; padding root mengikuti inset. */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge(root: View) {
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
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    root.setOnApplyWindowInsetsListener { v, ins ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
        } else {
            v.setPadding(
                ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                ins.systemWindowInsetRight, ins.systemWindowInsetBottom
            )
        }
        ins
    }
}

/** Ikon bilah status/navigasi gelap di tema terang, terang di tema gelap. */
@Suppress("DEPRECATION")
fun Activity.applyBarIcons() {
    val light = !Th.dark
    if (Build.VERSION.SDK_INT >= 30) {
        val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        var f = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= 23) {
            f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = f
    }
}

fun clampInt(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))

// ---------- jendela ala Windows: minimize / tutup + daftar jendela terbuka ----------

/** Hitung jendela milik launcher yang sedang terbuka (Pengaturan, File Explorer). */
object Running {
    private val counts = HashMap<String, Int>()

    fun opened(key: String) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    fun closed(key: String) {
        val n = (counts[key] ?: 0) - 1
        if (n <= 0) counts.remove(key) else counts[key] = n
    }

    fun isOpen(key: String): Boolean = (counts[key] ?: 0) > 0
}

/** Buka jendela milik launcher di task sendiri, supaya bisa diminimalkan tanpa menutup launcher. */
fun Context.openOwnWindow(cls: Class<*>, extras: (android.content.Intent.() -> Unit)? = null) {
    val i = android.content.Intent(this, cls).addFlags(
        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    )
    extras?.invoke(i)
    startActivity(i)
}

/** Garis kecil untuk tombol minimize. */
private class DashView(ctx: Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = ctx.dpf(1.3f)
        color = Th.p.text
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val half = context.dpf(5f)
        c.drawLine(w / 2 - half, h / 2, w / 2 + half, h / 2, p)
    }
}

/**
 * Tambahkan bilah judul tipis dengan tombol minimize dan tutup di kanan atas,
 * seperti jendela Windows. Seluruh isi [rootFrame] dipindah ke bawah bilah ini.
 */
fun Activity.attachWindowBar(rootFrame: android.widget.FrameLayout, title: String, kind: GlyphView.Kind) {
    val kids = ArrayList<View>()
    for (i in 0 until rootFrame.childCount) kids.add(rootFrame.getChildAt(i))
    rootFrame.removeAllViews()
    val content = android.widget.FrameLayout(this)
    for (k in kids) content.addView(k)

    val bar = ll(false, Gravity.CENTER_VERTICAL).apply {
        setBackgroundColor(Th.p.bg)
        setPadding(dp(12), 0, 0, 0)
    }
    bar.addView(icon(kind, Th.accent), LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) })
    bar.addView(tv(title, 12f, Th.p.dim), LinearLayout.LayoutParams(0, WRAP, 1f))

    fun btn(content: View, hover: Int, onClick: () -> Unit): View =
        android.widget.FrameLayout(this).apply {
            background = ripple(0f)
            addView(content, android.widget.FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { onClick() }
        }

    bar.addView(btn(DashView(this), Th.p.cardHover) {
        // Minimalkan: kembali ke desktop, jendela ini tetap hidup dan muncul di taskbar.
        startActivity(
            android.content.Intent(this, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }, LinearLayout.LayoutParams(dp(46), MATCH))
    bar.addView(btn(icon(GlyphView.Kind.CLOSE, Th.p.text), 0xFFE5484D.toInt()) {
        finishAndRemoveTask()
    }, LinearLayout.LayoutParams(dp(46), MATCH))

    val col = ll(true)
    col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(34)))
    col.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
    rootFrame.addView(col, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
}
