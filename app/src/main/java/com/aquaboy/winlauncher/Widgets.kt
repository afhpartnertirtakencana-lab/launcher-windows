package com.aquaboy.winlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Jenis widget bawaan yang bisa dipasang di desktop. */
internal enum class WType(val label: String, val desc: String, val glyph: GlyphView.Kind) {
    CLOCK("Jam", "Jam digital dan tanggal", GlyphView.Kind.TIME),
    CALENDAR("Kalender", "Tanggal hari ini", GlyphView.Kind.BELL),
    BATTERY("Baterai", "Level dan status pengisian daya", GlyphView.Kind.BATTERY),
    STORAGE("Penyimpanan", "Pemakaian penyimpanan internal", GlyphView.Kind.DRIVE),
    MEMORY("Memori", "Pemakaian RAM", GlyphView.Kind.SYSTEM),
    NOTE("Catatan", "Catatan singkat, ketuk untuk mengubah", GlyphView.Kind.DOC)
}

internal class WItem(val id: Long, val type: WType, var x: Int, var y: Int, var w: Int, var h: Int) {
    /** Kelas ukuran isi (0 kecil, 1 sedang, 2 besar) mengikuti lebar widget. */
    val size: Int get() = WStore.sizeClass(w)
}

internal object WStore {
    private const val KEY = "widgets"

    fun load(sp: SharedPreferences): MutableList<WItem> {
        val out = ArrayList<WItem>()
        val raw = sp.getString(KEY, "") ?: ""
        for (part in raw.split(";")) {
            val f = part.split(",")
            if (f.size != 5 && f.size != 7) continue
            val t = try {
                WType.valueOf(f[1])
            } catch (e: Exception) {
                continue
            }
            val id = f[0].toLongOrNull() ?: continue
            val preset = dims((f[2].toIntOrNull() ?: 1).coerceIn(0, 2))
            val w = if (f.size == 7) (f[5].toIntOrNull() ?: preset.first) else preset.first
            val h = if (f.size == 7) (f[6].toIntOrNull() ?: preset.second) else preset.second
            out.add(WItem(id, t, f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0, w.coerceAtLeast(MIN_W), h.coerceAtLeast(MIN_H)))
        }
        return out
    }

    fun save(sp: SharedPreferences, list: List<WItem>) {
        sp.edit().putString(KEY, list.joinToString(";") { "${it.id},${it.type.name},${it.size},${it.x},${it.y},${it.w},${it.h}" }).apply()
    }

    const val MIN_W = 120
    const val MIN_H = 80

    fun sizeClass(w: Int): Int = when {
        w < 190 -> 0
        w < 260 -> 1
        else -> 2
    }

    /** Ukuran widget dalam dp: kecil, sedang, besar. */
    fun dims(size: Int): Pair<Int, Int> = when (size) {
        0 -> 150 to 96
        2 -> 300 to 190
        else -> 220 to 140
    }
}

/** Cincin kemajuan dengan teks di tengah. */
internal class RingView(ctx: Context) : View(ctx) {
    var progress = 0f
    var ringColor = Th.accent
    var label = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val sw = s * 0.12f
        val l = (width - s) / 2f + sw / 2f
        val t = (height - s) / 2f + sw / 2f
        rect.set(l, t, l + s - sw, t + s - sw)
        track.strokeWidth = sw
        track.color = Th.withAlpha(Th.p.text, 0x26)
        arc.strokeWidth = sw
        arc.color = ringColor
        c.drawArc(rect, 0f, 360f, false, track)
        c.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        txt.color = Th.p.text
        txt.textSize = s * 0.26f
        c.drawText(label, width / 2f, height / 2f - (txt.ascent() + txt.descent()) / 2f, txt)
    }
}

