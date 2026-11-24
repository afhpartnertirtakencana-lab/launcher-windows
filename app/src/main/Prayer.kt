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
    class Slot(val id: String, val name: String, val hour: Int, val minute: Int, val on: Boolean, val msg: String, val text: String)

    // Doa-doa pendek yang umum dipakai. Teks panjang (Angelus, Koronka) sengaja tidak ditanam di sini:
    // redaksinya berbeda antar buku doa, jadi ditempel sendiri oleh pengguna dari Puji Syukur / buku doa paroki.
    private const val TANDA_SALIB = "Dalam nama Bapa dan Putra dan Roh Kudus. Amin."
    private const val BAPA_KAMI = "Bapa kami yang ada di surga, dimuliakanlah nama-Mu. Datanglah Kerajaan-Mu. " +
        "Jadilah kehendak-Mu di atas bumi seperti di dalam surga. Berilah kami rezeki pada hari ini, " +
        "dan ampunilah kesalahan kami, seperti kami pun mengampuni yang bersalah kepada kami; " +
        "dan janganlah masukkan kami ke dalam pencobaan, tetapi bebaskanlah kami dari yang jahat. Amin."
    private const val SALAM_MARIA = "Salam Maria, penuh rahmat, Tuhan sertamu. Terpujilah engkau di antara wanita, " +
        "dan terpujilah buah tubuhmu, Yesus. Santa Maria, Bunda Allah, doakanlah kami yang berdosa ini, " +
        "sekarang dan waktu kami mati. Amin."
    private const val KEMULIAAN = "Kemuliaan kepada Bapa dan Putra dan Roh Kudus, seperti pada permulaan, sekarang, selalu, " +
        "dan sepanjang segala abad. Amin."
    private const val CATATAN = "\n\n(Redaksi bisa berbeda tipis antar buku doa. Untuk teks lengkap, buka Puji Syukur atau buku doa paroki Anda, " +
        "lalu tempel di Pengaturan > Waktu doa > ketuk doa ini.)"

    private const val ANGELUS = "Doa Malaikat Tuhan (Angelus)\n\nUrutan doa:\n" +
        "1. Maria diberi kabar oleh malaikat Tuhan, bahwa ia akan mengandung dari Roh Kudus\n" +
        "Salam Maria\n\n" +
        "2. Aku ini hamba Tuhan. Terjadilah padaku menurut perkataan-Mu\n" +
        "Salam Maria\n\n" +
        "3. Sabda telah menjadi daging dan tinggal di antara kita\n" +
        "Salam Maria\n\n" +
        "4. Doa penutup\n" +
        "Ya Allah, karena kabar malaikat kami mengetahui bahwa Yesus Kristus Putra-Mu menjadi manusia. " +
        "Curahkanlah rahmat-Mu ke dalam hati kami supaya karena sengsara dan salib-Nya, " +
        "kami dibawa kepada kebangkitan yang mulia. Sebab Dialah Tuhan, pengantara kami.\n\n" +
        "Salam Maria:\n" + SALAM_MARIA

    val slots = listOf(
        Slot("angelus_pagi", "Doa Malaikat Tuhan (pagi)", 6, 0, true,
            "Saatnya Doa Malaikat Tuhan. Mari berhenti sejenak untuk berdoa.", ANGELUS),
        Slot("jam3", "Doa pagi (Jam III)", 9, 0, false,
            "Saatnya doa pagi. Mohon bimbingan Roh Kudus untuk pekerjaan hari ini.",
            "Doa pagi\n\nTanda Salib:\n$TANDA_SALIB\n\nBapa Kami:\n$BAPA_KAMI\n\nKemuliaan:\n$KEMULIAAN"),
        Slot("angelus_siang", "Doa Malaikat Tuhan (siang)", 12, 0, true,
            "Saatnya Doa Malaikat Tuhan. Mari berhenti sejenak untuk berdoa.", ANGELUS),
        Slot("kerahiman", "Jam Kerahiman Ilahi", 15, 0, true,
            "Jam Kerahiman Ilahi. Saatnya berdoa Koronka atau berhenti sejenak untuk berdoa.",
            "Jam Kerahiman Ilahi (pukul 15.00)\n\n" +
                "Ya Yesus, Engkau telah wafat, namun sumber kehidupan telah memancar bagi jiwa-jiwa " +
                "dan terbukalah lautan kerahiman bagi segenap dunia.\n" +
                "O sumber kehidupan, kerahiman Ilahi yang tak terselami, naungilah segenap dunia " +
                "dan curahkanlah diri-Mu pada kami.\n\n" +
                "Doa utama kepada Kerahiman Ilahi:\n" +
                "Darah dan air yang telah memancar dari Hati Yesus sebagai sumber kerahiman bagi kami, " +
                "Engkaulah Andalanku. (3x)\n\n" +
                "Yesus Raja Kerahiman Ilahi, Engkaulah Andalanku. Amin."),
        Slot("angelus_sore", "Doa Malaikat Tuhan (sore)", 18, 0, true,
            "Saatnya Doa Malaikat Tuhan. Mari berhenti sejenak untuk berdoa.", ANGELUS),
        Slot("malam", "Doa malam", 20, 0, true,
            "Saatnya doa malam. Tutup hari ini dengan doa syukur.",
            "Doa malam\n\nTanda Salib:\n$TANDA_SALIB\n\nBapa Kami:\n$BAPA_KAMI\n\nSalam Maria:\n$SALAM_MARIA\n\nKemuliaan:\n$KEMULIAAN")
    )

    /** Teks doa: yang diubah pengguna, atau bawaan bila kosong. */
    fun textOf(sp: SharedPreferences, s: Slot): String =
        (sp.getString("prayer_${s.id}_text", "") ?: "").ifBlank { s.text }

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

    /** Lama (menit) sebuah waktu doa dianggap "sedang berlangsung" setelah jamnya tiba. */
    const val WINDOW_MIN = 30

    /** Waktu doa yang sedang berlangsung (jamnya sudah tiba dan belum lewat [WINDOW_MIN] menit), atau null. */
    fun current(sp: SharedPreferences, now: Long): Slot? {
        var best: Slot? = null
        var bestAt = 0L
        for (s in slots) {
            if (!isOn(sp, s)) continue
            val c = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, hourOf(sp, s))
                set(java.util.Calendar.MINUTE, minuteOf(sp, s))
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val at = c.timeInMillis
            if (now >= at && now < at + WINDOW_MIN * 60000L && at >= bestAt) {
                best = s
                bestAt = at
            }
        }
        return best
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
            ctx, 7200 + slots.indexOf(s).coerceAtLeast(0),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("prayer_id", s.id),
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
            .setStyle(android.app.Notification.BigTextStyle().bigText(if (test) text else text + "\n\n" + textOf(sp, s)))
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


