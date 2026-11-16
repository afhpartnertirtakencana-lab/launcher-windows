package com.aquaboy.winlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/** Keadaan satu jendela mengambang milik launcher (Pengaturan atau File Explorer). */
internal class FState(val key: String) {
    var minimized = false
    var maximized = false
    var saved: Rect? = null
}

/** Daftar jendela mengambang yang sedang hidup, untuk taskbar dan pengaturan urutan. */
internal object Wins {
    private val states = LinkedHashMap<Activity, FState>()

    fun register(a: Activity, key: String) {
        states[a] = FState(key)
    }

    fun unregister(a: Activity) {
        states.remove(a)
    }

    fun isFloating(a: Activity): Boolean = states.containsKey(a)

    fun state(a: Activity): FState? = states[a]

    fun count(key: String): Int = states.values.count { it.key == key }

    /** Jendela yang paling pantas dimunculkan: yang diminimalkan dulu, jika tidak ada yang paling baru. */
    fun find(className: String): Activity? {
        val key = keyOf(className)
        val all = states.entries.filter { it.value.key == key && !it.key.isFinishing }
        return all.firstOrNull { it.value.minimized }?.key ?: all.lastOrNull()?.key
    }

    fun keyOf(className: String): String = if (className.endsWith("FilesActivity")) "files" else "settings"

    fun raise(a: Activity) {
        val st = states[a] ?: return
        if (st.minimized) a.floatRestore() else a.bringTaskToFront()
    }
}

internal fun Activity.bringTaskToFront() {
    try {
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(taskId, 0)
    } catch (e: Throwable) {
        // tanpa izin atau ditolak sistem: jendela tetap di urutan semula
    }
}

/** Jadikan jendela activity ini melayang: ukuran dan posisi sendiri, area luar tetap bisa disentuh. */
internal fun Activity.setupFloatingWindow(key: String) {
    val dm = resources.displayMetrics
    val sp = getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
    val w = sp.getInt("fw_${key}_w", min((dm.widthPixels * 0.72f).toInt(), dp(1000)))
        .coerceIn(min(dp(MIN_W), dm.widthPixels), dm.widthPixels)
    val h = sp.getInt("fw_${key}_h", (dm.heightPixels * 0.78f).toInt())
        .coerceIn(min(dp(MIN_H), dm.heightPixels), dm.heightPixels)
    val n = Wins.count(key)
    val step = dp(32)
    val x = (sp.getInt("fw_${key}_x", (dm.widthPixels - w) / 2) + n * step).coerceIn(0, max(0, dm.widthPixels - w))
    val y = (sp.getInt("fw_${key}_y", (dm.heightPixels - h) / 2 - dp(20)) + n * step).coerceIn(0, max(0, dm.heightPixels - h))
    window.setGravity(Gravity.TOP or Gravity.START)
    val a = window.attributes
    a.x = x
    a.y = y
    a.width = w
    a.height = h
    a.dimAmount = 0f
    window.attributes = a
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    setFinishOnTouchOutside(false)
    Wins.register(this, key)
}

private const val MIN_W = 380
private const val MIN_H = 300

/**
 * Minimalkan: launcher dibawa ke depan (aktif dan bisa disentuh), jendela ini tetap hidup
 * di belakangnya dan bisa dimunculkan lagi dari taskbar.
 */
internal fun Activity.floatMinimize() {
    val st = Wins.state(this) ?: return
    st.minimized = true
    try {
        startActivity(
            android.content.Intent(this, MainActivity::class.java).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    } catch (e: Throwable) {
        st.minimized = false
    }
}

internal fun Activity.floatRestore() {
    val st = Wins.state(this) ?: return
    st.minimized = false
    bringTaskToFront()
}

internal fun Activity.floatToggleMax() {
    val st = Wins.state(this) ?: return
    val a = window.attributes
    if (!st.maximized) {
        st.saved = Rect(a.x, a.y, a.x + a.width, a.y + a.height)
        a.x = 0
        a.y = 0
        a.width = ViewGroup.LayoutParams.MATCH_PARENT
        a.height = ViewGroup.LayoutParams.MATCH_PARENT
        st.maximized = true
    } else {
        val r = st.saved
        if (r != null) {
            a.x = r.left
            a.y = r.top
            a.width = r.width()
            a.height = r.height()
        }
        st.maximized = false
    }
    window.attributes = a
}

private fun Activity.saveFloatBounds() {
    val st = Wins.state(this) ?: return
    if (st.maximized) return
    val a = window.attributes
    if (a.width <= 0 || a.height <= 0) return
    getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE).edit()
        .putInt("fw_${st.key}_x", a.x).putInt("fw_${st.key}_y", a.y)
        .putInt("fw_${st.key}_w", a.width).putInt("fw_${st.key}_h", a.height).apply()
}

/** Seret bilah judul untuk memindahkan jendela; ketuk dua kali untuk memaksimalkan. */
internal fun Activity.makeBarDraggable(bar: View) {
    val dm = resources.displayMetrics
    var sx = 0f
    var sy = 0f
    var wx = 0
    var wy = 0
    var lastUp = 0L
    var moved = false
    bar.setOnTouchListener { _, e ->
        val st = Wins.state(this)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.rawX
                sy = e.rawY
                val a = window.attributes
                wx = a.x
                wy = a.y
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (st != null && !st.maximized) {
                    val a = window.attributes
                    if (kotlin.math.abs(e.rawX - sx) > dp(4) || kotlin.math.abs(e.rawY - sy) > dp(4)) moved = true
                    a.x = (wx + (e.rawX - sx)).toInt().coerceIn(-(max(a.width, 0) - dp(120)), dm.widthPixels - dp(80))
                    a.y = (wy + (e.rawY - sy)).toInt().coerceIn(0, dm.heightPixels - dp(48))
                    window.attributes = a
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) {
                    saveFloatBounds()
                } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val now = e.eventTime
                    if (now - lastUp < 320) {
                        floatToggleMax()
                        lastUp = 0L
                    } else {
                        lastUp = now
                    }
                }
                true
            }
            else -> true
        }
    }
}

/** Tepi atau sudut untuk mengubah ukuran. [mask]: 1 = lebar, 2 = tinggi, 3 = keduanya. */
internal fun Activity.makeResizer(v: View, mask: Int) {
    val dm = resources.displayMetrics
    var sx = 0f
    var sy = 0f
    var w0 = 0
    var h0 = 0
    v.setOnTouchListener { _, e ->
        val st = Wins.state(this)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.rawX
                sy = e.rawY
                val a = window.attributes
                w0 = a.width
                h0 = a.height
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (st != null && !st.maximized && w0 > 0 && h0 > 0) {
                    val a = window.attributes
                    if (mask and 1 != 0) a.width = (w0 + (e.rawX - sx)).toInt().coerceIn(min(dp(MIN_W), dm.widthPixels), max(dp(MIN_W), dm.widthPixels - a.x))
                    if (mask and 2 != 0) a.height = (h0 + (e.rawY - sy)).toInt().coerceIn(min(dp(MIN_H), dm.heightPixels), max(dp(MIN_H), dm.heightPixels - a.y))
                    window.attributes = a
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveFloatBounds()
                true
            }
            else -> true
        }
    }
}
