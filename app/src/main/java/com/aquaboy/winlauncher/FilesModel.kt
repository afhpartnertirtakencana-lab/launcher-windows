package com.aquaboy.winlauncher

import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal typealias FmK = GlyphView.Kind

/** Satu baris pada daftar berkas. [sub] berisi lokasi (hasil cari) atau lokasi asli (Keranjang Sampah). */
internal class FmItem(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mod: Long,
    val sub: String? = null
)

internal class FmVolume(val label: String, val root: File)

/** Catatan Keranjang Sampah: berkas di [binPath] berasal dari [orig]. */
internal class FmTrash(val binPath: String, val orig: String, val time: Long)

internal class FmProgress {
    @Volatile
    var cancelled = false

    @Volatile
    var text = ""
}

internal object FmUtil {
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")
    private val AUD = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr")
    private val DOCS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods",
        "csv", "md", "json", "xml", "html", "htm", "log"
    )
    private val ARC = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2")

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
    }

    fun baseName(name: String): String {
        val e = ext(name)
        return if (e.isEmpty()) name else name.substring(0, name.length - e.length - 1)
    }

    /** 0 lain-lain, 1 gambar, 2 video, 3 audio, 4 dokumen, 5 arsip, 6 apk */
    fun category(name: String): Int {
        val e = ext(name)
        return when {
            e.isEmpty() -> 0
            e in IMG -> 1
            e in VID -> 2
            e in AUD -> 3
            e in DOCS -> 4
            e in ARC -> 5
            e == "apk" -> 6
            else -> 0
        }
    }

    fun kindFor(isDir: Boolean, name: String): GlyphView.Kind {
        if (isDir) return GlyphView.Kind.FOLDER
        return when (category(name)) {
            1 -> GlyphView.Kind.IMAGE
            2 -> GlyphView.Kind.VIDEO
            3 -> GlyphView.Kind.MUSIC
            4 -> GlyphView.Kind.DOC
            5 -> GlyphView.Kind.ARCHIVE
            6 -> GlyphView.Kind.APK
            else -> GlyphView.Kind.FILE
        }
    }

    fun typeLabel(isDir: Boolean, name: String): String {
        if (isDir) return "Folder berkas"
        val e = ext(name).uppercase()
        if (e.isEmpty()) return "Berkas"
        return when (category(name)) {
            1 -> "Gambar $e"
            2 -> "Video $e"
            3 -> "Audio $e"
            4 -> "Dokumen $e"
            5 -> "Arsip $e"
            6 -> "Aplikasi APK"
            else -> "Berkas $e"
        }
    }

    fun typeKey(item: FmItem): String = if (item.isDir) "" else ext(item.name)

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f.name)) ?: "*/*"

    /** Nama bebas bentrok: "nama - Salin", "nama - Salin (2)", ... */
    fun unique(dir: File, name: String, isDir: Boolean): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val e = if (isDir) "" else ext(name)
        val b = if (isDir) name else baseName(name)
        val suffix = if (e.isEmpty()) "" else ".$e"
        var n = 1
        while (true) {
            val cand = if (n == 1) "$b - Salin$suffix" else "$b - Salin ($n)$suffix"
            val f = File(dir, cand)
            if (!f.exists()) return f
            n++
        }
    }

    /** [ukuran, jumlah berkas, jumlah folder] (isi saja, tanpa folder induk). */
    fun sizeOf(f: File, depth: Int = 0): LongArray {
        if (!f.isDirectory) return longArrayOf(f.length(), 1L, 0L)
        if (depth > 40) return longArrayOf(0L, 0L, 0L)
        val kids = f.listFiles() ?: return longArrayOf(0L, 0L, 0L)
        var size = 0L
        var files = 0L
        var dirs = 0L
        for (k in kids) {
            val r = sizeOf(k, depth + 1)
            size += r[0]
            files += r[1]
            dirs += r[2]
            if (k.isDirectory) dirs += 1L
        }
        return longArrayOf(size, files, dirs)
    }

    fun deleteTree(f: File): Boolean {
        if (f.isDirectory) {
            val kids = f.listFiles()
            if (kids != null) for (k in kids) deleteTree(k)
        }
        return f.delete()
    }

    fun copyTree(src: File, dst: File, pr: FmProgress, depth: Int = 0) {
        if (pr.cancelled || depth > 40) return
        if (src.isDirectory) {
            dst.mkdirs()
            val kids = src.listFiles() ?: return
            for (k in kids) copyTree(k, File(dst, k.name), pr, depth + 1)
        } else {
            pr.text = src.name
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (pr.cancelled) break
                        output.write(buf, 0, n)
                    }
                }
            }
            if (pr.cancelled) {
                dst.delete()
            } else {
                dst.setLastModified(src.lastModified())
            }
        }
    }

    fun addZip(z: ZipOutputStream, f: File, rootLen: Int, pr: FmProgress) {
        if (pr.cancelled) return
        val name = f.absolutePath.substring(rootLen).replace(File.separatorChar, '/')
        if (f.isDirectory) {
            z.putNextEntry(ZipEntry("$name/"))
            z.closeEntry()
            val kids = f.listFiles() ?: return
            for (k in kids) addZip(z, k, rootLen, pr)
        } else {
            pr.text = f.name
            z.putNextEntry(ZipEntry(name))
            FileInputStream(f).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (pr.cancelled) break
                    z.write(buf, 0, n)
                }
            }
            z.closeEntry()
        }
    }
}
