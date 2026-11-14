package com.aquaboy.winlauncher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Simpan dan pulihkan seluruh tampilan launcher: tema, aksen, taskbar, Start, ikon desktop,
 * widget (termasuk isi catatan), nama dan foto akun, serta pengaturan File Explorer.
 */
object Backup {
    private const val MAGIC = "WinLauncherBackup"
    private const val AVATAR = "avatar.png"

    fun export(ctx: Context, out: OutputStream) {
        val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for ((k, v) in sp.all) {
            if (k == "rev" || v == null) continue
            val o = JSONObject().put("k", k)
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            arr.put(o)
        }
        val root = JSONObject().put("app", MAGIC).put("version", 1).put("prefs", arr)
        val f = File(ctx.filesDir, AVATAR)
        if (f.exists()) root.put("avatar", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Mengembalikan jumlah pengaturan yang dipulihkan, atau -1 bila berkas bukan cadangan yang valid. */
    fun import(ctx: Context, input: InputStream): Int {
        return try {
            val root = JSONObject(input.readBytes().toString(Charsets.UTF_8))
            if (root.optString("app") != MAGIC) return -1
            val arr = root.getJSONArray("prefs")
            val sp = ctx.getSharedPreferences(Th.PREFS, Context.MODE_PRIVATE)
            val rev = sp.getInt("rev", 0) + 1
            val e = sp.edit().clear()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val k = o.getString("k")
                when (o.getString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    "set" -> {
                        val a = o.getJSONArray("v")
                        e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                    else -> continue
                }
                n++
            }
            e.putInt("rev", rev)
            e.commit()
            val f = File(ctx.filesDir, AVATAR)
            if (root.has("avatar")) f.writeBytes(Base64.decode(root.getString("avatar"), Base64.DEFAULT)) else f.delete()
            n
        } catch (e: Throwable) {
            -1
        }
    }
}