/** Mengelola widget di desktop: pilih, tambah, pindah (tahan lalu geser), ubah ukuran, hapus. */
internal class WidgetLayer(
    private val act: Activity,
    private val desktop: FrameLayout,
    private val prefs: SharedPreferences
) {
    private val items = WStore.load(prefs)
    private val updaters = ArrayList<() -> Unit>()

    fun enabled(): Boolean = prefs.getBoolean("widgets_show", true)

    /** Panggil setelah desktop dibersihkan. */
    fun render() {
        updaters.clear()
        if (!enabled()) return
        for (it in items) {
            val (view, update) = build(it)
            val r = rectOf(it)
            val lp = FrameLayout.LayoutParams(r.width(), r.height())
            lp.leftMargin = r.left
            lp.topMargin = r.top
            attachGestures(view, it)
            desktop.addView(view, lp)
            update()
            updaters.add(update)
        }
    }

    /** Posisi widget (px) setelah dijepit ke dalam desktop. */
    private fun rectOf(it: WItem): android.graphics.Rect {
        val w = min(act.dp(it.w), max(act.dp(WStore.MIN_W), desktop.width - act.dp(8)))
        val h = min(act.dp(it.h), max(act.dp(WStore.MIN_H), desktop.height - act.dp(8)))
        val l = clampX(act.dp(it.x), w)
        val t = clampY(act.dp(it.y), h)
        return android.graphics.Rect(l, t, l + w, t + h)
    }

    /** Area yang dipakai widget, supaya ikon desktop mengalir mengitarinya. */
    fun rects(): List<android.graphics.Rect> = if (enabled()) items.map { rectOf(it) } else emptyList()

    fun tick() {
        for (u in updaters) u()
    }

    private fun clampX(x: Int, w: Int) = x.coerceIn(0, max(0, desktop.width - w))
    private fun clampY(y: Int, h: Int) = y.coerceIn(0, max(0, desktop.height - h))

    private fun relayout() {
        (act as? MainActivity)?.renderDesktopNow()
    }

    fun showPicker() {
        val box = act.ll(true)
        var dialog: android.app.Dialog? = null
        for (t in WType.values()) {
            val row = act.ll(false, Gravity.CENTER_VERTICAL).apply {
                background = act.ripple(act.dpf(8f))
                setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
                setOnClickListener {
                    dialog?.dismiss()
                    add(t)
                }
            }
            row.addView(act.icon(t.glyph, Th.accent), LinearLayout.LayoutParams(act.dp(26), act.dp(26)).apply { rightMargin = act.dp(14) })
            val col = act.ll(true)
            col.addView(act.tv(t.label, 15f, Th.p.text, true))
            col.addView(act.tv(t.desc, 12f, Th.p.dim))
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
        }
        dialog = act.showDialog("Tambah widget", box, null, "Tutup")
    }

    private fun add(t: WType) {
        if (!enabled()) prefs.edit().putBoolean("widgets_show", true).apply()
        val (wDp, hDp) = WStore.dims(1)
        val deskWdp = (desktop.width / act.resources.displayMetrics.density).toInt()
        val deskHdp = (desktop.height / act.resources.displayMetrics.density).toInt()
        val per = max(1, (deskHdp - 12) / (hDp + 12))
        val idx = items.size
        val x = max(0, deskWdp - wDp - 16 - (idx / per) * (wDp + 12))
        val y = 12 + (idx % per) * (hDp + 12)
        items.add(WItem(System.currentTimeMillis(), t, x, y, wDp, hDp))
        WStore.save(prefs, items)
        relayout()
    }

    private fun attachGestures(v: View, item: WItem) {
        var dragging = false
        var downX = 0f
        var downY = 0f
        var startL = 0
        var startT = 0
        var moved = false
        v.isClickable = true
        v.setOnLongClickListener {
            dragging = true
            moved = false
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
            true
        }
        v.setOnTouchListener { view, e ->
            val lp = view.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startL = lp.leftMargin
                    startT = lp.topMargin
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        false
                    } else {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > act.dp(8) || abs(dy) > act.dp(8)) moved = true
                        if (moved) {
                            lp.leftMargin = clampX((startL + dx).toInt(), lp.width)
                            lp.topMargin = clampY((startT + dy).toInt(), lp.height)
                            view.layoutParams = lp
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        if (moved) {
                            val d = act.resources.displayMetrics.density
                            item.x = (lp.leftMargin / d).toInt()
                            item.y = (lp.topMargin / d).toInt()
                            WStore.save(prefs, items)
                            desktop.post { relayout() }
                        } else {
                            showMenu(view, item)
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun showMenu(anchor: View, item: WItem) {
        val pop = PopupMenu(Th.themed(act), anchor)
        val m = pop.menu
        m.add(0, 4, 0, "Ubah ukuran bebas")
        m.add(0, 1, 1, "Ukuran kecil")
        m.add(0, 2, 2, "Ukuran sedang")
        m.add(0, 3, 3, "Ukuran besar")
        m.add(0, 9, 4, "Hapus widget")
        pop.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                4 -> startResize(anchor, item)
                1, 2, 3 -> {
                    val (pw, ph) = WStore.dims(mi.itemId - 1)
                    item.w = pw
                    item.h = ph
                    WStore.save(prefs, items)
                    relayout()
                }
                9 -> {
                    items.remove(item)
                    prefs.edit().remove("widget_note_${item.id}").apply()
                    WStore.save(prefs, items)
                    relayout()
                }
            }
            true
        }
        pop.show()
        Toast.makeText(act, "Tahan lalu geser untuk memindahkan", Toast.LENGTH_SHORT).show()
    }

    /** Tampilkan gagang di sudut kanan bawah; seret untuk mengubah ukuran widget secara bebas. */
    private fun startResize(view: View, item: WItem) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        view.foreground = act.rounded(0, act.dpf(18f), Th.accent, act.dp(2))
        val hs = act.dp(30)
        val hl = FrameLayout.LayoutParams(hs, hs)
        val handle = FrameLayout(act).apply {
            background = act.rounded(Th.accent, hs / 2f, 0xFFFFFFFF.toInt(), act.dp(2))
            elevation = act.dpf(12f)
        }
        fun place() {
            hl.leftMargin = lp.leftMargin + lp.width - hs
            hl.topMargin = lp.topMargin + lp.height - hs
            handle.layoutParams = hl
        }
        place()
        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0
        var moved = false
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = lp.width
                    startH = lp.height
                    moved = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nw = (startW + (e.rawX - downX)).toInt()
                        .coerceIn(act.dp(WStore.MIN_W), max(act.dp(WStore.MIN_W), desktop.width - lp.leftMargin))
                    val nh = (startH + (e.rawY - downY)).toInt()
                        .coerceIn(act.dp(WStore.MIN_H), max(act.dp(WStore.MIN_H), desktop.height - lp.topMargin))
                    if (abs(nw - startW) > act.dp(4) || abs(nh - startH) > act.dp(4)) moved = true
                    lp.width = nw
                    lp.height = nh
                    view.layoutParams = lp
                    place()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val d = act.resources.displayMetrics.density
                        item.w = (lp.width / d).toInt().coerceAtLeast(WStore.MIN_W)
                        item.h = (lp.height / d).toInt().coerceAtLeast(WStore.MIN_H)
                        WStore.save(prefs, items)
                    }
                    desktop.post { relayout() }
                    true
                }
                else -> true
            }
        }
        desktop.addView(handle, hl)
        Toast.makeText(act, "Seret lingkaran di sudut untuk mengubah ukuran", Toast.LENGTH_SHORT).show()
    }

    // ---------- isi widget ----------
    private fun card(): LinearLayout = act.ll(true, Gravity.CENTER_VERTICAL).apply {
        background = act.rounded(Th.panel(), act.dpf(18f), Th.p.stroke, act.dp(1))
        elevation = act.dpf(6f)
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
    }

    private fun clock(fmt12: String, fmt24: String, sp: Float, color: Int, bold: Boolean): TextClock =
        TextClock(act).apply {
            format12Hour = fmt12
            format24Hour = fmt24
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }

    private fun build(it: WItem): Pair<View, () -> Unit> = when (it.type) {
        WType.CLOCK -> buildClock(it.size)
        WType.CALENDAR -> buildCalendar(it.size)
        WType.BATTERY -> buildBattery(it.size)
        WType.STORAGE -> buildStorage(it.size)
        WType.MEMORY -> buildMemory(it.size)
        WType.NOTE -> buildNote(it)
    }

    private fun buildClock(size: Int): Pair<View, () -> Unit> {
        val c = card()
        c.gravity = Gravity.CENTER_VERTICAL
        c.addView(clock("h:mm", "HH:mm", floatArrayOf(34f, 46f, 62f)[size], Th.p.text, true))
        c.addView(clock("EEEE, d MMMM", "EEEE, d MMMM", floatArrayOf(12f, 14f, 17f)[size], Th.accent, false))
        if (size == 2) c.addView(clock("yyyy", "yyyy", 14f, Th.p.dim, false))
        return c to {}
    }

    private fun buildCalendar(size: Int): Pair<View, () -> Unit> {
        val c = card()
        c.addView(clock("EEEE", "EEEE", floatArrayOf(12f, 14f, 17f)[size], Th.accent, true))
        c.addView(clock("d", "d", floatArrayOf(36f, 54f, 80f)[size], Th.p.text, true))
        c.addView(clock("MMMM yyyy", "MMMM yyyy", floatArrayOf(11f, 13f, 16f)[size], Th.p.dim, false))
        return c to {}
    }

    private fun ringCard(title: String, size: Int): Triple<LinearLayout, RingView, Pair<TextView, TextView>> {
        val c = act.ll(false, Gravity.CENTER_VERTICAL).apply {
            background = act.rounded(Th.panel(), act.dpf(18f), Th.p.stroke, act.dp(1))
            elevation = act.dpf(6f)
            setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        }
        val ring = RingView(act)
        val rs = intArrayOf(56, 76, 112)[size]
        c.addView(ring, LinearLayout.LayoutParams(act.dp(rs), act.dp(rs)).apply { rightMargin = act.dp(12) })
        val col = act.ll(true, Gravity.CENTER_VERTICAL)
        val head = act.tv(title, floatArrayOf(12f, 14f, 17f)[size], Th.p.text, true)
        val sub = act.tv("", floatArrayOf(11f, 12f, 14f)[size], Th.p.dim).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(head)
        col.addView(sub)
        c.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return Triple(c, ring, head to sub)
    }

    private fun buildBattery(size: Int): Pair<View, () -> Unit> {
        val (c, ring, ts) = ringCard("Baterai", size)
        val upd: () -> Unit = {
            val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else 0
                ring.progress = pct / 100f
                ring.label = "$pct%"
                ring.ringColor = when {
                    charging -> 0xFF2EAD5B.toInt()
                    pct <= 15 -> 0xFFE5484D.toInt()
                    else -> Th.accent
                }
                val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                ts.second.text = (if (charging) "Mengisi daya" else "Tidak mengisi daya") +
                    (if (size >= 1) "\nSuhu ${"%.1f".format(temp)}°C" else "")
                ring.invalidate()
            }
        }
        return c to upd
    }

    private fun buildStorage(size: Int): Pair<View, () -> Unit> {
        val (c, ring, ts) = ringCard("Penyimpanan", size)
        val upd: () -> Unit = {
            try {
                val st = StatFs(Environment.getDataDirectory().path)
                val total = st.totalBytes
                val used = total - st.availableBytes
                val f = if (total > 0) used.toFloat() / total else 0f
                ring.progress = f
                ring.label = "${(f * 100).toInt()}%"
                ring.ringColor = if (f > 0.9f) 0xFFE5484D.toInt() else Th.accent
                ts.second.text = "${fmtSize(used)} dari ${fmtSize(total)}" +
                    (if (size >= 1) "\n${fmtSize(st.availableBytes)} kosong" else "")
                ring.invalidate()
            } catch (e: Exception) {
                ts.second.text = "Tidak tersedia"
            }
        }
        return c to upd
    }

    private fun buildMemory(size: Int): Pair<View, () -> Unit> {
        val (c, ring, ts) = ringCard("Memori", size)
        val upd: () -> Unit = {
            val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val used = mi.totalMem - mi.availMem
            val f = if (mi.totalMem > 0) used.toFloat() / mi.totalMem else 0f
            ring.progress = f
            ring.label = "${(f * 100).toInt()}%"
            ring.ringColor = if (f > 0.9f) 0xFFE5484D.toInt() else Th.accent
            ts.second.text = "${fmtSize(used)} dari ${fmtSize(mi.totalMem)}" +
                (if (size >= 1) "\n${fmtSize(mi.availMem)} tersedia" else "")
            ring.invalidate()
        }
        return c to upd
    }

    private fun buildNote(it: WItem): Pair<View, () -> Unit> {
        val key = "widget_note_${it.id}"
        val c = card()
        c.gravity = Gravity.TOP
        c.addView(act.tv("Catatan", 12f, Th.accent, true))
        val body = act.tv("", floatArrayOf(13f, 14f, 16f)[it.size], Th.p.text).apply {
            maxLines = intArrayOf(2, 4, 8)[it.size]
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, act.dp(4), 0, 0)
        }
        c.addView(body)
        val upd: () -> Unit = {
            val t = prefs.getString(key, "") ?: ""
            body.text = if (t.isBlank()) "Ketuk untuk menulis catatan" else t
            body.alpha = if (t.isBlank()) 0.55f else 1f
        }
        c.setOnClickListener {
            act.promptDialog("Catatan", prefs.getString(key, "") ?: "", "Tulis catatan", "Simpan") { v ->
                prefs.edit().putString(key, v).apply()
                upd()
            }
        }
        return c to upd
    }
}
