package com.aquaboy.winlauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal sealed class UpdateResult {
    object NoRepo : UpdateResult()
    object Latest : UpdateResult()
    class Available(val version: String, val page: String, val apk: String?, val notes: String) : UpdateResult()
    class Failed(val message: String) : UpdateResult()
}

/**
 * Pemeriksa pembaruan lewat rilis GitHub ("owner/repo"). Hanya membuka tautan unduhan,
 * tidak memasang apa pun sendiri. Pengguna tetap memasang APK secara manual.
 */
internal object Updater {
    private const val CHANNEL = "update_v1"
    private const val EVERY_MS = 6L * 3600_000L

    fun repo(ctx: Context): String {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        val own = (sp.getString("update_repo", "") ?: "").trim().trim('/')
        return if (own.isNotEmpty()) own else BuildConfig.UPDATE_REPO
    }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** True bila a lebih baru dari b (angka dipisah titik, mis. 2.14.10 > 2.14.9). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split(".", "-").map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Memeriksa rilis terbaru di thread terpisah; [done] dipanggil di thread utama. */
    fun check(ctx: Context, done: (UpdateResult) -> Unit) {
        val app = ctx.applicationContext
        val repo = repo(app)
        val main = Handler(Looper.getMainLooper())
        if (!Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) {
            main.post { done(UpdateResult.NoRepo) }
            return
        }
        Thread {
            val result: UpdateResult = try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "WinLauncher")
                when (c.responseCode) {
                    200 -> {
                        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val tag = j.optString("tag_name", "")
                        var apk: String? = null
                        val assets = j.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name").endsWith(".apk", true)) {
                                    apk = a.optString("browser_download_url")
                                    break
                                }
                            }
                        }
                        if (tag.isNotEmpty() && isNewer(tag, currentVersion(app))) {
                            UpdateResult.Available(tag.removePrefix("v"), j.optString("html_url", "https://github.com/$repo/releases"), apk, j.optString("body", ""))
                        } else {
                            UpdateResult.Latest
                        }
                    }
                    404 -> UpdateResult.Failed("Belum ada rilis di $repo (atau repositori bersifat privat).")
                    else -> UpdateResult.Failed("Server menjawab kode ${c.responseCode}.")
                }
            } catch (e: Throwable) {
                UpdateResult.Failed("Tidak bisa terhubung ke internet.")
            }
            main.post { done(result) }
        }.start()
    }

    /** Dipanggil saat launcher dibuka: periksa diam-diam (maksimal tiap 6 jam), beri notifikasi sekali per versi. */
    fun autoCheck(ctx: Context) {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean("update_auto", true)) return
        val now = System.currentTimeMillis()
        if (now - sp.getLong("update_last", 0L) < EVERY_MS) return
        if (repo(ctx).isEmpty()) return
        sp.edit().putLong("update_last", now).apply()
        check(ctx) { r ->
            if (r is UpdateResult.Available && sp.getString("update_notified", "") != r.version) {
                sp.edit().putString("update_notified", r.version).apply()
                notifyAvailable(ctx.applicationContext, r)
            }
        }
    }

    fun openLink(ctx: Context, r: UpdateResult.Available) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apk ?: r.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    private fun notifyAvailable(ctx: Context, r: UpdateResult.Available) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pembaruan Win Launcher", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(
            ctx, 7500,
            Intent(Intent.ACTION_VIEW, Uri.parse(r.apk ?: r.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification26.builder(ctx, CHANNEL) else Notification26.legacy(ctx)
        val text = "Versi ${r.version} tersedia. Ketuk untuk mengunduh."
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pembaruan Win Launcher")
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text + (if (r.notes.isNotBlank()) "\n\n" + r.notes.take(300) else "")))
            .setContentIntent(pi)
            .setAutoCancel(true)
        nm.notify(7501, b.build())
    }
}

private object Notification26 {
    @Suppress("DEPRECATION")
    fun legacy(ctx: Context): android.app.Notification.Builder = android.app.Notification.Builder(ctx)

    fun builder(ctx: Context, channel: String): android.app.Notification.Builder =
        android.app.Notification.Builder(ctx, channel)
}
