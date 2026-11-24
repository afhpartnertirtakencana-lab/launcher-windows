package com.aquaboy.winlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Jenis widget bawaan yang bisa dipasang di desktop. */
internal enum class WType(
    val label: String,
    val desc: String,
    val glyph: GlyphView.Kind,
    val defW: Int = 0,   // ukuran awal khusus (dp); 0 = ikut ukuran standar
    val defH: Int = 0
) {
    CLOCK("Jam", "Jam digital dan tanggal", GlyphView.Kind.TIME),
    CALENDAR("Kalender", "Tanggal hari ini", GlyphView.Kind.BELL),
    BATTERY("Baterai", "Level dan status pengisian daya", GlyphView.Kind.BATTERY),
    STORAGE("Penyimpanan", "Pemakaian penyimpanan internal", GlyphView.Kind.DRIVE),
    MEMORY("Memori", "Pemakaian RAM, ketuk untuk membersihkan RAM", GlyphView.Kind.SYSTEM),
    NOTE("Catatan", "Catatan singkat, ketuk untuk mengubah", GlyphView.Kind.DOC),
    TOOLS("Pintasan cepat", "Senter, Wi-Fi, Bluetooth, dan volume", GlyphView.Kind.GEAR, 260, 96),
    MONTH("Kalender bulan", "Kalender satu bulan penuh", GlyphView.Kind.STAR, 260, 200),
    WORLD("Jam dunia", "WIB, WITA, WIT, Mekkah, dan London", GlyphView.Kind.LANGUAGE, 220, 160),
    NET("Kecepatan jaringan", "Kecepatan unduh dan unggah", GlyphView.Kind.NET, 200, 110),
    SEARCH("Pencarian", "Kolom cari cepat ke menu Start", GlyphView.Kind.SEARCH, 260, 80),
    WEATHER("Cuaca", "Suhu dan kondisi cuaca kota pilihan", GlyphView.Kind.SUN, 220, 140),
    MUSIC("Pemutar musik", "Kontrol musik yang sedang diputar", GlyphView.Kind.PHOTO, 260, 120),
    PRAYER("Waktu doa", "Waktu doa Katolik berikutnya dengan hitung mundur", GlyphView.Kind.BELL, 220, 120),
    TODO("Daftar tugas", "Daftar tugas dengan kotak centang, ketuk untuk mengelola", GlyphView.Kind.CHECK, 240, 180),
    COUNTDOWN("Hitung mundur", "Berapa hari lagi menuju tanggal penting", GlyphView.Kind.PIN, 200, 120),
    LITURGY("Kalender liturgi", "Hari liturgi, masa, dan warna liturgi hari ini", GlyphView.Kind.STAR, 220, 120),
    AGENDA("Agenda", "Acara kalender HP dalam 7 hari ke depan", GlyphView.Kind.TIME, 260, 170)
}

internal class WItem(val id: Long, val type: WType, var x: Int, var y: Int, var w: Int, var h: Int) {
    /** Kelas ukuran isi (0 kecil, 1 sedang, 2 besar) mengikuti lebar widget. */
    val size: Int get() = WStore.sizeClass(w)
}

internal object WStore {
    const val KEY = "widgets"
    const val KEY_LAND = "widgets_land"