/** Menampilkan teks doa. [onSettings] dipanggil bila pengguna memilih "Atur jam". */
internal fun android.app.Activity.showPrayerDialog(s: Prayer.Slot, onSettings: (() -> Unit)? = null) {
    val sp = getSharedPreferences(Th.PREFS, android.content.Context.MODE_PRIVATE)
    val text = Prayer.textOf(sp, s)
    val body = android.widget.TextView(this).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Th.p.text)
        setLineSpacing(0f, 1.15f)
    }
    val sv = android.widget.ScrollView(this)
    sv.addView(body)
    val dm = resources.displayMetrics
    val est = dp(24 + (text.length / 34 + text.count { it == '\n' } + 2) * 22)
    val h = minOf(est, (dm.heightPixels * 0.55f).toInt())
    val box = ll(true)
    box.addView(
        android.widget.TextView(this).apply {
            this.text = Prayer.timeText(sp, s) + "  \u00B7  " + s.msg
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Th.accent)
            setPadding(0, 0, 0, dp(8))
        }
    )
    val lit = Liturgy.today()
    box.addView(
        android.widget.TextView(this).apply {
            this.text = lit.title + "  \u00B7  " + lit.season
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Th.p.dim)
            setPadding(0, 0, 0, dp(8))
        }
    )
    readingApp()?.let { (label, intent) ->
        box.addView(linkText("Buka $label untuk teks lengkap  \u203A") {
            try { startActivity(intent) } catch (e: Exception) { android.widget.Toast.makeText(this, "Tidak bisa membuka $label", android.widget.Toast.LENGTH_SHORT).show() }
        })
    }
    box.addView(sv, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, h))
    showDialog("\u271D " + s.name, box, if (onSettings != null) "Atur jam" else "Tutup", if (onSettings != null) "Tutup" else null) {
        onSettings?.invoke()
    }
}


/** Aplikasi bacaan/doa pilihan pengguna (mis. eKatolik): pasangan nama dan intent peluncurnya, atau null. */
internal fun android.app.Activity.readingApp(): Pair<String, android.content.Intent>? {
    val sp = getSharedPreferences(Th.PREFS, android.content.Context.MODE_PRIVATE)
    val cn = android.content.ComponentName.unflattenFromString(sp.getString("reading_app", "") ?: "") ?: return null
    return try {
        val label = packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString()
        label to android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setComponent(cn)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (e: Throwable) {
        null
    }
}

/** Tautan teks kecil berwarna aksen. */
internal fun android.app.Activity.linkText(text: String, onClick: () -> Unit): android.widget.TextView =
    android.widget.TextView(this).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Th.accent)
        setPadding(0, dp(8), 0, dp(4))
        setOnClickListener { onClick() }
    }
