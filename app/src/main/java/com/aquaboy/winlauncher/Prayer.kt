package com.aquaboy.winlauncher

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import java.util.Calendar

/**
 * Waktu doa Katolik dan pengingatnya (notifikasi + nada).
 * Jam bawaan: Doa Malaikat Tuhan (Angelus) 06.00, 12.00, 18.00; Jam Kerahiman Ilahi 15.00;
 * serta jam Ibadat Harian tradisional (Jam III 09.00 dan doa malam sekitar 20.00).
 * Semua jam bisa diubah atau dimatikan di Pengaturan > Waktu doa.
 */
internal object Prayer {
    class Slot(val id: String, val name: String, val hour: Int, val minute: Int, val on: Boolean, val msg: String)

    val slots = listOf(
        Slot("angelus_pagi", "Doa Malaikat Tuhan (pagi)", 6, 0, true, "Saatnya Doa Malaikat Tuhan. Mari berhenti sejenak untuk berdoa."),
        Slot("jam3", "Doa pagi (Jam III)", 9, 0, false, "Saatnya doa pagi. Mohon bimbingan Roh Kudus untuk pekerjaan hari ini."),
        Slot("angelus_siang", "Doa Malaikat Tuhan (siang)", 12, 0, true, "Saatnya Doa Malaikat Tuhan. Mari berhenti sejenak untuk berdoa."),
        Slot("kerahiman", "Jam Kerahiman Ilahi", 15, 0, true, "Jam Kerahiman Ilahi. Saatnya berdoa Koronka atau berhenti sejenak untuk berdoa."),
        Slot("angelus_sore", "Doa Malaikat Tuhan (sore)", 18, 0, true, "Saatnya Doa Malaikat Tuhan. Mari berhenti sejenak untuk berdoa."),
        Slot("malam", "Doa malam", 20, 0, true, "Saatnya doa malam. Tutup hari ini dengan doa syukur.")
    )

    const val ACTION = "com.aquaboy.winlauncher.PRAYER"

    fun masterOn(sp: SharedPreferences) = sp.getBoolean("prayer_on", false)
    fun isOn(sp: SharedPreferences, s: Slot) = sp.getBoolean("prayer_${s.id}_on", s.on)
    fun hourOf(sp: SharedPreferences, s: Slot) = sp.getInt("prayer_${s.id}_h", s.hour)
    fun minuteOf(sp: SharedPreferences, s: Slot) = sp.getInt("prayer_${s.id}_m", s.minute)
    fun timeText(sp: SharedPreferences, s: Slot) = "%02d.%02d".format(hourOf(sp, s), minuteOf(sp, s))

    /** Waktu (ms) kejadian berikutnya setelah [now]. */
    fun nextTime(sp: SharedPreferences, s: Slot, now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, hourOf(sp, s))
        c.set(Calendar.MINUTE, minuteOf(sp, s))
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** Daftar waktu doa aktif, diurutkan dari yang paling dekat. */
    fun upcoming(sp: SharedPreferences, now: Long): List<Pair<Slot, Long>> =
        slots.filter { isOn(sp, it) }.map { it to nextTime(sp, it, now) }.sortedBy { it.second }

    private fun pending(ctx: Context, index: Int, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 7100 + index,
            Intent(ctx, PrayerReceiver::class.java).setAction(ACTION).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** Pasang (atau batalkan) semua alarm sesuai pengaturan. Aman dipanggil berulang. */
    fun scheduleAll(ctx: Context) {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val master = masterOn(sp)
        val now = System.currentTimeMillis()
        for ((i, s) in slots.withIndex()) {
            val pi = pending(ctx, i, s.id)
            if (!master || !isOn(sp, s)) {
                am.cancel(pi)
                continue
            }
            val at = nextTime(sp, s, now)
            try {
                if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: Throwable) {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    private const val CH_BELL = "doa_lonceng_v1"
    private const val CH_SYS = "doa_sistem_v1"
    private const val CH_QUIET = "doa_senyap_v1"

    private fun channelFor(tone: Int) = when (tone) {
        1 -> CH_SYS
        2 -> CH_QUIET
        else -> CH_BELL
    }

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val bell = NotificationChannel(CH_BELL, "Waktu doa (lonceng)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pengingat waktu doa dengan bunyi lonceng"
            setSound(Uri.parse("android.resource://${ctx.packageName}/raw/lonceng_doa"), attrs)
            enableVibration(true)
        }
        val sys = NotificationChannel(CH_SYS, "Waktu doa (nada sistem)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pengingat waktu doa dengan nada notifikasi bawaan"
            enableVibration(true)
        }
        val quiet = NotificationChannel(CH_QUIET, "Waktu doa (senyap)", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Pengingat waktu doa tanpa suara"
            setSound(null, null)
        }
        nm.createNotificationChannel(bell)
        nm.createNotificationChannel(sys)
        nm.createNotificationChannel(quiet)
    }

    fun notify(ctx: Context, s: Slot, test: Boolean = false) {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        val tone = sp.getInt("prayer_tone", 0)
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 7200,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = (if (test) "Uji: " else "") + s.name
        val text = if (test) "Begini bunyi pengingat waktu doa Anda." else s.msg + "  (" + timeText(sp, s) + ")"
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, channelFor(tone))
        else android.app.Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_prayer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            when (tone) {
                0 -> b.setSound(Uri.parse("android.resource://${ctx.packageName}/raw/lonceng_doa"))
                1 -> b.setDefaults(android.app.Notification.DEFAULT_SOUND or android.app.Notification.DEFAULT_VIBRATE)
            }
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(7300 + slots.indexOf(s).coerceAtLeast(0), b.build())
        } catch (e: Throwable) {
            // izin notifikasi belum diberikan
        }
    }
}

/** Menerima alarm waktu doa: tampilkan notifikasi lalu pasang alarm berikutnya. */
class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Prayer.ACTION) return
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        val s = Prayer.slots.firstOrNull { it.id == i.getStringExtra("id") }
        if (s != null && Prayer.masterOn(sp) && Prayer.isOn(sp, s)) Prayer.notify(ctx, s)
        Prayer.scheduleAll(ctx)
    }
}

/** Setelah tablet/HP menyala ulang, aplikasi diperbarui, atau jam/zona waktu berubah: pasang ulang alarm. */
class PrayerBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Prayer.scheduleAll(ctx)
    }
}
