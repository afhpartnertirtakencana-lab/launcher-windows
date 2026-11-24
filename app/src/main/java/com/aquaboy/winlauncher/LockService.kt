package com.aquaboy.winlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Hanya dipakai untuk satu hal: mengunci/mematikan layar (ketuk dua kali di desktop).
 * Tidak membaca isi layar dan tidak menerima peristiwa apa pun.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: LockService? = null

        /** True bila perintah kunci layar berhasil dikirim. */
        fun lock(): Boolean {
            val s = instance ?: return false
            return Build.VERSION.SDK_INT >= 28 && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
