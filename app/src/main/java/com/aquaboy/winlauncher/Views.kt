package com.aquaboy.winlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Wallpaper bawaan: gradasi dari warna aksen, mengikuti tema terang/gelap. */
class WallpaperView(ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bg: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val a = Th.accent
        val colors = if (Th.dark) {
            intArrayOf(
                Th.blend(a, 0xFF000000.toInt(), 0.90f),
                Th.blend(a, 0xFF000000.toInt(), 0.62f),
                Th.blend(a, 0xFF000000.toInt(), 0.20f),
                Th.blend(a, 0xFFFFFFFF.toInt(), 0.35f)
            )
        } else {
            intArrayOf(
                Th.blend(a, 0xFFFFFFFF.toInt(), 0.90f),
                Th.blend(a, 0xFFFFFFFF.toInt(), 0.65f),
                Th.blend(a, 0xFFFFFFFF.toInt(), 0.30f),
                Th.blend(a, 0xFF000000.toInt(), 0.10f)
            )
        }
        bg = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), colors, floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = bg
        c.drawRect(0f, 0f, w, h, p)

        val a = Th.accent
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val cx = w * 0.5f
        val cy = h * 0.46f
        val r = min(w, h) * 0.46f
        for (i in 0 until 6) {
            c.save()
            c.rotate(i * 60f + 12f, cx, cy)
            val top = if (i % 2 == 0) Th.blend(a, white, 0.55f) else Th.blend(a, 0xFF9AA8FF.toInt(), 0.5f)
            val bottom = if (i % 2 == 0) Th.blend(a, black, 0.35f) else Th.blend(a, black, 0.55f)
            p.shader = LinearGradient(
                cx, cy - r, cx, cy,
                intArrayOf(Th.withAlpha(top, if (Th.dark) 0xB0 else 0x90), Th.withAlpha(bottom, 0x22)),
                null,
                Shader.TileMode.CLAMP
            )
            c.drawOval(RectF(cx - r * 0.30f, cy - r, cx + r * 0.30f, cy), p)
            c.restore()
        }

        p.shader = RadialGradient(
            cx, cy, r * 0.5f,
            intArrayOf(0x66FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 0.5f, p)

        val vig = if (Th.dark) 0x55000A24 else 0x330A2A55
        p.shader = LinearGradient(0f, h * 0.7f, 0f, h, 0x00000000, vig, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.7f, w, h, p)
        p.shader = null
    }
}

/** Ikon vektor sederhana yang digambar langsung pada kanvas (koordinat satuan 0..1). */
class GlyphView(ctx: Context, var kind: Kind) : View(ctx) {

    enum class Kind {
        START, SEARCH, NET, VOLUME, BATTERY, POWER,
        SYSTEM, BLUETOOTH, PERSONALIZE, APPS, ACCOUNTS, TIME, ACCESS, PRIVACY, UPDATE, TASKBAR,
        DRIVE, INFO, LANGUAGE, HOME, GEAR, MOON, SUN, BELL, PHOTO, AIRPLANE, CAST, DND, PIN, MENU,
        CHEV_RIGHT, CHEV_DOWN, CHEV_UP, BACK, FORWARD, UP, CLOSE, CHECK, MORE, PLUS,
        FOLDER, FILE, IMAGE, MUSIC, VIDEO, DOC, ARCHIVE, APK,
        DOWNLOAD, NEW_FOLDER, COPY, CUT, PASTE, DELETE, RENAME, SHARE, SORT, VIEW_LIST, VIEW_GRID,
        STAR, EYE, SELECT_ALL
    }

    /** Warna ikon; null = warna teks tema (atau warna bawaan untuk ikon jenis berkas). */
    var tint: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 0 = wifi, 1 = seluler, 2 = tidak ada koneksi */
    var net: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var level: Int = 100
        set(value) {
            field = value
            invalidate()
        }

