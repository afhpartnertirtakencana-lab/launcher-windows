package com.aquaboy.winlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import java.io.File
import kotlin.math.max

/**
 * Pembersih ringan. Android tidak mengizinkan aplikasi biasa menghapus cache aplikasi lain,
 * jadi yang dilakukan: menghentikan proses latar belakang aplikasi lain (RAM) dan
 * menghapus cache milik launcher sendiri.
 */
internal object Cleaner {
    class Result(val ramFreed: Long, val cacheFreed: Long, val apps: Int)

    private fun avail(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem
    }

    /** Jalankan di thread latar belakang. */
    fun clean(ctx: Context): Result {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = avail(ctx)
        var n = 0
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pkgs = ctx.packageManager.queryIntentActivities(i, 0)
                .map { it.activityInfo.packageName }.toSet() - ctx.packageName
            for (p in pkgs) {
                try {
                    am.killBackgroundProcesses(p)
                    n++
                } catch (e: Throwable) {
                    // lewati aplikasi yang menolak
                }
            }
        } catch (e: Throwable) {
            // daftar aplikasi tidak tersedia
        }
        val cache = clearCache(ctx)
        try {
            Thread.sleep(900)
        } catch (e: InterruptedException) {
            // lanjut
        }
        return Result(max(0L, avail(ctx) - before), cache, n)
    }

    private fun clearCache(ctx: Context): Long {
        var freed = 0L
        for (d in listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)) {
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                freed += sizeOf(k)
                try {
                    k.deleteRecursively()
                } catch (e: Throwable) {
                    // abaikan berkas yang sedang dipakai
                }
            }
        }
        return freed
    }

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L) else f.length()
}
