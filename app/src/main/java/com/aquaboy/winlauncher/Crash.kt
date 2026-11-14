package com.aquaboy.winlauncher

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Application: memasang pencatat crash supaya penyebab aplikasi berhenti bisa dibaca di launcher. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}

object Crash {
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = "Waktu: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "\nPerangkat: " + Build.MANUFACTURER + " " + Build.MODEL +
                    " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")" +
                    "\nThread: " + t.name + "\n\n"
                File(app.filesDir, FILE).writeText(head + sw.toString())
            } catch (x: Throwable) {
                // pencatat tidak boleh membuat crash baru
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Ambil laporan crash terakhir (sekali pakai), atau null jika tidak ada. */
    fun take(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        val s = try {
            f.readText()
        } catch (e: Exception) {
            null
        }
        f.delete()
        return s
    }
}
