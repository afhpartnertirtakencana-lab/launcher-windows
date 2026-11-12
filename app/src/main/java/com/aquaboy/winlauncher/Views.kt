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
import kotlin.math.min

/** Wallpaper bawaan: gradasi biru dengan kelopak abstrak (desain asli, bukan salinan wallpaper Microsoft). */
class WallpaperView(ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bg: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg = LinearGradient(
            0f, 0f, w * 0.6f, h.toFloat(),
            intArrayOf(
                0xFF04122F.toInt(),
                0xFF0A3A94.toInt(),
                0xFF1E7FE0.toInt(),
                0xFF8FD3FF.toInt()
            ),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = bg
        c.drawRect(0f, 0f, w, h, p)

        val cx = w * 0.5f
        val cy = h * 0.46f
        val r = min(w, h) * 0.46f
        for (i in 0 until 6) {
            c.save()
            c.rotate(i * 60f + 12f, cx, cy)
            val top = if (i % 2 == 0) 0xFF7FD6FF.toInt() else 0xFF9AA8FF.toInt()
            val bottom = if (i % 2 == 0) 0xFF1346C8.toInt() else 0xFF0B2A86.toInt()
            p.shader = LinearGradient(
                cx, cy - r, cx, cy,
                intArrayOf(withAlpha(top, 0xB0), withAlpha(bottom, 0x22)),
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

        p.shader = LinearGradient(0f, h * 0.7f, 0f, h, 0x00000000, 0x55000A24, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.7f, w, h, p)
        p.shader = null
    }
}

/** Ikon vektor kecil yang digambar langsung (logo Start, cari, wifi, volume, baterai, power). */
class GlyphView(ctx: Context, private val kind: Kind) : View(ctx) {

    enum class Kind { START, SEARCH, NET, VOLUME, BATTERY, POWER }

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

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = min(w, h)
        p.shader = null
        p.strokeWidth = m * 0.09f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val white = 0xFFFFFFFF.toInt()

        when (kind) {
            Kind.START -> {
                p.style = Paint.Style.FILL
                p.color = 0xFF4CC2FF.toInt()
                val s = m * 0.74f
                val gap = s * 0.09f
                val sq = (s - gap) / 2f
                val left = (w - s) / 2f
                val top = (h - s) / 2f
                val rad = sq * 0.1f
                for (i in 0..1) {
                    for (j in 0..1) {
                        val x = left + i * (sq + gap)
                        val y = top + j * (sq + gap)
                        c.drawRoundRect(x, y, x + sq, y + sq, rad, rad, p)
                    }
                }
            }

            Kind.SEARCH -> {
                p.style = Paint.Style.STROKE
                p.color = white
                c.drawCircle(w * 0.44f, h * 0.44f, m * 0.24f, p)
                c.drawLine(w * 0.44f + m * 0.17f, h * 0.44f + m * 0.17f, w * 0.82f, h * 0.82f, p)
            }

            Kind.NET -> {
                p.style = Paint.Style.STROKE
                p.color = if (net == 2) 0x88FFFFFF.toInt() else white
                if (net == 1) {
                    for (k in 0..3) {
                        val x = w / 2f - m * 0.3f + k * m * 0.2f
                        val bar = m * (0.2f + 0.15f * k)
                        c.drawLine(x, h * 0.85f, x, h * 0.85f - bar, p)
                    }
                } else {
                    val cx = w / 2f
                    val cy = h * 0.78f
                    for (k in 1..3) {
                        val r = m * 0.2f * k
                        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -135f, 90f, false, p)
                    }
                    p.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, m * 0.06f, p)
                    p.style = Paint.Style.STROKE
                }
                if (net == 2) {
                    c.drawLine(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.85f, p)
                }
            }

            Kind.VOLUME -> {
                p.style = Paint.Style.FILL
                p.color = white
                val path = Path()
                path.moveTo(w * 0.18f, h * 0.40f)
                path.lineTo(w * 0.34f, h * 0.40f)
                path.lineTo(w * 0.52f, h * 0.24f)
                path.lineTo(w * 0.52f, h * 0.76f)
                path.lineTo(w * 0.34f, h * 0.60f)
                path.lineTo(w * 0.18f, h * 0.60f)
                path.close()
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                val cx = w * 0.5f
                val cy = h * 0.5f
                for (k in 1..2) {
                    val r = m * (0.2f * k + 0.05f)
                    c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -45f, 90f, false, p)
                }
            }

            Kind.BATTERY -> {
                val body = RectF(w * 0.08f, h * 0.28f, w * 0.84f, h * 0.72f)
                val soft = 0xAAFFFFFF.toInt()
                p.style = Paint.Style.STROKE
                p.color = soft
                p.strokeWidth = m * 0.07f
                c.drawRoundRect(body, m * 0.1f, m * 0.1f, p)
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(w * 0.87f, h * 0.42f, w * 0.93f, h * 0.58f), 2f, 2f, p)
                val inset = m * 0.1f
                val fillW = (body.width() - 2 * inset) * level.coerceIn(0, 100) / 100f
                p.color = when {
                    charging -> 0xFF6CCB5F.toInt()
                    level <= 15 -> 0xFFFF99A4.toInt()
                    else -> white
                }
                if (fillW > 0f) {
                    c.drawRoundRect(
                        RectF(body.left + inset, body.top + inset, body.left + inset + fillW, body.bottom - inset),
                        2f, 2f, p
                    )
                }
            }

            Kind.POWER -> {
                p.style = Paint.Style.STROKE
                p.color = white
                val cx = w / 2f
                val cy = h / 2f + m * 0.04f
                val r = m * 0.28f
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
                c.drawLine(cx, cy - r * 1.25f, cx, cy - r * 0.1f, p)
            }
        }
    }
}
