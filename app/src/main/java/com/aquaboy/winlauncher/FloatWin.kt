package com.aquaboy.winlauncher

import android.app.ActivityOptions
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings

/**
 * Jendela mengambang (freeform). Launcher hanya menentukan posisi dan ukuran awal jendela;
 * memindahkan dan mengubah ukuran dilakukan oleh sistem Android, sehingga perangkat harus
 * mengaktifkan mode freeform (Opsi pengembang) agar bisa bekerja.
 */
object FloatWin {
    private var cascade = 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("float_windows", false)

    /** Apakah perangkat ini sudah mengaktifkan jendela freeform. */
    fun supported(ctx: Context): Boolean {
        if (ctx.packageManager.hasSystemFeature("android.software.freeform_window_management")) return true
        return try {
            Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1
        } catch (e: Throwable) {
            false
        }
    }

    /** Opsi peluncuran dengan batas jendela bertingkat, atau null bila mode ini dimatikan. */
    fun options(ctx: Context): Bundle? {
        if (!enabled(ctx)) return null
        return try {
            val dm = ctx.resources.displayMetrics
            val frac = when (prefs(ctx).getInt("float_size", 1)) {
                0 -> 0.5f
                2 -> 0.82f
                else -> 0.66f
            }
            val w = (dm.widthPixels * frac).toInt()
            val h = (dm.heightPixels * frac).toInt()
            val step = (36 * dm.density).toInt()
            val n = cascade++ % 8
            val l = ((dm.widthPixels - w) / 2 + (n - 3) * step).coerceIn(0, dm.widthPixels - w)
            val t = ((dm.heightPixels - h) / 2 + (n - 3) * step).coerceIn(0, dm.heightPixels - h)
            ActivityOptions.makeBasic().setLaunchBounds(Rect(l, t, l + w, t + h)).toBundle()
        } catch (e: Throwable) {
            null
        }
    }
}