    var charging: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun defaultColor(k: Kind): Int = when (k) {
        Kind.START -> Th.accent
        Kind.FOLDER -> 0xFFFFC83D.toInt()
        Kind.FILE -> 0xFF8A93A3.toInt()
        Kind.IMAGE -> 0xFF1FA67A.toInt()
        Kind.MUSIC -> 0xFFE0507A.toInt()
        Kind.VIDEO -> 0xFF8E5BE0.toInt()
        Kind.DOC -> 0xFF2F7BE8.toInt()
        Kind.ARCHIVE -> 0xFFE08A2F.toInt()
        Kind.APK -> 0xFF3DB45A.toInt()
        else -> Th.p.text
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)
        if (m <= 0f) return
        val col = tint ?: defaultColor(kind)

        c.save()
        c.translate((w - m) / 2f, (h - m) / 2f)
        c.scale(m, m)

        p.shader = null
        p.color = col
        p.strokeWidth = 0.085f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND

        fun st() { p.style = Paint.Style.STROKE }
        fun fl() { p.style = Paint.Style.FILL }
        fun ln(a: Float, b: Float, x: Float, y: Float) { c.drawLine(a, b, x, y, p) }
        fun circ(cx: Float, cy: Float, r: Float) { c.drawCircle(cx, cy, r, p) }
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) { c.drawRoundRect(l, t, r, b, rad, rad, p) }
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, p)
        }
        fun poly(close: Boolean, vararg v: Float) {
            val path = Path()
            path.moveTo(v[0], v[1])
            var i = 2
            while (i + 1 < v.size) {
                path.lineTo(v[i], v[i + 1])
                i += 2
            }
            if (close) path.close()
            c.drawPath(path, p)
        }
        fun page() {
            fl()
            p.color = col
            poly(true, 0.22f, 0.08f, 0.62f, 0.08f, 0.80f, 0.26f, 0.80f, 0.92f, 0.22f, 0.92f)
            p.color = 0x99FFFFFF.toInt()
            poly(true, 0.62f, 0.08f, 0.62f, 0.26f, 0.80f, 0.26f)
            p.color = 0xFFFFFFFF.toInt()
        }

        when (kind) {
            Kind.START -> {
                fl()
                val s = 0.74f
                val gap = s * 0.09f
                val sq = (s - gap) / 2f
                val left = (1f - s) / 2f
                for (i in 0..1) {
                    for (j in 0..1) {
                        val x = left + i * (sq + gap)
                        val y = left + j * (sq + gap)
                        rr(x, y, x + sq, y + sq, sq * 0.1f)
                    }
                }
            }

            Kind.SEARCH -> {
                st()
                circ(0.43f, 0.43f, 0.25f)
                ln(0.61f, 0.61f, 0.86f, 0.86f)
            }

            Kind.NET -> {
                st()
                if (net == 2) p.color = Th.withAlpha(col, 0x88)
                if (net == 1) {
                    for (k in 0..3) {
                        val x = 0.2f + k * 0.2f
                        ln(x, 0.85f, x, 0.85f - (0.2f + 0.15f * k))
                    }
                } else {
                    for (k in 1..3) arc(0.5f, 0.8f, 0.2f * k, -135f, 90f)
                    fl()
                    circ(0.5f, 0.8f, 0.06f)
                    st()
                }
                if (net == 2) ln(0.15f, 0.15f, 0.85f, 0.85f)
            }

            Kind.VOLUME -> {
                fl()
                poly(true, 0.14f, 0.40f, 0.30f, 0.40f, 0.50f, 0.22f, 0.50f, 0.78f, 0.30f, 0.60f, 0.14f, 0.60f)
                st()
                for (k in 1..2) arc(0.48f, 0.5f, 0.2f * k + 0.05f, -45f, 90f)
            }

            Kind.BATTERY -> {
                val soft = Th.withAlpha(col, 0xAA)
                p.color = soft
                st()
                p.strokeWidth = 0.07f
                rr(0.04f, 0.28f, 0.86f, 0.72f, 0.1f)
                fl()
                rr(0.89f, 0.42f, 0.96f, 0.58f, 0.03f)
                val inset = 0.09f
                val fw = (0.82f - 2 * inset) * level.coerceIn(0, 100) / 100f
                p.color = when {
                    charging -> Th.good
                    level <= 15 -> Th.danger
                    else -> col
                }
                if (fw > 0f) rr(0.04f + inset, 0.28f + inset, 0.04f + inset + fw, 0.72f - inset, 0.04f)
            }

            Kind.POWER -> {
                st()
                arc(0.5f, 0.54f, 0.3f, -60f, 300f)
                ln(0.5f, 0.12f, 0.5f, 0.46f)
            }

            Kind.SYSTEM -> {
                st()
                rr(0.1f, 0.18f, 0.9f, 0.68f, 0.07f)
                ln(0.5f, 0.68f, 0.5f, 0.82f)
                ln(0.3f, 0.84f, 0.7f, 0.84f)
            }

            Kind.BLUETOOTH -> {
                st()
                poly(false, 0.3f, 0.32f, 0.68f, 0.68f, 0.5f, 0.86f, 0.5f, 0.14f, 0.68f, 0.32f, 0.3f, 0.68f)
            }

            Kind.PERSONALIZE -> {
                st()
                ln(0.82f, 0.16f, 0.44f, 0.56f)
                circ(0.32f, 0.68f, 0.15f)
                ln(0.5f, 0.5f, 0.58f, 0.58f)
            }

            Kind.APPS, Kind.VIEW_GRID -> {
                st()
                rr(0.14f, 0.14f, 0.45f, 0.45f, 0.05f)
                rr(0.55f, 0.14f, 0.86f, 0.45f, 0.05f)
                rr(0.14f, 0.55f, 0.45f, 0.86f, 0.05f)
                rr(0.55f, 0.55f, 0.86f, 0.86f, 0.05f)
            }

            Kind.ACCOUNTS -> {
                st()
                circ(0.5f, 0.32f, 0.17f)
                c.drawArc(RectF(0.2f, 0.56f, 0.8f, 1.1f), 180f, 180f, false, p)
            }

            Kind.TIME -> {
                st()
                circ(0.5f, 0.5f, 0.38f)
                ln(0.5f, 0.5f, 0.5f, 0.27f)
                ln(0.5f, 0.5f, 0.67f, 0.6f)
            }

            Kind.ACCESS -> {
                st()
                circ(0.5f, 0.5f, 0.4f)
                ln(0.28f, 0.42f, 0.72f, 0.42f)
                ln(0.5f, 0.42f, 0.5f, 0.6f)
                ln(0.5f, 0.6f, 0.4f, 0.76f)
                ln(0.5f, 0.6f, 0.6f, 0.76f)
            }

            Kind.PRIVACY -> {
                st()
                val path = Path()
                path.moveTo(0.5f, 0.1f)
                path.lineTo(0.82f, 0.22f)
                path.lineTo(0.82f, 0.5f)
                path.quadTo(0.82f, 0.78f, 0.5f, 0.92f)
                path.quadTo(0.18f, 0.78f, 0.18f, 0.5f)
                path.lineTo(0.18f, 0.22f)
                path.close()
                c.drawPath(path, p)
            }

            Kind.UPDATE -> {
                st()
                arc(0.5f, 0.5f, 0.32f, 30f, 270f)
                poly(false, 0.78f, 0.7f, 0.78f, 0.48f, 0.56f, 0.7f)
            }

            Kind.TASKBAR -> {
                st()
                rr(0.1f, 0.2f, 0.9f, 0.8f, 0.07f)
                fl()
                rr(0.1f, 0.6f, 0.9f, 0.8f, 0.05f)
            }

            Kind.DRIVE -> {
                st()
                rr(0.1f, 0.34f, 0.9f, 0.7f, 0.07f)
                ln(0.2f, 0.52f, 0.5f, 0.52f)
                fl()
                circ(0.76f, 0.52f, 0.035f)
            }

            Kind.INFO -> {
                st()
                circ(0.5f, 0.5f, 0.4f)
                ln(0.5f, 0.46f, 0.5f, 0.72f)
                fl()
                circ(0.5f, 0.3f, 0.045f)
            }

            Kind.LANGUAGE -> {
                st()
                circ(0.5f, 0.5f, 0.4f)
                c.drawOval(RectF(0.32f, 0.1f, 0.68f, 0.9f), p)
                ln(0.1f, 0.5f, 0.9f, 0.5f)
            }

            Kind.HOME -> {
                st()
                poly(false, 0.1f, 0.48f, 0.5f, 0.14f, 0.9f, 0.48f)
                poly(false, 0.22f, 0.42f, 0.22f, 0.86f, 0.78f, 0.86f, 0.78f, 0.42f)
            }

            Kind.GEAR -> {
                st()
                circ(0.5f, 0.5f, 0.2f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val x1 = 0.5f + (0.3f * cos(a)).toFloat()
                    val y1 = 0.5f + (0.3f * sin(a)).toFloat()
                    val x2 = 0.5f + (0.42f * cos(a)).toFloat()
                    val y2 = 0.5f + (0.42f * sin(a)).toFloat()
                    ln(x1, y1, x2, y2)
                }
                circ(0.5f, 0.5f, 0.31f)
            }

            Kind.MOON -> {
                fl()
                val outer = Path()
                outer.addCircle(0.5f, 0.5f, 0.38f, Path.Direction.CW)
                val cut = Path()
                cut.addCircle(0.68f, 0.38f, 0.32f, Path.Direction.CW)
                outer.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(outer, p)
            }

            Kind.SUN -> {
                st()
                circ(0.5f, 0.5f, 0.17f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    ln(
                        0.5f + (0.28f * cos(a)).toFloat(), 0.5f + (0.28f * sin(a)).toFloat(),
                        0.5f + (0.4f * cos(a)).toFloat(), 0.5f + (0.4f * sin(a)).toFloat()
                    )
                }
            }

            Kind.BELL -> {
                st()
                val path = Path()
                path.moveTo(0.24f, 0.7f)
                path.lineTo(0.3f, 0.64f)
                path.lineTo(0.3f, 0.45f)
                path.quadTo(0.3f, 0.18f, 0.5f, 0.18f)
                path.quadTo(0.7f, 0.18f, 0.7f, 0.45f)
                path.lineTo(0.7f, 0.64f)
                path.lineTo(0.76f, 0.7f)
                path.close()
                c.drawPath(path, p)
                fl()
                circ(0.5f, 0.82f, 0.06f)
            }

            Kind.PHOTO -> {
                st()
                rr(0.12f, 0.2f, 0.88f, 0.8f, 0.07f)
                poly(false, 0.15f, 0.75f, 0.42f, 0.5f, 0.6f, 0.66f, 0.72f, 0.56f, 0.85f, 0.72f)
                fl()
                circ(0.36f, 0.38f, 0.06f)
            }

            Kind.AIRPLANE -> {
                st()
                ln(0.5f, 0.1f, 0.5f, 0.9f)
                ln(0.5f, 0.45f, 0.12f, 0.62f)
                ln(0.5f, 0.45f, 0.88f, 0.62f)
                ln(0.5f, 0.78f, 0.3f, 0.88f)
                ln(0.5f, 0.78f, 0.7f, 0.88f)
            }

            Kind.CAST -> {
                st()
                poly(false, 0.12f, 0.28f, 0.88f, 0.28f, 0.88f, 0.76f, 0.58f, 0.76f)
                arc(0.12f, 0.8f, 0.18f, -90f, 90f)
                arc(0.12f, 0.8f, 0.34f, -90f, 90f)
                fl()
                circ(0.12f, 0.8f, 0.035f)
            }

            Kind.DND -> {
                st()
                circ(0.5f, 0.5f, 0.4f)
                ln(0.28f, 0.5f, 0.72f, 0.5f)
            }

            Kind.PIN -> {
                st()
                circ(0.5f, 0.38f, 0.23f)
                ln(0.32f, 0.54f, 0.5f, 0.9f)
                ln(0.68f, 0.54f, 0.5f, 0.9f)
                fl()
                circ(0.5f, 0.38f, 0.07f)
            }

            Kind.MENU -> {
                st()
                ln(0.15f, 0.27f, 0.85f, 0.27f)
                ln(0.15f, 0.5f, 0.85f, 0.5f)
                ln(0.15f, 0.73f, 0.85f, 0.73f)
            }

            Kind.CHEV_RIGHT -> {
                st()
                poly(false, 0.38f, 0.2f, 0.64f, 0.5f, 0.38f, 0.8f)
            }

            Kind.CHEV_DOWN -> {
                st()
                poly(false, 0.2f, 0.38f, 0.5f, 0.64f, 0.8f, 0.38f)
            }

            Kind.CHEV_UP -> {
                st()
                poly(false, 0.2f, 0.62f, 0.5f, 0.36f, 0.8f, 0.62f)
            }

            Kind.BACK -> {
                st()
                ln(0.85f, 0.5f, 0.15f, 0.5f)
                poly(false, 0.4f, 0.25f, 0.15f, 0.5f, 0.4f, 0.75f)
            }

            Kind.FORWARD -> {
                st()
                ln(0.15f, 0.5f, 0.85f, 0.5f)
                poly(false, 0.6f, 0.25f, 0.85f, 0.5f, 0.6f, 0.75f)
            }

            Kind.UP -> {
                st()
                ln(0.5f, 0.85f, 0.5f, 0.15f)
                poly(false, 0.25f, 0.4f, 0.5f, 0.15f, 0.75f, 0.4f)
            }

            Kind.CLOSE -> {
                st()
                ln(0.22f, 0.22f, 0.78f, 0.78f)
                ln(0.78f, 0.22f, 0.22f, 0.78f)
            }

            Kind.CHECK -> {
                st()
                poly(false, 0.2f, 0.52f, 0.42f, 0.72f, 0.8f, 0.28f)
            }

            Kind.MORE -> {
                fl()
                circ(0.5f, 0.2f, 0.065f)
                circ(0.5f, 0.5f, 0.065f)
                circ(0.5f, 0.8f, 0.065f)
            }

            Kind.PLUS -> {
                st()
                ln(0.5f, 0.18f, 0.5f, 0.82f)
                ln(0.18f, 0.5f, 0.82f, 0.5f)
            }

            Kind.FOLDER -> {
                fl()
                p.color = Th.blend(col, 0xFF000000.toInt(), 0.18f)
                poly(true, 0.08f, 0.2f, 0.38f, 0.2f, 0.46f, 0.3f, 0.92f, 0.3f, 0.92f, 0.82f, 0.08f, 0.82f)
                p.color = col
                rr(0.08f, 0.38f, 0.92f, 0.82f, 0.05f)
            }

            Kind.NEW_FOLDER -> {
                st()
                poly(true, 0.08f, 0.22f, 0.38f, 0.22f, 0.46f, 0.32f, 0.92f, 0.32f, 0.92f, 0.82f, 0.08f, 0.82f)
                ln(0.5f, 0.45f, 0.5f, 0.69f)
                ln(0.38f, 0.57f, 0.62f, 0.57f)
            }

            Kind.FILE -> page()

            Kind.IMAGE -> {
                page()
                fl()
                circ(0.42f, 0.42f, 0.06f)
                st()
                p.strokeWidth = 0.06f
                poly(false, 0.28f, 0.8f, 0.45f, 0.6f, 0.55f, 0.72f, 0.62f, 0.64f, 0.74f, 0.8f)
            }

            Kind.MUSIC -> {
                page()
                fl()
                circ(0.44f, 0.72f, 0.07f)
                st()
                p.strokeWidth = 0.06f
                ln(0.51f, 0.72f, 0.51f, 0.44f)
                ln(0.51f, 0.44f, 0.66f, 0.5f)
            }

            Kind.VIDEO -> {
                page()
                fl()
                poly(true, 0.4f, 0.44f, 0.4f, 0.76f, 0.66f, 0.6f)
            }

            Kind.DOC -> {
                page()
                st()
                p.strokeWidth = 0.05f
                ln(0.32f, 0.46f, 0.7f, 0.46f)
                ln(0.32f, 0.6f, 0.7f, 0.6f)
                ln(0.32f, 0.74f, 0.58f, 0.74f)
            }

            Kind.ARCHIVE -> {
                page()
                st()
                p.strokeWidth = 0.05f
                ln(0.51f, 0.2f, 0.51f, 0.3f)
                ln(0.51f, 0.38f, 0.51f, 0.48f)
                rr(0.43f, 0.54f, 0.59f, 0.78f, 0.03f)
            }

            Kind.APK -> {
                page()
                fl()
                rr(0.34f, 0.5f, 0.68f, 0.78f, 0.05f)
                st()
                p.strokeWidth = 0.05f
                ln(0.4f, 0.38f, 0.45f, 0.46f)
                ln(0.62f, 0.38f, 0.57f, 0.46f)
            }

            Kind.DOWNLOAD -> {
                st()
                ln(0.5f, 0.14f, 0.5f, 0.66f)
                poly(false, 0.28f, 0.46f, 0.5f, 0.68f, 0.72f, 0.46f)
                ln(0.2f, 0.86f, 0.8f, 0.86f)
            }

            Kind.COPY -> {
                st()
                rr(0.36f, 0.16f, 0.84f, 0.68f, 0.06f)
                rr(0.16f, 0.32f, 0.64f, 0.86f, 0.06f)
            }

            Kind.CUT -> {
                st()
                circ(0.3f, 0.76f, 0.11f)
                circ(0.7f, 0.76f, 0.11f)
                ln(0.36f, 0.66f, 0.72f, 0.14f)
                ln(0.64f, 0.66f, 0.28f, 0.14f)
            }

            Kind.PASTE -> {
                st()
                rr(0.2f, 0.2f, 0.8f, 0.9f, 0.06f)
                fl()
                rr(0.36f, 0.08f, 0.64f, 0.3f, 0.04f)
            }

            Kind.DELETE -> {
                st()
                ln(0.14f, 0.28f, 0.86f, 0.28f)
                poly(false, 0.38f, 0.28f, 0.38f, 0.14f, 0.62f, 0.14f, 0.62f, 0.28f)
                poly(false, 0.24f, 0.28f, 0.3f, 0.88f, 0.7f, 0.88f, 0.76f, 0.28f)
                ln(0.42f, 0.44f, 0.42f, 0.74f)
                ln(0.58f, 0.44f, 0.58f, 0.74f)
            }

            Kind.RENAME -> {
                st()
                ln(0.78f, 0.18f, 0.3f, 0.66f)
                poly(false, 0.3f, 0.66f, 0.2f, 0.82f, 0.36f, 0.72f)
                ln(0.46f, 0.86f, 0.86f, 0.86f)
            }

            Kind.SHARE -> {
                st()
                ln(0.26f, 0.5f, 0.74f, 0.26f)
                ln(0.26f, 0.5f, 0.74f, 0.74f)
                fl()
                circ(0.76f, 0.24f, 0.1f)
                circ(0.24f, 0.5f, 0.1f)
                circ(0.76f, 0.76f, 0.1f)
            }

            Kind.SORT -> {
                st()
                ln(0.14f, 0.26f, 0.86f, 0.26f)
                ln(0.14f, 0.5f, 0.66f, 0.5f)
                ln(0.14f, 0.74f, 0.46f, 0.74f)
            }

            Kind.VIEW_LIST -> {
                st()
                ln(0.34f, 0.26f, 0.88f, 0.26f)
                ln(0.34f, 0.5f, 0.88f, 0.5f)
                ln(0.34f, 0.74f, 0.88f, 0.74f)
                fl()
                circ(0.15f, 0.26f, 0.04f)
                circ(0.15f, 0.5f, 0.04f)
                circ(0.15f, 0.74f, 0.04f)
            }

            Kind.STAR -> {
                st()
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 0.4f else 0.17f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = 0.5f + (r * cos(a)).toFloat()
                    val y = 0.54f + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }

            Kind.EYE -> {
                st()
                val path = Path()
                path.moveTo(0.08f, 0.5f)
                path.quadTo(0.5f, 0.1f, 0.92f, 0.5f)
                path.quadTo(0.5f, 0.9f, 0.08f, 0.5f)
                c.drawPath(path, p)
                fl()
                circ(0.5f, 0.5f, 0.12f)
            }

            Kind.SELECT_ALL -> {
                st()
                rr(0.14f, 0.14f, 0.86f, 0.86f, 0.08f)
                poly(false, 0.32f, 0.52f, 0.46f, 0.66f, 0.7f, 0.36f)
            }
        }
        c.restore()
    }
}
