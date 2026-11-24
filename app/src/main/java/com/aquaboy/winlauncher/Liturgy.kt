package com.aquaboy.winlauncher

import java.util.Calendar

/**
 * Kalender liturgi Gereja Katolik Roma (tahun C/A/B tidak dihitung), dihitung sendiri tanpa internet.
 * Perkiraan umum untuk Indonesia: Penampakan Tuhan dan Hari Raya Tubuh dan Darah Kristus dirayakan hari Minggu,
 * Kenaikan Tuhan hari Kamis. Peringatan santo/santa hanya yang utama. Untuk kepastian, ikuti kalender liturgi KWI/paroki.
 */
internal object Liturgy {
    const val WHITE = 0xFFEDEDED.toInt()
    const val RED = 0xFFD93A3A.toInt()
    const val GREEN = 0xFF2E9D57.toInt()
    const val VIOLET = 0xFF7B4FC4.toInt()
    const val ROSE = 0xFFE88AB4.toInt()

    class Day(val title: String, val season: String, val rank: String, val color: Int) {
        val colorName: String
            get() = when (color) {
                WHITE -> "Putih"
                RED -> "Merah"
                GREEN -> "Hijau"
                VIOLET -> "Ungu"
                else -> "Merah muda"
            }
    }

    private val DAYS = arrayOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")

