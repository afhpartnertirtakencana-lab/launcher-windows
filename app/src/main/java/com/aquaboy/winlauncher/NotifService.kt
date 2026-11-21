package com.aquaboy.winlauncher

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Jumlah notifikasi aktif per aplikasi, untuk badge di ikon. */
object Badges {
    @Volatile var counts: Map<String, Int> = emptyMap()
    @Volatile var connected = false
    @Volatile var onChange: (() -> Unit)? = null

    fun count(pkg: String): Int = counts[pkg] ?: 0
}

/**
 * Membaca notifikasi yang sedang tampil (hanya jumlahnya, isinya tidak dibaca atau disimpan).
 * Aktif setelah pengguna memberi "Akses notifikasi" di Pengaturan Android.
 */
class NotifService : NotificationListenerService() {

    override fun onListenerConnected() {
        Badges.connected = true
        recount()
    }

    override fun onListenerDisconnected() {
        Badges.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        recount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        recount()
    }

    private fun recount() {
        val list = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return
        val m = HashMap<String, Int>()
        for (n in list) {
            if (n.isOngoing) continue
            if (n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            m[n.packageName] = (m[n.packageName] ?: 0) + 1
        }
        if (m != Badges.counts) {
            Badges.counts = m
            Handler(Looper.getMainLooper()).post { Badges.onChange?.invoke() }
        }
    }
}