    fun load(sp: SharedPreferences, key: String = KEY): MutableList<WItem> {
        val out = ArrayList<WItem>()
        val raw = sp.getString(key, "") ?: ""
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

    fun save(sp: SharedPreferences, list: List<WItem>, key: String = KEY) {
        sp.edit().putString(key, list.joinToString(";") { "${it.id},${it.type.name},${it.size},${it.x},${it.y},${it.w},${it.h}" }).apply()
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
    // HP landscape (tampilan tablet diperkecil) punya susunan widget sendiri, supaya tidak merusak susunan potret.
    private val key = if (landScaled) WStore.KEY_LAND else WStore.KEY
    private var needsArrange = landScaled && !prefs.contains(WStore.KEY_LAND)
    private val items = WStore.load(prefs, if (needsArrange) WStore.KEY else key)
    private val updaters = ArrayList<() -> Unit>()

    fun enabled(): Boolean = prefs.getBoolean("widgets_show", true)

    /** Panggil setelah desktop dibersihkan. */
    fun render() {
        updaters.clear()
        if (!enabled()) return
        if (needsArrange && desktop.width > 0 && desktop.height > 0) {
            needsArrange = false
            arrange(silent = true)
        }
        for (it in items) {
            try {
                renderOne(it)
            } catch (e: Throwable) {
                Toast.makeText(act, "Widget ${it.type.label} gagal: ${e.javaClass.simpleName} ${e.message ?: ""}".take(120), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderOne(it: WItem) {
        run {
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
        // Daftar panjang harus bisa digulir (terutama saat landscape yang tingginya pendek).
        val sv = android.widget.ScrollView(act)
        sv.addView(box)
        val h = (act.resources.displayMetrics.heightPixels * 0.62f).toInt()
        val holder = FrameLayout(act)
        holder.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
        dialog = act.showDialog("Tambah widget", holder, null, "Tutup")
    }

    private fun add(t: WType) {
        if (!enabled()) prefs.edit().putBoolean("widgets_show", true).apply()
        val deskWdp = (desktop.width / act.resources.displayMetrics.density).toInt()
        val deskHdp = (desktop.height / act.resources.displayMetrics.density).toInt()
        if (isPhone()) {
            // HP: ukuran kecil (dua widget muat sebaris), diletakkan di ruang kosong mulai dari bawah.
            val (wDp, hDp) = startDims(t, 0, deskWdp)
            val (x, y) = freeSpot(wDp, hDp, deskWdp, deskHdp)
            items.add(WItem(System.currentTimeMillis(), t, x, y, wDp, hDp))
        } else {
            val (wDp, hDp) = startDims(t, 1, deskWdp)
            val per = max(1, (deskHdp - 12) / (hDp + 12))
            val idx = items.size
            val x = max(0, deskWdp - wDp - 16 - (idx / per) * (wDp + 12))
            val y = 12 + (idx % per) * (hDp + 12)
            items.add(WItem(System.currentTimeMillis(), t, x, y, wDp, hDp))
        }
        WStore.save(prefs, items, key)
        relayout()
    }

    private fun startDims(t: WType, size: Int, deskWdp: Int): Pair<Int, Int> {
        if (t.defW <= 0) return WStore.dims(size)
        return min(t.defW, max(WStore.MIN_W, deskWdp - 16)) to t.defH
    }

    private fun isPhone(): Boolean = !landScaled && act.resources.configuration.smallestScreenWidthDp < 600

    /** Cari ruang kosong (dp) untuk widget baru: dari kanan-bawah ke kiri lalu ke atas, tanpa menimpa widget lain. */
    private fun freeSpot(wDp: Int, hDp: Int, deskW: Int, deskH: Int): Pair<Int, Int> {
        val gap = 8
        var y = deskH - hDp - gap
        while (y >= gap) {
            var x = deskW - wDp - gap
            while (x >= gap) {
                val hit = items.any {
                    x < it.x + it.w + gap && x + wDp + gap > it.x &&
                        y < it.y + it.h + gap && y + hDp + gap > it.y
                }
                if (!hit) return x to y
                x -= 6
            }
            y -= 6
        }
        return gap to gap
    }

    /** Susun ulang semua widget ke ruang kosong dari bawah, supaya tidak saling menimpa. */
    fun arrange(silent: Boolean = false) {
        val deskWdp = (desktop.width / act.resources.displayMetrics.density).toInt()
        val deskHdp = (desktop.height / act.resources.displayMetrics.density).toInt()
        if (deskWdp <= 0 || deskHdp <= 0) return
        val old = ArrayList(items)
        items.clear()
        for (w in old) {
            w.w = min(w.w, max(WStore.MIN_W, deskWdp - 16))
            w.h = min(w.h, max(WStore.MIN_H, deskHdp - 16))
            val (x, y) = freeSpot(w.w, w.h, deskWdp, deskHdp)
            w.x = x
            w.y = y
            items.add(w)
        }
        WStore.save(prefs, items, key)
        if (!silent) {
            relayout()
            Toast.makeText(act, "Widget dirapikan", Toast.LENGTH_SHORT).show()
        }
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
                            WStore.save(prefs, items, key)
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
        m.add(0, 4, 0, "Ubah ukuran…")
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
                    WStore.save(prefs, items, key)
                    relayout()
                }
                9 -> {
                    items.remove(item)
                    prefs.edit().remove("widget_note_${item.id}").apply()
                    WStore.save(prefs, items, key)
                    relayout()
                }
            }
            true
        }
        pop.show()
        Toast.makeText(act, "Tahan lalu geser untuk memindahkan", Toast.LENGTH_SHORT).show()
    }

    /** Dialog dengan penggeser lebar dan tinggi; ukuran widget berubah langsung saat digeser. */
    private fun startResize(view: View, item: WItem) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val d = act.resources.displayMetrics.density
        val maxW = max(WStore.MIN_W + 20, (desktop.width / d).toInt() - 8)
        val maxH = max(WStore.MIN_H + 20, (desktop.height / d).toInt() - 8)

        fun apply() {
            lp.width = min(act.dp(item.w), desktop.width)
            lp.height = min(act.dp(item.h), desktop.height)
            view.layoutParams = lp
        }

        fun slider(label: String, lo: Int, hi: Int, cur: Int, onChange: (Int) -> Unit): View {
            val col = act.ll(true)
            val head = act.tv("$label: ${cur.coerceIn(lo, hi)} dp", 13f, Th.p.text)
            val sb = SeekBar(act).apply {
                max = hi - lo
                progress = (cur - lo).coerceIn(0, hi - lo)
                progressTintList = ColorStateList.valueOf(Th.accent)
                thumbTintList = ColorStateList.valueOf(Th.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = lo + p
                        head.text = "$label: $v dp"
                        onChange(v)
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            col.addView(head)
            col.addView(sb, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = act.dp(10) })
            return col
        }

        val box = act.ll(true)
        box.addView(slider("Lebar", WStore.MIN_W, maxW, item.w) { v ->
            item.w = v
            apply()
        })
        box.addView(slider("Tinggi", WStore.MIN_H, maxH, item.h) { v ->
            item.h = v
            apply()
        })
        val dlg = act.showDialog("Ubah ukuran widget", box, "Selesai", null)
        dlg.setOnDismissListener {
            WStore.save(prefs, items, key)
            relayout()
        }
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
        WType.TOOLS -> buildTools()
        WType.MONTH -> buildMonth(it.size)
        WType.WORLD -> buildWorld(it)
        WType.NET -> buildNet(it.size)
        WType.SEARCH -> buildSearch()
        WType.WEATHER -> buildWeather(it.size)
        WType.MUSIC -> buildMusic(it.size)
        WType.PRAYER -> buildPrayer(it.size)
        WType.TODO -> buildTodo(it)
        WType.COUNTDOWN -> buildCountdown(it)
        WType.LITURGY -> buildLiturgy(it.size)
        WType.AGENDA -> buildAgenda(it.size)
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
            setPadding(act.dp(if (size == 0) 10 else 14), act.dp(10), act.dp(if (size == 0) 10 else 14), act.dp(10))
        }
        val ring = RingView(act)
        val rs = intArrayOf(56, 76, 112)[size]
        c.addView(ring, LinearLayout.LayoutParams(act.dp(rs), act.dp(rs)).apply { rightMargin = act.dp(if (size == 0) 8 else 12) })
        val col = act.ll(true, Gravity.CENTER_VERTICAL)
        val headSp = floatArrayOf(12f, 14f, 17f)[size]
        val head = act.tv(title, headSp, Th.p.text, true).apply {
            // Judul selalu satu baris: huruf mengecil otomatis bila ruangnya sempit (mis. "Penyimpanan").
            maxLines = 1
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                setAutoSizeTextTypeUniformWithConfiguration(9, headSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
            } else if (title.length > 9 && size == 0) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, headSp - 2f)
            }
        }
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
        val busy = BooleanArray(1)
        val upd: () -> Unit = {
            val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val used = mi.totalMem - mi.availMem
            val f = if (mi.totalMem > 0) used.toFloat() / mi.totalMem else 0f
            ring.progress = f
            ring.label = "${(f * 100).toInt()}%"
            ring.ringColor = if (f > 0.9f) 0xFFE5484D.toInt() else Th.accent
            ts.second.text = if (busy[0]) "Membersihkan…" else
                "${fmtSize(used)} dari ${fmtSize(mi.totalMem)}" +
                    (if (size >= 1) "\n${fmtSize(mi.availMem)} tersedia" else "") +
                    (if (size >= 2) "\nKetuk untuk membersihkan" else "")
            ring.invalidate()
        }
        // Ketuk widget = bebaskan RAM (hentikan proses latar belakang aplikasi lain) dan bersihkan cache launcher.
        c.setOnClickListener {
            if (!busy[0]) {
                busy[0] = true
                upd()
                Thread {
                    val r = Cleaner.clean(act)
                    act.runOnUiThread {
                        busy[0] = false
                        upd()
                        val ram = if (r.ramFreed >= 1_000_000L) "RAM bertambah ${fmtSize(r.ramFreed)}" else "RAM sudah cukup bersih"
                        val cache = if (r.cacheFreed > 0) ", cache launcher ${fmtSize(r.cacheFreed)} dibersihkan" else ""
                        Toast.makeText(act, ram + cache, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
        return c to upd
    }

    // ---------- widget tambahan ----------
    private fun rowCard(pad: Int = 8): LinearLayout = act.ll(false, Gravity.CENTER_VERTICAL).apply {
        background = act.rounded(Th.panel(), act.dpf(18f), Th.p.stroke, act.dp(1))
        elevation = act.dpf(6f)
        setPadding(act.dp(pad), act.dp(pad), act.dp(pad), act.dp(pad))
    }

    private fun openSys(vararg actions: String) {
        for (a in actions) {
            try {
                act.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Throwable) {
                // coba cadangan berikutnya
            }
        }
        Toast.makeText(act, "Tidak dapat membuka pengaturan ini", Toast.LENGTH_SHORT).show()
    }

    private fun buildTools(): Pair<View, () -> Unit> {
        val c = rowCard()
        fun button(kind: GlyphView.Kind, label: String, onClick: (FrameLayout, GlyphView) -> Unit) {
            val col = act.ll(true, Gravity.CENTER_HORIZONTAL)
            val ic = act.icon(kind, Th.p.text)
            val circle = FrameLayout(act).apply {
                background = act.rounded(Th.withAlpha(Th.accent, 0x30), act.dpf(24f))
                addView(ic, FrameLayout.LayoutParams(act.dp(22), act.dp(22), Gravity.CENTER))
                setOnClickListener { onClick(this, ic) }
            }
            col.addView(circle, LinearLayout.LayoutParams(act.dp(46), act.dp(46)))
            col.addView(act.tv(label, 10f, Th.p.dim).apply {
                maxLines = 1
                setPadding(0, act.dp(3), 0, 0)
            })
            c.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        var torchOn = false
        button(GlyphView.Kind.SUN, "Senter") { circle, ic ->
            try {
                val cm = act.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id == null) {
                    Toast.makeText(act, "Perangkat ini tidak punya lampu senter", Toast.LENGTH_SHORT).show()
                } else {
                    torchOn = !torchOn
                    cm.setTorchMode(id, torchOn)
                    circle.background = act.rounded(if (torchOn) Th.accent else Th.withAlpha(Th.accent, 0x30), act.dpf(24f))
                    ic.tint = if (torchOn) Th.onAccent else Th.p.text
                    ic.invalidate()
                }
            } catch (e: Throwable) {
                torchOn = false
                Toast.makeText(act, "Senter tidak dapat dinyalakan", Toast.LENGTH_SHORT).show()
            }
        }
        button(GlyphView.Kind.NET, "Wi-Fi") { _, _ ->
            openSys("android.settings.panel.action.INTERNET_CONNECTIVITY", android.provider.Settings.ACTION_WIFI_SETTINGS)
        }
        button(GlyphView.Kind.BLUETOOTH, "Bluetooth") { _, _ ->
            openSys(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        button(GlyphView.Kind.VOLUME, "Volume") { _, _ ->
            openSys("android.settings.panel.action.VOLUME", android.provider.Settings.ACTION_SOUND_SETTINGS)
        }
        return c to {}
    }

    private fun buildMonth(size: Int): Pair<View, () -> Unit> {
        val c = act.ll(true).apply {
            background = act.rounded(Th.panel(), act.dpf(18f), Th.p.stroke, act.dp(1))
            elevation = act.dpf(6f)
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
        }
        val sp = floatArrayOf(9f, 10f, 12f)[size]
        val title = act.tv("", sp + 3f, Th.accent, true)
        val grid = act.ll(true)
        c.addView(title)
        c.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = act.dp(4) })
        var shownDay = -1
        fun cell(text: String, color: Int, bold: Boolean, today: Boolean): View =
            act.tv(text, sp, if (today) Th.onAccent else color, bold || today).apply {
                gravity = Gravity.CENTER
                if (today) background = act.rounded(Th.accent, act.dpf(12f))
            }
        val upd: () -> Unit = {
            val now = java.util.Calendar.getInstance()
            val day = now.get(java.util.Calendar.DAY_OF_YEAR) * 10000 + now.get(java.util.Calendar.YEAR)
            if (day != shownDay) {
                shownDay = day
                title.text = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(now.time)
                grid.removeAllViews()
                val head = act.ll(false)
                for (d in listOf("Sn", "Sl", "Rb", "Km", "Jm", "Sb", "Mg")) {
                    head.addView(cell(d, Th.p.dim, true, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                }
                grid.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                val first = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                val offset = (first.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Senin = 0
                val days = first.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                val today = now.get(java.util.Calendar.DAY_OF_MONTH)
                var n = 1 - offset
                for (r in 0 until 6) {
                    if (n > days) break
                    val row = act.ll(false)
                    for (col in 0 until 7) {
                        val view = if (n in 1..days) cell(n.toString(), if (col == 6) 0xFFE5484D.toInt() else Th.p.text, false, n == today)
                        else cell("", Th.p.text, false, false)
                        row.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                        n++
                    }
                    grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }
            }
        }
        return c to upd
    }

    private fun buildWorld(it: WItem): Pair<View, () -> Unit> {
        val c = act.ll(true, Gravity.CENTER_VERTICAL).apply {
            background = act.rounded(Th.panel(), act.dpf(18f), Th.p.stroke, act.dp(1))
            elevation = act.dpf(6f)
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
        }
        val zones = listOf(
            Triple("Jakarta", "WIB", "Asia/Jakarta"),
            Triple("Makassar", "WITA", "Asia/Makassar"),
            Triple("Jayapura", "WIT", "Asia/Jayapura"),
            Triple("Mekkah", "AST", "Asia/Riyadh"),
            Triple("London", "UK", "Europe/London")
        )
        val rows = when {
            it.h < 110 -> 3
            it.h < 140 -> 4
            else -> 5
        }
        val sp = floatArrayOf(12f, 13f, 15f)[it.size]
        for ((city, tag, zone) in zones.take(rows)) {
            val row = act.ll(false, Gravity.CENTER_VERTICAL)
            row.addView(act.tv(city, sp, Th.p.text).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(act.tv(tag, sp - 3f, Th.p.dim).apply { setPadding(0, 0, act.dp(8), 0) })
            row.addView(TextClock(act).apply {
                format12Hour = "HH:mm"
                format24Hour = "HH:mm"
                timeZone = zone
                textSize = sp + 1f
                setTextColor(Th.accent)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            c.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return c to {}
    }

    private fun buildNet(size: Int): Pair<View, () -> Unit> {
        val c = card()
        c.addView(act.tv("Jaringan", floatArrayOf(12f, 13f, 15f)[size], Th.accent, true))
        val sp = floatArrayOf(15f, 18f, 22f)[size]
        val down = act.tv("", sp, Th.p.text, true).apply { maxLines = 1 }
        val up = act.tv("", sp - 3f, Th.p.dim).apply { maxLines = 1 }
        val kind = act.tv("", 11f, Th.p.dim).apply { maxLines = 1 }
        c.addView(down)
        c.addView(up)
        c.addView(kind)
        var lastRx = android.net.TrafficStats.getTotalRxBytes()
        var lastTx = android.net.TrafficStats.getTotalTxBytes()
        var lastT = System.currentTimeMillis()
        val upd: () -> Unit = {
            val now = System.currentTimeMillis()
            val dt = now - lastT
            if (dt >= 800) {
                val rx = android.net.TrafficStats.getTotalRxBytes()
                val tx = android.net.TrafficStats.getTotalTxBytes()
                if (rx >= 0 && lastRx >= 0) {
                    down.text = "\u2193 " + fmtSize(max(0L, (rx - lastRx) * 1000 / dt)) + "/s"
                    up.text = "\u2191 " + fmtSize(max(0L, (tx - lastTx) * 1000 / dt)) + "/s"
                } else {
                    down.text = "Tidak tersedia"
                    up.text = ""
                }
                lastRx = rx
                lastTx = tx
                lastT = now
            }
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = try {
                cm.getNetworkCapabilities(cm.activeNetwork)
            } catch (e: Throwable) {
                null
            }
            kind.text = when {
                caps == null -> "Offline"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Data seluler"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Terhubung"
            }
        }
        // Segarkan tiap 2 detik selama widget terlihat.
        val loop = object : Runnable {
            override fun run() {
                if (!c.isAttachedToWindow) return
                upd()
                c.postDelayed(this, 2000)
            }
        }
        c.post(loop)
        return c to upd
    }

    private fun buildSearch(): Pair<View, () -> Unit> {
        val c = rowCard(14)
        c.addView(act.icon(GlyphView.Kind.SEARCH, Th.accent), LinearLayout.LayoutParams(act.dp(22), act.dp(22)))
        c.addView(act.tv("Ketik di sini untuk mencari", 14f, Th.p.dim).apply {
            maxLines = 1
            setPadding(act.dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.setOnClickListener { (act as? MainActivity)?.openSearchFromWidget() }
        return c to {}
    }

    // ---------- cuaca (Open-Meteo, tanpa kunci API) ----------
    private fun weatherText(code: Int): Pair<String, String> = when (code) {
        0 -> "Cerah" to "\u2600\uFE0F"
        1 -> "Cerah berawan" to "\uD83C\uDF24\uFE0F"
        2 -> "Berawan sebagian" to "\u26C5"
        3 -> "Mendung" to "\u2601\uFE0F"
        45, 48 -> "Berkabut" to "\uD83C\uDF2B\uFE0F"
        51, 53, 55, 56, 57 -> "Gerimis" to "\uD83C\uDF26\uFE0F"
        61, 80 -> "Hujan ringan" to "\uD83C\uDF27\uFE0F"
        63, 81 -> "Hujan sedang" to "\uD83C\uDF27\uFE0F"
        65, 66, 67, 82 -> "Hujan lebat" to "\uD83C\uDF27\uFE0F"
        71, 73, 75, 77, 85, 86 -> "Salju" to "\u2744\uFE0F"
        95, 96, 99 -> "Badai petir" to "\u26C8\uFE0F"
        else -> "Cuaca" to "\uD83C\uDF21\uFE0F"
    }

    private fun fetchJson(url: String): org.json.JSONObject? = try {
        val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        con.connectTimeout = 8000
        con.readTimeout = 8000
        con.requestMethod = "GET"
        val text = con.inputStream.bufferedReader().use { it.readText() }
        con.disconnect()
        org.json.JSONObject(text)
    } catch (e: Throwable) {
        null
    }

    private fun buildWeather(size: Int): Pair<View, () -> Unit> {
        val c = card()
        val city = act.tv("", floatArrayOf(11f, 12f, 14f)[size], Th.accent, true).apply { maxLines = 1 }
        val temp = act.tv("--°", floatArrayOf(30f, 38f, 50f)[size], Th.p.text, true).apply { maxLines = 1 }
        val cond = act.tv("Memuat…", floatArrayOf(12f, 13f, 15f)[size], Th.p.text).apply { maxLines = 1 }
        val more = act.tv("", floatArrayOf(10f, 11f, 12f)[size], Th.p.dim).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        c.addView(city)
        c.addView(temp)
        c.addView(cond)
        if (size >= 1) c.addView(more)
        var loading = false

        fun render() {
            city.text = prefs.getString("weather_city", "Jakarta") ?: "Jakarta"
            val raw = prefs.getString("weather_json", "") ?: ""
            if (raw.isEmpty()) return
            try {
                val j = org.json.JSONObject(raw)
                val cur = j.getJSONObject("current")
                val code = cur.getInt("weather_code")
                val (name, emoji) = weatherText(code)
                val night = cur.optInt("is_day", 1) == 0 && (code == 0 || code == 1)
                temp.text = "${Math.round(cur.getDouble("temperature_2m"))}°"
                cond.text = (if (night) "\uD83C\uDF19" else emoji) + " " + name
                val d = j.getJSONObject("daily")
                val hi = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0))
                val lo = Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))
                val rain = d.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
                more.text = "Tertinggi $hi° · Terendah $lo°" +
                    (if (rain >= 0) " · Hujan $rain%" else "") +
                    "\nTerasa ${Math.round(cur.getDouble("apparent_temperature"))}° · Lembap ${cur.getInt("relative_humidity_2m")}%" +
                    (if (size >= 2) " · Angin ${Math.round(cur.getDouble("wind_speed_10m"))} km/j\nData: Open-Meteo.com" else "")
            } catch (e: Throwable) {
                cond.text = "Data cuaca tidak terbaca"
            }
        }

        fun refresh(force: Boolean) {
            val age = System.currentTimeMillis() - prefs.getLong("weather_time", 0L)
            if (loading || (!force && age < 30 * 60 * 1000L)) return
            loading = true
            val lat = prefs.getString("weather_lat", "-6.2088") ?: "-6.2088"
            val lon = prefs.getString("weather_lon", "106.8456") ?: "106.8456"
            Thread {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=1&timezone=auto"
                val j = fetchJson(url)
                act.runOnUiThread {
                    loading = false
                    if (j != null && j.has("current")) {
                        prefs.edit().putString("weather_json", j.toString()).putLong("weather_time", System.currentTimeMillis()).apply()
                        render()
                    } else if ((prefs.getString("weather_json", "") ?: "").isEmpty()) {
                        cond.text = "Tidak ada koneksi internet"
                    }
                }
            }.start()
        }

        c.setOnClickListener {
            act.promptDialog("Kota untuk cuaca", prefs.getString("weather_city", "Jakarta") ?: "", "Nama kota", "Cari") { q ->
                val name = q.trim()
                if (name.isNotEmpty()) {
                    Toast.makeText(act, "Mencari $name…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val j = fetchJson(
                            "https://geocoding-api.open-meteo.com/v1/search?name=" + java.net.URLEncoder.encode(name, "UTF-8") +
                                "&count=1&language=id&format=json"
                        )
                        val r = j?.optJSONArray("results")?.optJSONObject(0)
                        act.runOnUiThread {
                            if (r == null) {
                                Toast.makeText(act, "Kota \"$name\" tidak ditemukan", Toast.LENGTH_LONG).show()
                            } else {
                                prefs.edit()
                                    .putString("weather_city", r.optString("name", name))
                                    .putString("weather_lat", r.getDouble("latitude").toString())
                                    .putString("weather_lon", r.getDouble("longitude").toString())
                                    .putLong("weather_time", 0L)
                                    .putString("weather_json", "")
                                    .apply()
                                render()
                                refresh(true)
                            }
                        }
                    }.start()
                }
            }
        }
        val upd: () -> Unit = {
            render()
            refresh(false)
        }
        return c to upd
    }

    // ---------- pemutar musik (butuh akses notifikasi) ----------
    private fun buildMusic(size: Int): Pair<View, () -> Unit> {
        val c = card()
        val app = act.tv("", 10f, Th.accent, true).apply { maxLines = 1 }
        val title = act.tv("Tidak ada musik diputar", floatArrayOf(13f, 15f, 17f)[size], Th.p.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val artist = act.tv("", floatArrayOf(11f, 12f, 14f)[size], Th.p.dim).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val controls = act.ll(false, Gravity.CENTER)
        var current: android.media.session.MediaController? = null
        val playBtn = act.tv("\u25B6", 22f, Th.p.text, true)
        fun ctl(label: TextView, onClick: () -> Unit) {
            label.gravity = Gravity.CENTER
            label.setOnClickListener {
                onClick()
                label.postDelayed({ if (label.isAttachedToWindow) label.rootView?.invalidate() }, 300)
            }
            controls.addView(label, LinearLayout.LayoutParams(0, act.dp(40), 1f))
        }
        ctl(act.tv("\u25C0\u25C0", 16f, Th.p.text, true)) { current?.transportControls?.skipToPrevious() }
        ctl(playBtn) {
            val st = current?.playbackState?.state
            if (st == android.media.session.PlaybackState.STATE_PLAYING) current?.transportControls?.pause()
            else current?.transportControls?.play()
        }
        ctl(act.tv("\u25B6\u25B6", 16f, Th.p.text, true)) { current?.transportControls?.skipToNext() }
        c.addView(app)
        c.addView(title)
        c.addView(artist)
        c.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = act.dp(4)
        })
        var noAccess = false
        c.setOnClickListener {
            if (noAccess) {
                try {
                    act.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Throwable) {
                    Toast.makeText(act, "Pengaturan tidak tersedia", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val upd: () -> Unit = {
            try {
                val msm = act.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
                val list = msm.getActiveSessions(android.content.ComponentName(act, NotifService::class.java))
                noAccess = false
                current = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                    ?: list.firstOrNull()
                val cur = current
                if (cur == null) {
                    app.text = ""
                    title.text = "Tidak ada musik diputar"
                    artist.text = "Putar lagu di aplikasi musik"
                    playBtn.text = "\u25B6"
                } else {
                    val md = cur.metadata
                    app.text = try {
                        act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(cur.packageName, 0)).toString()
                    } catch (e: Throwable) {
                        cur.packageName
                    }
                    title.text = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Sedang diputar"
                    artist.text = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    playBtn.text = if (cur.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) "\u275A\u275A" else "\u25B6"
                }
            } catch (e: SecurityException) {
                noAccess = true
                current = null
                app.text = ""
                title.text = "Butuh akses notifikasi"
                artist.text = "Ketuk lalu aktifkan untuk launcher ini"
                playBtn.text = "\u25B6"
            } catch (e: Throwable) {
                title.text = "Musik tidak tersedia"
            }
        }
        val loop = object : Runnable {
            override fun run() {
                if (!c.isAttachedToWindow) return
                upd()
                c.postDelayed(this, 1500)
            }
        }
        c.post(loop)
        return c to upd
    }

    // ---------- waktu doa ----------
    private fun buildPrayer(size: Int): Pair<View, () -> Unit> {
        val c = card()
        val head = act.tv("\u271D Waktu doa", floatArrayOf(11f, 12f, 14f)[size], Th.accent, true).apply { maxLines = 1 }
        val name = act.tv("", floatArrayOf(12f, 16f, 19f)[size], Th.p.text, true).apply {
            maxLines = if (size == 0) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val time = act.tv("", floatArrayOf(20f, 28f, 36f)[size], Th.p.text, true).apply { maxLines = 1 }
        val left = act.tv("", floatArrayOf(10f, 12f, 14f)[size], Th.accent).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val later = act.tv("", 11f, Th.p.dim).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        c.addView(head)
        c.addView(name)
        c.addView(time)
        c.addView(left)
        if (size >= 1) c.addView(later)
        c.setOnClickListener {
            val next = Prayer.upcoming(prefs, System.currentTimeMillis()).firstOrNull()?.first
            val openSettings = {
                act.startActivity(
                    Intent(act, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_PAGE, "prayer")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            }
            if (next == null) openSettings() else act.showPrayerDialog(next, openSettings)
        }
        val upd: () -> Unit = {
            val now = System.currentTimeMillis()
            val up = Prayer.upcoming(prefs, now)
            if (up.isEmpty()) {
                name.text = "Belum ada waktu doa aktif"
                time.text = "--.--"
                left.text = "Ketuk untuk mengatur"
                later.text = ""
            } else {
                val (s, at) = up[0]
                name.text = if (size == 0) s.name.replace("Doa Malaikat Tuhan", "Angelus") else s.name
                time.text = Prayer.timeText(prefs, s)
                val mins = ((at - now) / 60000L).toInt() + 1
                left.text = if (size == 0) {
                    (if (mins >= 60) "${mins / 60} j ${mins % 60} mnt" else "$mins mnt") + " lagi"
                } else {
                    (if (mins >= 60) "${mins / 60} jam ${mins % 60} menit lagi" else "$mins menit lagi") +
                        (if (Prayer.masterOn(prefs)) "" else " · pengingat mati")
                }
                later.text = up.drop(1).take(2).joinToString("\n") { "${Prayer.timeText(prefs, it.first)}  ${it.first.name}" }
            }
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

    // ---------- Daftar tugas ----------
    private fun todoLoad(key: String): MutableList<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        for (line in (prefs.getString(key, "") ?: "").split("\n")) {
            if (line.length < 2 || line[1] != '|') continue
            out.add((line[0] == '1') to line.substring(2))
        }
        return out
    }

    private fun todoSave(key: String, list: List<Pair<Boolean, String>>) {
        prefs.edit().putString(key, list.joinToString("\n") { (if (it.first) "1|" else "0|") + it.second.replace("\n", " ") }).apply()
    }

    private fun buildTodo(it: WItem): Pair<View, () -> Unit> {
        val key = "widget_todo_${it.id}"
        val c = card()
        c.gravity = Gravity.TOP
        val head = act.tv("", 12f, Th.accent, true).apply { maxLines = 1 }
        c.addView(head)
        val max = intArrayOf(3, 5, 8)[it.size]
        val rows = ArrayList<TextView>()
        for (i in 0 until max) {
            val r = act.tv("", floatArrayOf(12f, 13f, 15f)[it.size], Th.p.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, act.dp(3), 0, 0)
            }
            rows.add(r)
            c.addView(r)
        }
        val upd: () -> Unit = {
            val list = todoLoad(key)
            val open = list.count { p -> !p.first }
            head.text = if (list.isEmpty()) "Daftar tugas" else "Daftar tugas  ·  $open tersisa"
            // yang belum selesai ditampilkan lebih dulu
            val shown = list.filter { p -> !p.first } + list.filter { p -> p.first }
            for ((i, r) in rows.withIndex()) {
                if (shown.isEmpty() && i == 0) {
                    r.text = "Ketuk untuk menambah tugas"
                    r.alpha = 0.55f
                    r.paintFlags = r.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    r.visibility = View.VISIBLE
                } else if (i < shown.size) {
                    val p = shown[i]
                    val more = i == rows.size - 1 && shown.size > rows.size
                    r.text = if (more) "+ ${shown.size - i} tugas lagi" else (if (p.first) "\u2611  " else "\u2610  ") + p.second
                    r.alpha = if (p.first && !more) 0.5f else 1f
                    r.paintFlags = if (p.first && !more) r.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    else r.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    r.visibility = View.VISIBLE
                } else {
                    r.visibility = View.GONE
                }
            }
        }
        c.setOnClickListener { showTodoDialog(key, upd) }
        return c to upd
    }

    private fun showTodoDialog(key: String, upd: () -> Unit) {
        val box = act.ll(true)
        val listBox = act.ll(true)
        val input = android.widget.EditText(act).apply {
            hint = "Tugas baru"
            setHintTextColor(Th.p.faint)
            setTextColor(Th.p.text)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = act.rounded(Th.p.input, act.dpf(14f), Th.p.stroke, act.dp(1))
            setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
        }
        lateinit var rebuild: () -> Unit
        rebuild = {
            listBox.removeAllViews()
            val list = todoLoad(key)
            if (list.isEmpty()) {
                listBox.addView(act.tv("Belum ada tugas.", 13f, Th.p.dim).apply { setPadding(0, act.dp(4), 0, act.dp(8)) })
            }
            for ((i, p) in list.withIndex()) {
                val row = act.ll(false, Gravity.CENTER_VERTICAL).apply { minimumHeight = act.dp(40) }
                val check = act.tv(if (p.first) "\u2611" else "\u2610", 20f, Th.accent).apply {
                    setPadding(act.dp(4), 0, act.dp(10), 0)
                    setOnClickListener {
                        val l = todoLoad(key)
                        if (i < l.size) l[i] = !l[i].first to l[i].second
                        todoSave(key, l)
                        upd()
                        rebuild()
                    }
                }
                val text = act.tv(p.second, 14f, Th.p.text).apply {
                    if (p.first) {
                        alpha = 0.5f
                        paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    }
                }
                val del = act.tv("\u2715", 16f, Th.p.dim).apply {
                    setPadding(act.dp(12), act.dp(6), act.dp(6), act.dp(6))
                    setOnClickListener {
                        val l = todoLoad(key)
                        if (i < l.size) l.removeAt(i)
                        todoSave(key, l)
                        upd()
                        rebuild()
                    }
                }
                row.addView(check)
                row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(del)
                listBox.addView(row)
            }
        }
        rebuild()
        fun add() {
            val v = input.text.toString().trim()
            if (v.isEmpty()) return
            val l = todoLoad(key)
            l.add(false to v.take(80))
            todoSave(key, l)
            input.setText("")
            upd()
            rebuild()
        }
        input.setOnEditorActionListener { _, _, _ ->
            add()
            true
        }
        val sv = android.widget.ScrollView(act)
        sv.addView(listBox)
        val h = minOf(act.dp(40 * max(1, todoLoad(key).size) + 12), (act.resources.displayMetrics.heightPixels * 0.4f).toInt())
        box.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h))
        val addRow = act.ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(0, act.dp(8), 0, 0) }
        addRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(act.textButton("Tambah", true) { add() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = act.dp(8) })
        box.addView(addRow)
        val d = act.showDialog("Daftar tugas", box, null, "Tutup")
        d.setOnDismissListener { upd() }
    }

    // ---------- Hitung mundur ----------
    private fun buildCountdown(it: WItem): Pair<View, () -> Unit> {
        val key = "widget_cd_${it.id}"
        val c = card()
        val title = act.tv("", floatArrayOf(11f, 12f, 14f)[it.size], Th.accent, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val big = act.tv("", floatArrayOf(30f, 40f, 54f)[it.size], Th.p.text, true).apply { maxLines = 1 }
        val sub = act.tv("", floatArrayOf(11f, 12f, 14f)[it.size], Th.p.dim).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        c.addView(title)
        c.addView(big)
        c.addView(sub)
        val upd: () -> Unit = {
            val raw = prefs.getString(key, "") ?: ""
            val parts = raw.split("|", limit = 2)
            val date = parts.getOrNull(0)?.split("-")?.mapNotNull { n -> n.toIntOrNull() }
            if (date == null || date.size != 3) {
                title.text = "Hitung mundur"
                big.text = "--"
                sub.text = "Ketuk untuk mengatur tanggal"
            } else {
                val target = java.util.Calendar.getInstance().apply {
                    set(date[0], date[1] - 1, date[2], 0, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val today = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val days = Math.round((target.timeInMillis - today.timeInMillis) / 86400000.0).toInt()
                title.text = parts.getOrNull(1)?.ifBlank { null } ?: "Hitung mundur"
                when {
                    days > 0 -> { big.text = days.toString(); sub.text = "hari lagi" }
                    days == 0 -> { big.text = "Hari ini"; sub.text = "" }
                    else -> { big.text = (-days).toString(); sub.text = "hari yang lalu" }
                }
            }
        }
        c.setOnClickListener {
            val raw = prefs.getString(key, "") ?: ""
            val parts = raw.split("|", limit = 2)
            act.promptDialog("Judul hitung mundur", parts.getOrNull(1) ?: "", "Contoh: Natal", "Lanjut") { name ->
                val cal = java.util.Calendar.getInstance()
                val old = parts.getOrNull(0)?.split("-")?.mapNotNull { n -> n.toIntOrNull() }
                if (old != null && old.size == 3) cal.set(old[0], old[1] - 1, old[2])
                android.app.DatePickerDialog(
                    Th.themed(act),
                    { _, y, m, d ->
                        prefs.edit().putString(key, "%04d-%02d-%02d|%s".format(y, m + 1, d, name.replace("|", " "))).apply()
                        upd()
                    },
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        return c to upd
    }

    // ---------- Kalender liturgi ----------
    private fun buildLiturgy(size: Int): Pair<View, () -> Unit> {
        val c = card()
        val head = act.tv("\u271D Kalender liturgi", floatArrayOf(10f, 11f, 13f)[size], Th.accent, true).apply { maxLines = 1 }
        val title = act.tv("", floatArrayOf(13f, 15f, 18f)[size], Th.p.text, true).apply {
            maxLines = if (size == 0) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = act.tv("", floatArrayOf(10f, 11f, 13f)[size], Th.p.dim).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        c.addView(head)
        c.addView(title)
        c.addView(sub)
        val upd: () -> Unit = {
            val d = Liturgy.today()
            title.text = d.title
            sub.text = d.season + "  \u00B7  " + d.colorName
            // titik warna liturgi di depan judul
            val dot = android.text.SpannableString("\u25CF  " + d.title)
            dot.setSpan(android.text.style.ForegroundColorSpan(d.color), 0, 1, 0)
            title.text = dot
        }
        c.setOnClickListener { showLiturgyDialog() }
        return c to upd
    }

    private fun showLiturgyDialog() {
        val d = Liturgy.today()
        val fmt = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale("id"))
        val now = java.util.Date()
        val box = act.ll(true)
        box.addView(act.tv(fmt.format(now), 12f, Th.accent, true))
        val dotTitle = android.text.SpannableString("\u25CF  " + d.title)
        dotTitle.setSpan(android.text.style.ForegroundColorSpan(d.color), 0, 1, 0)
        box.addView(act.tv("", 17f, Th.p.text, true).apply {
            text = dotTitle
            setPadding(0, act.dp(6), 0, act.dp(4))
        })
        box.addView(act.tv(d.season + (if (d.rank.isNotEmpty()) "  \u00B7  " + d.rank else "") + "  \u00B7  warna " + d.colorName.lowercase(), 13f, Th.p.dim))
        box.addView(act.tv("Dihitung otomatis tanpa internet. Untuk kepastian (mis. peringatan santo/santa setempat), ikuti kalender liturgi KWI atau paroki Anda.", 11f, Th.p.faint).apply {
            setPadding(0, act.dp(10), 0, 0)
        })
        var dlg: android.app.Dialog? = null
        act.readingApp()?.let { (label, intent) ->
            box.addView(act.linkText("Buka $label (bacaan dan doa)  \u203A") {
                dlg?.dismiss()
                try { act.startActivity(intent) } catch (e: Exception) { Toast.makeText(act, "Tidak bisa membuka $label", Toast.LENGTH_SHORT).show() }
            })
        }
        box.addView(act.linkText("Cari bacaan hari ini di web  \u203A") {
            dlg?.dismiss()
            val q = "bacaan harian katolik " + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("id")).format(now)
            try {
                act.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")))
                )
            } catch (e: Exception) {
                Toast.makeText(act, "Tidak ada peramban untuk membuka pencarian", Toast.LENGTH_SHORT).show()
            }
        })
        dlg = act.showDialog("Kalender liturgi", box, null, "Tutup")
    }

    // ---------- Agenda (kalender HP) ----------
    private fun buildAgenda(size: Int): Pair<View, () -> Unit> {
        val c = card()
        c.gravity = Gravity.TOP
        val head = act.tv("Agenda", floatArrayOf(11f, 12f, 14f)[size], Th.accent, true).apply { maxLines = 1 }
        c.addView(head)
        val n = intArrayOf(3, 5, 8)[size]
        val rows = ArrayList<TextView>()
        for (i in 0 until n) {
            val r = act.tv("", floatArrayOf(11f, 12f, 14f)[size], Th.p.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, act.dp(3), 0, 0)
            }
            rows.add(r)
            c.addView(r)
        }
        val upd: () -> Unit = {
            val granted = act.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val lines = ArrayList<String>()
            if (!granted) {
                lines.add("Ketuk untuk mengizinkan akses kalender")
            } else {
                val ev = readEvents(n)
                if (ev.isEmpty()) lines.add("Tidak ada acara 7 hari ke depan") else lines.addAll(ev)
            }
            for ((i, r) in rows.withIndex()) {
                if (i < lines.size) {
                    r.text = lines[i]
                    r.visibility = View.VISIBLE
                } else {
                    r.visibility = View.GONE
                }
            }
        }
        c.setOnClickListener {
            if (act.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 7401)
            } else {
                try {
                    act.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Toast.makeText(act, "Aplikasi kalender tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return c to upd
    }

    private fun readEvents(limit: Int): List<String> {
        val out = ArrayList<String>()
        try {
            val now = System.currentTimeMillis()
            val b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(b, now)
            android.content.ContentUris.appendId(b, now + 7L * 86400000L)
            val cur = act.contentResolver.query(
                b.build(),
                arrayOf(
                    android.provider.CalendarContract.Instances.TITLE,
                    android.provider.CalendarContract.Instances.BEGIN,
                    android.provider.CalendarContract.Instances.ALL_DAY
                ),
                null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC"
            )
            cur?.use {
                val dayFmt = java.text.SimpleDateFormat("EEE", java.util.Locale("id"))
                val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("id"))
                while (it.moveToNext() && out.size < limit) {
                    val title = it.getString(0) ?: "(tanpa judul)"
                    val begin = it.getLong(1)
                    val allDay = it.getInt(2) == 1
                    val whenText = if (allDay) {
                        // acara seharian disimpan dalam UTC
                        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = begin }
                        val local = java.util.Calendar.getInstance().apply {
                            set(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
                        }
                        dayFmt.format(local.time) + " " + local.get(java.util.Calendar.DAY_OF_MONTH) + "  seharian"
                    } else {
                        val dt = java.util.Date(begin)
                        dayFmt.format(dt) + " " + timeFmt.format(dt)
                    }
                    out.add("$whenText  $title")
                }
            }
        } catch (e: Throwable) {
            out.clear()
        }
        return out
    }
}