    // Hari sejak 1 Januari 1970 (kalender Gregorian).
    private fun days(y: Int, m: Int, d: Int): Int {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun dow(e: Int): Int = ((e % 7) + 11) % 7 // 0 = Minggu

    private fun easter(y: Int): Int {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return days(y, month, day)
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(10, 9, 5, 4, 1)
        val s = arrayOf("X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        // untuk 30-an: XXX + ...
        while (x >= 10) { sb.append("X"); x -= 10 }
        for (i in 1 until v.size) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    fun today(): Day {
        val c = Calendar.getInstance()
        return forDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun forDate(y: Int, m: Int, d: Int): Day {
        val e = days(y, m, d)
        val dw = dow(e)
        val sunday = e - dw
        val dn = DAYS[dw]
        val easter = easter(y)
        val ashW = easter - 46
        val pent = easter + 49
        val dec25 = days(y, 12, 25)
        val adventI = dec25 - (if (dow(dec25) == 0) 7 else dow(dec25)) - 21
        val christKing = adventI - 7
        val j2 = days(y, 1, 2)
        val epiph = j2 + ((7 - dow(j2)) % 7)
        val baptism = if (epiph >= days(y, 1, 7)) epiph + 1 else epiph + 7

        val season = when {
            e in adventI until dec25 -> "Masa Adven"
            e >= dec25 || e <= baptism -> "Masa Natal"
            e < ashW -> "Masa Biasa"
            e < easter - 7 -> "Masa Prapaskah"
            e < easter - 3 -> "Pekan Suci"
            e < easter -> "Trihari Paskah"
            e <= pent -> "Masa Paskah"
            e < adventI -> "Masa Biasa"
            else -> "Masa Biasa"
        }

        // ---- hari biasa menurut masa ----
        var res: Day = when (season) {
            "Masa Adven" -> {
                val w = 1 + (sunday - adventI) / 7
                if (dw == 0) Day("Hari Minggu Adven ${roman(w)}" + (if (w == 3) " (Gaudete)" else ""), season, "", if (w == 3) ROSE else VIOLET)
                else Day("$dn Pekan Adven ${roman(w)}", season, "", VIOLET)
            }
            "Masa Natal" -> Day(if (dw == 0) "Hari Minggu dalam Masa Natal" else "$dn dalam Masa Natal", season, "", WHITE)
            "Masa Prapaskah" -> {
                if (e < ashW + 4) Day("$dn sesudah Rabu Abu", season, "", VIOLET)
                else {
                    val n = 1 + (sunday - (ashW + 4)) / 7
                    if (dw == 0) Day("Hari Minggu Prapaskah ${roman(n)}" + (if (n == 4) " (Laetare)" else ""), season, "", if (n == 4) ROSE else VIOLET)
                    else Day("$dn Pekan Prapaskah ${roman(n)}", season, "", VIOLET)
                }
            }
            "Pekan Suci" -> Day("$dn dalam Pekan Suci", season, "", VIOLET)
            "Trihari Paskah" -> Day("$dn dalam Trihari Paskah", season, "", VIOLET)
            "Masa Paskah" -> {
                val k = e - easter
                if (k in 1..6) Day("$dn dalam Oktaf Paskah", season, "", WHITE)
                else {
                    val n = 1 + (sunday - easter) / 7
                    if (dw == 0) Day("Hari Minggu Paskah ${roman(n)}", season, "", WHITE)
                    else Day("$dn Pekan Paskah ${roman(n)}", season, "", WHITE)
                }
            }
            else -> {
                val w = if (e <= ashW) 1 + (sunday - (baptism - dow(baptism))) / 7 else 34 - (christKing - sunday) / 7
                if (dw == 0) Day("Hari Minggu Biasa ${roman(w)}", season, "", GREEN)
                else Day("$dn Pekan Biasa ${roman(w)}", season, "", GREEN)
            }
        }

        // ---- hari raya/pesta yang bergerak ----
        val k = e - easter
        val moving: Day? = when {
            k == -46 -> Day("Rabu Abu", season, "", VIOLET)
            k == -7 -> Day("Hari Minggu Palma", season, "", RED)
            k == -3 -> Day("Kamis Putih", season, "", WHITE)
            k == -2 -> Day("Jumat Agung", season, "", RED)
            k == -1 -> Day("Sabtu Suci", season, "", VIOLET)
            k == 0 -> Day("Hari Raya Paskah", season, "Hari Raya", WHITE)
            k == 7 -> Day("Minggu Paskah II (Minggu Kerahiman Ilahi)", season, "", WHITE)
            k == 39 -> Day("Hari Raya Kenaikan Tuhan", season, "Hari Raya", WHITE)
            k == 49 -> Day("Hari Raya Pentakosta", season, "Hari Raya", RED)
            k == 50 -> Day("Santa Perawan Maria, Bunda Gereja", "Masa Biasa", "Peringatan", WHITE)
            k == 56 -> Day("Hari Raya Tritunggal Mahakudus", "Masa Biasa", "Hari Raya", WHITE)
            k == 63 -> Day("Hari Raya Tubuh dan Darah Kristus", "Masa Biasa", "Hari Raya", WHITE)
            k == 68 -> Day("Hari Raya Hati Yesus Yang Mahakudus", "Masa Biasa", "Hari Raya", WHITE)
            k == 69 -> Day("Hati Santa Perawan Maria Yang Tak Bernoda", "Masa Biasa", "Peringatan", WHITE)
            e == christKing -> Day("Hari Raya Kristus Raja Semesta Alam", "Masa Biasa", "Hari Raya", WHITE)
            e == epiph -> Day("Hari Raya Penampakan Tuhan (Epifani)", season, "Hari Raya", WHITE)
            e == baptism -> Day("Pesta Pembaptisan Tuhan", season, "Pesta", WHITE)
            else -> null
        }
        // Keluarga Kudus: Minggu antara 26-31 Desember, atau 30 Desember bila tidak ada Minggu.
        val holyFamily = if (m == 12 && d in 26..31 && dw == 0) true
        else (m == 12 && d == 30 && (26..31).none { dow(days(y, 12, it)) == 0 })
        if (holyFamily) res = Day("Pesta Keluarga Kudus", season, "Pesta", WHITE)
        if (moving != null) return moving

        // ---- hari raya/pesta tetap ----
        val seasonalSunday = dw == 0 && (season == "Masa Adven" || season == "Masa Prapaskah" || season == "Masa Paskah")
        val holy = season == "Pekan Suci" || season == "Trihari Paskah" || (k in 1..6)
        var fixed: Day? = when (m * 100 + d) {
            101 -> Day("Hari Raya Santa Perawan Maria, Bunda Allah", season, "Hari Raya", WHITE)
            202 -> Day("Pesta Yesus Dipersembahkan di Kenisah", season, "Pesta", WHITE)
            222 -> Day("Pesta Takhta Santo Petrus", season, "Pesta", WHITE)
            319 -> Day("Hari Raya Santo Yosef, Suami SP Maria", season, "Hari Raya", WHITE)
            325 -> Day("Hari Raya Kabar Sukacita", season, "Hari Raya", WHITE)
            425 -> Day("Pesta Santo Markus", season, "Pesta", RED)
            501 -> Day("Santo Yosef Pekerja", season, "Peringatan", WHITE)
            531 -> Day("Pesta Kunjungan SP Maria", season, "Pesta", WHITE)
            624 -> Day("Hari Raya Kelahiran Santo Yohanes Pembaptis", season, "Hari Raya", WHITE)
            629 -> Day("Hari Raya Santo Petrus dan Paulus", season, "Hari Raya", RED)
            725 -> Day("Pesta Santo Yakobus", season, "Pesta", RED)
            806 -> Day("Pesta Yesus Menampakkan Kemuliaan-Nya", season, "Pesta", WHITE)
            815 -> Day("Hari Raya Santa Perawan Maria Diangkat ke Surga", season, "Hari Raya", WHITE)
            908 -> Day("Pesta Kelahiran Santa Perawan Maria", season, "Pesta", WHITE)
            914 -> Day("Pesta Salib Suci", season, "Pesta", RED)
            929 -> Day("Pesta Para Malaikat Agung", season, "Pesta", WHITE)
            1007 -> Day("Santa Perawan Maria Ratu Rosario", season, "Peringatan", WHITE)
            1101 -> Day("Hari Raya Semua Orang Kudus", season, "Hari Raya", WHITE)
            1102 -> Day("Peringatan Arwah Semua Orang Beriman", season, "Peringatan", VIOLET)
            1109 -> Day("Pesta Pentahbisan Gereja Lateran", season, "Pesta", WHITE)
            1208 -> if (dw == 0) null else Day("Hari Raya Santa Perawan Maria Dikandung Tanpa Noda", season, "Hari Raya", WHITE)
            1209 -> if (dow(e - 1) == 0) Day("Hari Raya Santa Perawan Maria Dikandung Tanpa Noda", season, "Hari Raya", WHITE) else null
            1225 -> Day("Hari Raya Natal", season, "Hari Raya", WHITE)
            1226 -> Day("Pesta Santo Stefanus", season, "Pesta", RED)
            1227 -> Day("Pesta Santo Yohanes", season, "Pesta", WHITE)
            1228 -> Day("Pesta Anak-anak Suci Martir", season, "Pesta", RED)
            else -> null
        }
        if (fixed == null && season == "Masa Prapaskah" && dow(e - 1) == 0) {
            if (m == 3 && d == 20) fixed = Day("Hari Raya Santo Yosef, Suami SP Maria", season, "Hari Raya", WHITE)
            if (m == 3 && d == 26) fixed = Day("Hari Raya Kabar Sukacita", season, "Hari Raya", WHITE)
        }
        if (fixed != null && !seasonalSunday && !holy) return fixed
        return res
    }
}
