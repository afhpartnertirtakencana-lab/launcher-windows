package com.aquaboy.winlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File

/** Nama dan foto akun lokal (bisa diubah di Pengaturan Win → Akun). */
object Profile {
    private const val KEY_NAME = "profile_name"
    private const val PHOTO = "avatar.png"

    private fun deviceName(ctx: Context): String {
        val n = try {
            Settings.Global.getString(ctx.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return if (n.isNullOrBlank()) Build.MODEL else n
    }

    fun name(ctx: Context): String {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        val n = sp.getString(KEY_NAME, null)
        return if (n.isNullOrBlank()) deviceName(ctx) else n
    }

    fun setName(ctx: Context, name: String) {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_NAME, name.trim()).apply()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, PHOTO)

    fun hasPhoto(ctx: Context): Boolean = file(ctx).exists()

    fun clearPhoto(ctx: Context) {
        file(ctx).delete()
    }

    /** Simpan foto pilihan pengguna: dipotong persegi dan diperkecil ke 320 px. */
    fun savePhoto(ctx: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 640 && bounds.outHeight / (sample * 2) >= 640) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val out = Bitmap.createScaledBitmap(square, 320, 320, true)
            file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Avatar bulat: foto jika ada, kalau tidak huruf pertama nama di atas warna aksen. */
    fun avatar(ctx: Context, sizeDp: Int, textSp: Float): View {
        val density = ctx.resources.displayMetrics.density
        val px = (sizeDp * density).toInt()
        val bmp = if (hasPhoto(ctx)) {
            try {
                BitmapFactory.decodeFile(file(ctx).absolutePath)
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }
        if (bmp != null) {
            return ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(RoundedBitmapDrawableFactory.create(ctx.resources, bmp).apply {
                    isCircular = true
                    setAntiAlias(true)
                })
                minimumWidth = px
                minimumHeight = px
            }
        }
        return ctx.tv(name(ctx).take(1).uppercase(), textSp, Th.onAccent, true).apply {
            gravity = Gravity.CENTER
            background = ctx.rounded(Th.accent, px / 2f)
        }
    }

    /** Isi ulang wadah dengan avatar terbaru. */
    fun fill(holder: FrameLayout, sizeDp: Int, textSp: Float) {
        holder.removeAllViews()
        holder.addView(avatar(holder.context, sizeDp, textSp), FrameLayout.LayoutParams(-1, -1))
    }
}
