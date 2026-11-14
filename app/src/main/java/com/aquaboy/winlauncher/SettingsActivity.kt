package com.aquaboy.winlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

// ---------- model baris pengaturan ----------

internal sealed class SRow
internal class SLink(val kind: GlyphView.Kind, val title: String, val sub: String?, val onClick: () -> Unit) : SRow()
internal class SToggle(
    val kind: GlyphView.Kind,
    val title: String,
    val sub: String?,
    val key: String,
    val def: Boolean,
    val after: (() -> Unit)? = null
) : SRow()

internal class SSegment(
    val kind: GlyphView.Kind,
    val title: String,
    val sub: String?,
    val options: List<String>,
    val key: String,
    val def: Int,
    val values: List<Int>? = null,
    val after: (() -> Unit)? = null
) : SRow()

internal class SInfo(val title: String, val value: String) : SRow()
internal class SHeader(val text: String) : SRow()
internal class SCustom(val build: () -> View) : SRow()

internal class SPage(
    val id: String,
    val title: String,
    val kind: GlyphView.Kind,
    val parent: String?,
    val rows: () -> List<SRow>
)

internal class SAppRef(val label: String, val pkg: String, val cn: ComponentName)

/** Menu Pengaturan bergaya Windows 11: panel navigasi di kiri (tablet) atau daftar kategori (HP). */
class SettingsActivity : Activity() {

    companion object {
        const val EXTRA_PAGE = "page"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var pm: PackageManager
    private val tablet: Boolean by lazy { resources.configuration.smallestScreenWidthDp >= 600 }

    private val pages = LinkedHashMap<String, SPage>()
    private val topIds = listOf(
        "system", "bluetooth", "network", "personalize", "apps",
        "accounts", "time", "access", "privacy", "update"
    )

    private var current = "home"
    private var navAvatar: FrameLayout? = null
    private var navName: TextView? = null
    private val stack = ArrayList<String>()
    private var query = ""
    private var suppressWatcher = false

    private lateinit var searchInput: EditText
    private lateinit var searchBox: View
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleBox: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var backBtn: View
    private val navViews = HashMap<String, Pair<View, View>>()

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        Th.load(this)
        setTheme(if (Th.dark) R.style.AppDark else R.style.AppLight)
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Th.PREFS, MODE_PRIVATE)
        pm = packageManager
        buildPages()
        buildUi()

        val wanted = savedInstanceState?.getString("page") ?: intent?.getStringExtra(EXTRA_PAGE)
        val start = if (wanted != null && pages.containsKey(wanted)) wanted else if (tablet) "system" else "home"
        val parent = pages[start]?.parent
        if (parent != null) stack.add(parent)
        showPage(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", current)
    }

    override fun onBackPressed() {
        when {
            query.isNotBlank() -> clearSearch()
            stack.isNotEmpty() -> showPage(stack.removeAt(stack.size - 1))
            !tablet && current != "home" -> showPage("home")
            else -> finish()
        }
    }

    // ---------- util ----------
    private fun startSys(vararg actions: String) {
        for (a in actions) {
            try {
                startActivity(Intent(a))
                return
            } catch (e: Exception) {
                // coba alternatif berikutnya
            }
        }
        Toast.makeText(this, "Pengaturan ini tidak tersedia di perangkat Anda", Toast.LENGTH_SHORT).show()
    }

    private fun startIntent(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka pengaturan ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Pengaturan", text))
        Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
    }

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return if (n.isNullOrBlank()) Build.MODEL else n
    }

    private fun launcherVersion(): String = try {
        pm.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun launcherApps(): List<SAppRef> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val selfMain = MainActivity::class.java.name
        val out = ArrayList<SAppRef>()
        for (ri in pm.queryIntentActivities(i, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == packageName && ai.name == selfMain) continue
            out.add(SAppRef(ri.loadLabel(pm).toString(), ai.packageName, ComponentName(ai.packageName, ai.name)))
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun bump() = Th.bump(this)

    private fun loadKeys(key: String): MutableList<String> =
        (prefs.getString(key, "") ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()

    private fun saveKeys(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString("\n")).apply()
        bump()
        showPage(current)
    }

    // ---------- halaman ----------
    private fun page(id: String, title: String, kind: GlyphView.Kind, parent: String?, rows: () -> List<SRow>) {
        pages[id] = SPage(id, title, kind, parent, rows)
    }

    private fun sys(kind: GlyphView.Kind, title: String, sub: String?, vararg actions: String): SLink =
        SLink(kind, title, sub) { startSys(*actions) }

    private fun buildPages() {
        page("system", "Sistem", GlyphView.Kind.SYSTEM, null) {
            listOf(
                SCustom { hero(GlyphView.Kind.SYSTEM, deviceName(), "${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}  •  API ${Build.VERSION.SDK_INT}") },
                sys(GlyphView.Kind.SYSTEM, "Tampilan", "Kecerahan, mode malam, ukuran layar dan font", "android.settings.DISPLAY_SETTINGS"),
                sys(GlyphView.Kind.VOLUME, "Suara", "Volume, nada dering, mode senyap", "android.settings.SOUND_SETTINGS"),
                sys(GlyphView.Kind.BELL, "Notifikasi", "Notifikasi aplikasi dan jangan ganggu", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "android.settings.NOTIFICATION_SETTINGS", "android.settings.SETTINGS"),
                SLink(GlyphView.Kind.BATTERY, "Daya & baterai", "Status baterai, hemat baterai") { goTo("battery") },
                SLink(GlyphView.Kind.DRIVE, "Penyimpanan", "Ruang penyimpanan dan memori") { goTo("storage") },
                SLink(GlyphView.Kind.INFO, "Tentang", "Spesifikasi perangkat dan versi") { goTo("about") }
            )
        }

        page("battery", "Daya & baterai", GlyphView.Kind.BATTERY, "system") { batteryRows() }
        page("storage", "Penyimpanan", GlyphView.Kind.DRIVE, "system") { storageRows() }
        page("about", "Tentang", GlyphView.Kind.INFO, "system") { aboutRows() }

        page("bluetooth", "Bluetooth & perangkat", GlyphView.Kind.BLUETOOTH, null) {
            listOf(
                sys(GlyphView.Kind.BLUETOOTH, "Bluetooth", "Sambungkan dan kelola perangkat Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
                sys(GlyphView.Kind.SYSTEM, "Perangkat tersambung", "USB, Bluetooth, Cast, dan lainnya", "android.settings.CONNECTED_DEVICE_SETTINGS", "android.settings.BLUETOOTH_SETTINGS"),
                sys(GlyphView.Kind.CAST, "Cast", "Tampilkan layar ke perangkat lain", "android.settings.CAST_SETTINGS"),
                sys(GlyphView.Kind.SHARE, "NFC", "Pembayaran dan berbagi dekat", "android.settings.NFC_SETTINGS"),
                sys(GlyphView.Kind.DOC, "Printer", "Layanan cetak", "android.settings.ACTION_PRINT_SETTINGS"),
                sys(GlyphView.Kind.MENU, "Keyboard", "Keyboard aktif dan metode input", "android.settings.INPUT_METHOD_SETTINGS")
            )
        }

        page("network", "Jaringan & internet", GlyphView.Kind.NET, null) {
            listOf(
                SCustom { hero(GlyphView.Kind.NET, "Koneksi saat ini", netStatus(), null) },
                sys(GlyphView.Kind.NET, "Wi-Fi", "Jaringan nirkabel", "android.settings.WIFI_SETTINGS"),
                sys(GlyphView.Kind.NET, "Jaringan seluler", "Operator dan roaming", "android.settings.NETWORK_OPERATOR_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
                sys(GlyphView.Kind.AIRPLANE, "Mode pesawat", "Matikan semua koneksi nirkabel", "android.settings.AIRPLANE_MODE_SETTINGS"),
                sys(GlyphView.Kind.PRIVACY, "VPN", "Koneksi pribadi virtual", "android.settings.VPN_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
                sys(GlyphView.Kind.SHARE, "Hotspot & tethering", "Bagikan koneksi internet", "android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
                sys(GlyphView.Kind.SORT, "Penggunaan data", "Pemakaian data seluler dan Wi-Fi", "android.settings.DATA_USAGE_SETTINGS", "android.settings.WIRELESS_SETTINGS")
            )
        }

        page("personalize", "Personalisasi", GlyphView.Kind.PERSONALIZE, null) {
            listOf(
                SHeader("Tema"),
                SCustom { themeTiles() },
                SHeader("Warna aksen"),
                SCustom { swatches() },
                SHeader("Latar belakang"),
                SToggle(GlyphView.Kind.PHOTO, "Gunakan wallpaper sistem", "Matikan untuk memakai wallpaper bawaan sesuai warna aksen", "syswp", false),
                SLink(GlyphView.Kind.PHOTO, "Ganti wallpaper sistem", "Pilih gambar dari galeri atau wallpaper") {
                    startIntent(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Pilih wallpaper"))
                },
                SHeader("Efek"),
                SToggle(GlyphView.Kind.PERSONALIZE, "Efek transparansi", "Panel Start dan taskbar tembus pandang", "transparency", true),
                SToggle(GlyphView.Kind.UPDATE, "Animasi", "Animasi buka/tutup panel", "animations", true),
                SHeader("Tata letak"),
                SLink(GlyphView.Kind.TASKBAR, "Taskbar", "Perataan, ukuran ikon, tombol cari") { goTo("taskbar") },
                SLink(GlyphView.Kind.START, "Start", "Kolom, rekomendasi, aplikasi tersemat") { goTo("start") },
                SLink(GlyphView.Kind.HOME, "Desktop", "Ikon pintasan di layar utama") { goTo("desktop") }
            )
        }

        page("taskbar", "Taskbar", GlyphView.Kind.TASKBAR, "personalize") {
            listOf(
                SSegment(GlyphView.Kind.TASKBAR, "Perataan ikon taskbar", null, listOf("Kiri", "Tengah"), "tb_center", if (tablet) 1 else 0),
                SSegment(GlyphView.Kind.APPS, "Ukuran ikon", null, listOf("Kecil", "Sedang", "Besar"), "tb_size", 1),
                SToggle(GlyphView.Kind.SEARCH, "Tombol cari", "Tampilkan tombol cari di taskbar", "tb_search", true),
                SToggle(GlyphView.Kind.TIME, "Tampilkan tanggal", "Tanggal di bawah jam (tablet)", "tb_date", true),
                SToggle(GlyphView.Kind.UP, "Sembunyikan bilah navigasi Android", "Tombol navigasi sistem disembunyikan agar layar lebih luas; geser dari tepi bawah untuk memunculkannya sementara", "fullscreen", false),
                SHeader("Aplikasi di taskbar"),
                SCustom { pinManager("taskbar") }
            )
        }

        page("start", "Start", GlyphView.Kind.START, "personalize") {
            listOf(
                SSegment(GlyphView.Kind.APPS, "Jumlah kolom aplikasi", "Otomatis menyesuaikan ukuran layar", listOf("Otomatis", "4", "5", "6"), "start_cols", 0, listOf(0, 4, 5, 6)),
                SToggle(GlyphView.Kind.STAR, "Tampilkan rekomendasi", "Aplikasi yang baru dibuka", "start_recents", true),
                SLink(GlyphView.Kind.DELETE, "Hapus riwayat aplikasi terbaru", null) {
                    prefs.edit().remove("recents").apply()
                    bump()
                    Toast.makeText(this, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
                },
                SHeader("Aplikasi disematkan"),
                SCustom { pinManager("start") }
            )
        }

        page("desktop", "Desktop", GlyphView.Kind.HOME, "personalize") {
            listOf(
                SToggle(GlyphView.Kind.HOME, "Tampilkan ikon desktop", null, "desk_show", true),
                SToggle(GlyphView.Kind.APPS, "Tampilkan widget desktop", "Tambah widget lewat tekan-lama pada desktop → Tambah widget", "widgets_show", true),
                SSegment(GlyphView.Kind.APPS, "Ukuran ikon", null, listOf("Kecil", "Sedang", "Besar"), "desk_size", 1),
                SHeader("Ikon di desktop"),
                SCustom { pinManager("desktop") },
                SHeader("Pemulihan"),
                SLink(GlyphView.Kind.UPDATE, "Kembalikan tata letak default", "Atur ulang pin Start, taskbar, dan desktop") {
                    confirmDialog("Kembalikan tata letak?", "Semua pin Start, taskbar, dan desktop akan diatur ulang ke default.", "Kembalikan") {
                        prefs.edit().remove("start").remove("taskbar").remove("desktop").remove("recents").apply()
                        bump()
                        showPage(current)
                    }
                }
            )
        }

        page("apps", "Aplikasi", GlyphView.Kind.APPS, null) {
            listOf(
                SLink(GlyphView.Kind.APPS, "Aplikasi terpasang", "${launcherApps().size} aplikasi") { goTo("installed") },
                sys(GlyphView.Kind.CHECK, "Aplikasi default", "Peramban, telepon, SMS, dan lainnya", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "android.settings.SETTINGS"),
                sys(GlyphView.Kind.HOME, "Launcher bawaan", "Pilih aplikasi layar beranda", "android.settings.HOME_SETTINGS"),
                sys(GlyphView.Kind.SYSTEM, "Kelola semua aplikasi", "Izin, penyimpanan, dan paksa berhenti", "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"),
                SLink(GlyphView.Kind.DOWNLOAD, "Pasang aplikasi dari sumber lain", "Izinkan pemasangan APK untuk aplikasi tertentu") {
                    if (Build.VERSION.SDK_INT >= 26) {
                        startIntent(Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:$packageName")))
                    } else {
                        startSys("android.settings.SECURITY_SETTINGS")
                    }
                }
            )
        }
        page("installed", "Aplikasi terpasang", GlyphView.Kind.APPS, "apps") {
            listOf(SCustom { installedApps() })
        }

        page("accounts", "Akun", GlyphView.Kind.ACCOUNTS, null) {
            listOf(
                SCustom { profileCard() },
                sys(GlyphView.Kind.SYSTEM, "Akun & sinkronisasi", "Akun Google dan akun lain", "android.settings.SYNC_SETTINGS"),
                sys(GlyphView.Kind.PLUS, "Tambah akun", null, "android.settings.ADD_ACCOUNT_SETTINGS"),
                sys(GlyphView.Kind.ACCOUNTS, "Pengguna", "Pengguna ganda dan profil", "android.settings.USER_SETTINGS", "android.settings.SETTINGS")
            )
        }

        page("time", "Waktu & bahasa", GlyphView.Kind.TIME, null) {
            listOf(
                SCustom {
                    val now = Date()
                    hero(
                        GlyphView.Kind.TIME,
                        DateFormat.getTimeFormat(this).format(now),
                        DateFormat.getLongDateFormat(this).format(now),
                        TimeZone.getDefault().displayName
                    )
                },
                sys(GlyphView.Kind.TIME, "Tanggal & waktu", "Zona waktu dan format jam", "android.settings.DATE_SETTINGS"),
                sys(GlyphView.Kind.LANGUAGE, "Bahasa & wilayah", "Bahasa tampilan dan format wilayah", "android.settings.LOCALE_SETTINGS"),
                sys(GlyphView.Kind.MENU, "Keyboard", "Metode input dan ejaan", "android.settings.INPUT_METHOD_SETTINGS")
            )
        }

        page("access", "Aksesibilitas", GlyphView.Kind.ACCESS, null) {
            listOf(
                sys(GlyphView.Kind.ACCESS, "Aksesibilitas", "Pembaca layar, kontras, dan kontrol interaksi", "android.settings.ACCESSIBILITY_SETTINGS"),
                sys(GlyphView.Kind.SYSTEM, "Ukuran teks & tampilan", "Ukuran font dan ukuran tampilan", "android.settings.DISPLAY_SETTINGS"),
                sys(GlyphView.Kind.DOC, "Subtitle", "Gaya dan ukuran teks subtitle", "android.settings.CAPTIONING_SETTINGS"),
                SToggle(GlyphView.Kind.UPDATE, "Kurangi animasi launcher", "Matikan transisi panel (kebalikan dari Animasi)", "animations_off_hint", false) {
                    prefs.edit().putBoolean("animations", !prefs.getBoolean("animations_off_hint", false)).apply()
                    bump()
                }
            )
        }

        page("privacy", "Privasi & keamanan", GlyphView.Kind.PRIVACY, null) {
            listOf(
                sys(GlyphView.Kind.PRIVACY, "Keamanan", "Kunci layar, sidik jari, enkripsi", "android.settings.SECURITY_SETTINGS"),
                sys(GlyphView.Kind.EYE, "Privasi & izin", "Izin aplikasi dan dasbor privasi", "android.settings.PRIVACY_SETTINGS", "android.settings.SECURITY_SETTINGS"),
                sys(GlyphView.Kind.PIN, "Lokasi", "Layanan lokasi", "android.settings.LOCATION_SOURCE_SETTINGS"),
                SLink(GlyphView.Kind.FOLDER, "Akses semua file untuk File Manager", "Diperlukan agar File Manager dapat membaca seluruh penyimpanan") {
                    if (Build.VERSION.SDK_INT >= 30) {
                        startIntent(Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:$packageName")))
                    } else {
                        startIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                },
                SLink(GlyphView.Kind.SYSTEM, "Izin ubah pengaturan sistem", "Dipakai untuk slider kecerahan di Quick Settings") {
                    startIntent(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                }
            )
        }

        page("update", "Pembaruan", GlyphView.Kind.UPDATE, null) {
            listOf(
                SCustom { hero(GlyphView.Kind.UPDATE, "Android ${Build.VERSION.RELEASE}", "Patch keamanan: ${securityPatch()}", "Win Launcher ${launcherVersion()}") },
                sys(GlyphView.Kind.UPDATE, "Periksa pembaruan sistem", "Buka pembaruan sistem Android", "android.settings.SYSTEM_UPDATE_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS"),
                SInfo("Versi Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                SInfo("Patch keamanan", securityPatch()),
                SInfo("Versi launcher", launcherVersion())
            )
        }
    }

    private fun securityPatch(): String =
        if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "-"

    // ---------- baris data dinamis ----------
    private fun netStatus(): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork
        val c = if (n != null) cm.getNetworkCapabilities(n) else null
        when {
            c == null -> "Tidak terhubung"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Terhubung melalui Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Terhubung melalui data seluler"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Terhubung melalui Ethernet"
            else -> "Terhubung"
        }
    } catch (e: Exception) {
        "Status tidak diketahui"
    }

    private fun batteryRows(): List<SRow> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rows = ArrayList<SRow>()
        if (i != null) {
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val status = when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Mengisi daya"
                BatteryManager.BATTERY_STATUS_FULL -> "Penuh"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Tidak mengisi daya"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Tidak mengisi daya"
                else -> "Tidak diketahui"
            }
            val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Adaptor AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Nirkabel"
                else -> "Baterai"
            }
            val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Baik"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Terlalu panas"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Rusak"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Tegangan berlebih"
                BatteryManager.BATTERY_HEALTH_COLD -> "Terlalu dingin"
                else -> "Tidak diketahui"
            }
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            rows.add(SCustom {
                val glyph = icon(GlyphView.Kind.BATTERY).apply {
                    level = max(0, pct)
                    charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                }
                bigStat(glyph, if (pct >= 0) "$pct%" else "-", status)
            })
            rows.add(SHeader("Detail"))
            rows.add(SInfo("Status", status))
            rows.add(SInfo("Sumber daya", plug))
            rows.add(SInfo("Kesehatan baterai", health))
            rows.add(SInfo("Suhu", String.format(Locale.US, "%.1f °C", temp)))
            rows.add(SInfo("Tegangan", "$volt mV"))
            rows.add(SInfo("Teknologi", i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "-"))
        }
        rows.add(SHeader("Pengaturan"))
        rows.add(sys(GlyphView.Kind.BATTERY, "Hemat baterai", "Aktifkan dan atur penghemat baterai", "android.settings.BATTERY_SAVER_SETTINGS"))
        rows.add(sys(GlyphView.Kind.SORT, "Penggunaan baterai", "Aplikasi yang paling banyak memakai daya", "android.intent.action.POWER_USAGE_SUMMARY", "android.settings.BATTERY_SAVER_SETTINGS"))
        return rows
    }

    private fun storageRows(): List<SRow> {
        val rows = ArrayList<SRow>()
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val free = st.availableBlocksLong * st.blockSizeLong
        val used = total - free
        rows.add(SCustom {
            val box = ll(true).apply {
                background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }
            box.addView(tv("Penyimpanan internal", 14f, Th.p.text, true))
            box.addView(tv("${fmtSize(used)} terpakai dari ${fmtSize(total)}", 13f, Th.p.dim).apply { setPadding(0, dp(4), 0, dp(10)) })
            box.addView(usageBar(used, total), LinearLayout.LayoutParams(MATCH, dp(8)))
            box
        })
        rows.add(SInfo("Total", fmtSize(total)))
        rows.add(SInfo("Terpakai", fmtSize(used)))
        rows.add(SInfo("Tersedia", fmtSize(free)))

        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        rows.add(SHeader("Memori (RAM)"))
        rows.add(SCustom {
            val box = ll(true).apply {
                background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }
            box.addView(tv("${fmtSize(mi.totalMem - mi.availMem)} dipakai dari ${fmtSize(mi.totalMem)}", 13f, Th.p.dim).apply { setPadding(0, 0, 0, dp(10)) })
            box.addView(usageBar(mi.totalMem - mi.availMem, mi.totalMem), LinearLayout.LayoutParams(MATCH, dp(8)))
            box
        })
        rows.add(SHeader("Kelola"))
        rows.add(SLink(GlyphView.Kind.FOLDER, "Buka File Manager", "Telusuri file dan folder") {
            startActivity(Intent(this, FilesActivity::class.java))
        })
        rows.add(sys(GlyphView.Kind.DRIVE, "Penyimpanan Android", "Rincian per kategori dan bersihkan ruang", "android.settings.INTERNAL_STORAGE_SETTINGS"))
        return rows
    }

    private fun aboutRows(): List<SRow> {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val dm = resources.displayMetrics
        val info = listOf(
            "Nama perangkat" to deviceName(),
            "Produsen" to Build.MANUFACTURER,
            "Model" to Build.MODEL,
            "Merek" to Build.BRAND,
            "Perangkat" to Build.DEVICE,
            "Versi Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Patch keamanan" to securityPatch(),
            "Nomor build" to Build.DISPLAY,
            "Kernel" to (System.getProperty("os.version") ?: "-"),
            "Arsitektur CPU" to Build.SUPPORTED_ABIS.joinToString(", "),
            "RAM terpasang" to fmtSize(mi.totalMem),
            "Penyimpanan internal" to fmtSize(total),
            "Layar" to "${dm.widthPixels} × ${dm.heightPixels} px  •  ${dm.densityDpi} dpi",
            "Bahasa" to Locale.getDefault().displayName,
            "Versi launcher" to launcherVersion()
        )
        val rows = ArrayList<SRow>()
        rows.add(SCustom { hero(GlyphView.Kind.SYSTEM, deviceName(), "${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}") })
        rows.add(SHeader("Spesifikasi perangkat"))
        for ((k, v) in info) rows.add(SInfo(k, v))
        rows.add(SHeader("Tindakan"))
        rows.add(SLink(GlyphView.Kind.COPY, "Salin spesifikasi", "Salin semua detail di atas ke clipboard") {
            copy(info.joinToString("\n") { "${it.first}: ${it.second}" })
        })
        rows.add(sys(GlyphView.Kind.INFO, "Info perangkat Android", "Halaman Tentang ponsel/tablet bawaan", "android.settings.DEVICE_INFO_SETTINGS"))
        return rows
    }

    // ---------- komponen kustom ----------
    private fun hero(kind: GlyphView.Kind, title: String, sub: String, sub2: String?): View {
        val box = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val badge = FrameLayout(this).apply {
            background = rounded(Th.blend(Th.p.card, Th.accent, 0.2f), dpf(16f))
            addView(icon(kind, Th.accent), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        box.addView(badge, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(18) })
        val col = ll(true)
        col.addView(tv(title, 20f, Th.p.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(tv(sub, 13f, Th.p.dim).apply { setPadding(0, dp(3), 0, 0) })
        if (sub2 != null) col.addView(tv(sub2, 12f, Th.p.faint).apply { setPadding(0, dp(2), 0, 0) })
        box.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return box
    }

    // ---------- profil akun ----------
    private val reqPhoto = 4711

    private fun profileCard(): View {
        val box = ll(true).apply {
            background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        val top = ll(false, Gravity.CENTER_VERTICAL)
        val holder = FrameLayout(this)
        Profile.fill(holder, 72, 28f)
        top.addView(holder, LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(18) })
        val col = ll(true)
        col.addView(tv(Profile.name(this), 20f, Th.p.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(tv("Akun lokal", 13f, Th.p.dim).apply { setPadding(0, dp(3), 0, 0) })
        top.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(top)

        val actions = ll(false)
        actions.addView(textButton("Ganti foto", true) {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            try {
                startActivityForResult(Intent.createChooser(i, "Pilih foto"), reqPhoto)
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak ada aplikasi galeri", Toast.LENGTH_SHORT).show()
            }
        })
        actions.addView(textButton("Ubah nama", false) {
            promptDialog("Ubah nama akun", Profile.name(this), "Nama", "Simpan") { v ->
                if (v.isNotBlank()) {
                    Profile.setName(this, v)
                    afterProfileChange()
                }
            }
        })
        if (Profile.hasPhoto(this)) {
            actions.addView(textButton("Hapus foto", false) {
                Profile.clearPhoto(this)
                afterProfileChange()
            })
        }
        box.addView(actions, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
        return box
    }

    private fun afterProfileChange() {
        bump()
        navAvatar?.let { Profile.fill(it, 48, 18f) }
        navName?.text = Profile.name(this)
        showPage(current)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqPhoto && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null && Profile.savePhoto(this, uri)) {
                afterProfileChange()
            } else {
                Toast.makeText(this, "Foto tidak dapat dibaca", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bigStat(glyph: GlyphView, value: String, label: String): View {
        val box = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(glyph, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(18) })
        val col = ll(true)
        col.addView(tv(value, 32f, Th.p.text, true))
        col.addView(tv(label, 13f, Th.p.dim))
        box.addView(col)
        return box
    }

    private fun usageBar(used: Long, total: Long): View {
        val bar = ll(false).apply { background = rounded(Th.blend(Th.p.faint, Th.p.card, 0.6f), dpf(4f)) }
        val frac = if (total > 0) used.toFloat() / total.toFloat() else 0f
        val fill = View(this).apply {
            background = rounded(if (frac > 0.9f) Th.danger else Th.accent, dpf(4f))
        }
        bar.addView(fill, LinearLayout.LayoutParams(0, MATCH, max(frac, 0.02f)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, MATCH, max(1f - frac, 0.0f)))
        return bar
    }

    private fun themeTiles(): View {
        val row = ll(false)
        val names = listOf("Terang", "Gelap", "Ikuti sistem")
        val modeFor = intArrayOf(1, 2, 0)
        val sysDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        for (i in 0..2) {
            val pal = when (i) {
                0 -> Th.LIGHT
                1 -> Th.DARK
                else -> if (sysDark) Th.DARK else Th.LIGHT
            }
            val selected = Th.mode == modeFor[i]
            val preview = FrameLayout(this).apply {
                background = rounded(pal.bg, dpf(10f), if (selected) Th.accent else Th.p.stroke, dp(if (selected) 2 else 1))
            }
            val win = FrameLayout(this).apply {
                background = rounded(pal.card, dpf(5f), pal.stroke, dp(1))
                addView(View(this@SettingsActivity).apply { background = rounded(Th.accent, dpf(2f)) }, FrameLayout.LayoutParams(MATCH, dp(7), Gravity.TOP))
            }
            preview.addView(win, FrameLayout.LayoutParams(dp(58), dp(36), Gravity.CENTER).apply { bottomMargin = dp(6) })
            preview.addView(
                View(this).apply { setBackgroundColor(pal.taskbar) },
                FrameLayout.LayoutParams(MATCH, dp(9), Gravity.BOTTOM)
            )
            val tile = ll(true, Gravity.CENTER_HORIZONTAL).apply {
                setOnClickListener {
                    prefs.edit().putInt("theme_mode", modeFor[i]).apply()
                    bump()
                    recreate()
                }
            }
            tile.addView(preview, LinearLayout.LayoutParams(MATCH, dp(84)))
            tile.addView(tv(names[i], 13f, if (selected) Th.accent else Th.p.text, selected).apply { setPadding(0, dp(8), 0, dp(2)) })
            row.addView(tile, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val box = ll(true).apply {
            background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(14), dp(16), dp(14), dp(12))
        }
        box.addView(row)
        return box
    }

    private fun swatches(): View {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        for ((i, a) in Th.ACCENTS.withIndex()) {
            val c = if (Th.dark) a.dark else a.light
            val sel = i == Th.accentIdx
            val dot = FrameLayout(this).apply {
                background = rounded(c, dpf(21f), if (sel) Th.p.text else 0, if (sel) dp(2) else 0)
                if (sel) addView(icon(GlyphView.Kind.CHECK, Th.onAccent), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }
            val cell = ll(true, Gravity.CENTER_HORIZONTAL).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = ripple(dpf(8f))
                setOnClickListener {
                    prefs.edit().putInt("accent_idx", i).apply()
                    bump()
                    recreate()
                }
            }
            cell.addView(dot, LinearLayout.LayoutParams(dp(42), dp(42)))
            cell.addView(tv(a.name, 11f, if (sel) Th.p.text else Th.p.dim).apply { setPadding(0, dp(6), 0, 0) })
            row.addView(cell)
        }
        hs.addView(row)
        val box = ll(true).apply {
            background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        box.addView(hs)
        return box
    }

    private fun labelOf(cn: ComponentName): String? = try {
        pm.getActivityInfo(cn, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        null
    }

    private fun smallBtn(kind: GlyphView.Kind, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            background = ripple(dpf(6f))
            addView(icon(kind, Th.p.dim), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { onClick() }
        }

    /** Daftar aplikasi yang disematkan (taskbar/Start/desktop) dengan tombol naik dan hapus. */
    private fun pinManager(prefKey: String): View {
        val box = ll(true).apply { background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1)) }
        val keys = loadKeys(prefKey)
        for ((i, k) in keys.withIndex()) {
            val cn = ComponentName.unflattenFromString(k) ?: continue
            val label = labelOf(cn) ?: continue
            val row = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(16), dp(8), dp(8), dp(8)) }
            row.addView(
                ImageView(this).apply {
                    try { setImageDrawable(pm.getActivityIcon(cn)) } catch (e: Exception) { }
                },
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(14) }
            )
            row.addView(tv(label, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (i > 0) {
                row.addView(smallBtn(GlyphView.Kind.UP) {
                    val l = loadKeys(prefKey)
                    val idx = l.indexOf(k)
                    if (idx > 0) {
                        l.removeAt(idx)
                        l.add(idx - 1, k)
                        saveKeys(prefKey, l)
                    }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            row.addView(smallBtn(GlyphView.Kind.CLOSE) {
                val l = loadKeys(prefKey)
                l.remove(k)
                saveKeys(prefKey, l)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            box.addView(row)
        }
        val add = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ripple(dpf(8f))
            setOnClickListener { showAddDialog(prefKey) }
        }
        add.addView(icon(GlyphView.Kind.PLUS, Th.accent), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(14) })
        add.addView(tv("Tambah aplikasi", 14f, Th.accent, true))
        box.addView(add)
        return box
    }

    private fun showAddDialog(prefKey: String) {
        val existing = loadKeys(prefKey)
        val candidates = launcherApps().filter { it.cn.flattenToString() !in existing }
        val list = ll(true)
        var dlg: Dialog? = null
        for (a in candidates) {
            val row = ll(false, Gravity.CENTER_VERTICAL).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = ripple(dpf(8f))
                setOnClickListener {
                    dlg?.dismiss()
                    val l = loadKeys(prefKey)
                    l.add(a.cn.flattenToString())
                    saveKeys(prefKey, l)
                }
            }
            row.addView(
                ImageView(this).apply {
                    try { setImageDrawable(pm.getActivityIcon(a.cn)) } catch (e: Exception) { }
                },
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(14) }
            )
            row.addView(tv(a.label, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            list.addView(row)
        }
        if (candidates.isEmpty()) list.addView(tv("Semua aplikasi sudah ditambahkan.", 13f, Th.p.dim))
        val sv = ScrollView(this).apply { addView(list) }
        val holder = FrameLayout(this)
        holder.addView(sv, FrameLayout.LayoutParams(MATCH, dp(360)))
        dlg = showDialog("Tambah aplikasi", holder, null, "Tutup")
    }

    private fun installedApps(): View {
        val apps = launcherApps()
        val box = ll(true)
        val filter = EditText(this).apply {
            hint = "Filter aplikasi (${apps.size})"
            setHintTextColor(Th.p.faint)
            setTextColor(Th.p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(filter, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        val list = ll(true).apply { background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1)) }
        box.addView(list)

        fun fill(q: String) {
            list.removeAllViews()
            val ql = q.trim().lowercase()
            for (a in apps) {
                if (ql.isNotEmpty() && !a.label.lowercase().contains(ql) && !a.pkg.lowercase().contains(ql)) continue
                val row = ll(false, Gravity.CENTER_VERTICAL).apply {
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = ripple(dpf(8f))
                    setOnClickListener {
                        startIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null)))
                    }
                }
                row.addView(
                    ImageView(this).apply {
                        try { setImageDrawable(pm.getActivityIcon(a.cn)) } catch (e: Exception) { }
                    },
                    LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(14) }
                )
                val col = ll(true)
                col.addView(tv(a.label, 14f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                col.addView(tv(a.pkg, 11f, Th.p.faint).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(icon(GlyphView.Kind.CHEV_RIGHT, Th.p.faint), LinearLayout.LayoutParams(dp(14), dp(14)))
                list.addView(row)
            }
            if (list.childCount == 0) list.addView(tv("Tidak ada aplikasi yang cocok.", 13f, Th.p.dim).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) })
        }
        fill("")
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                fill(s?.toString() ?: "")
            }
        })
        return box
    }

    // ---------- konstruksi UI ----------
    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply { setBackgroundColor(Th.p.bg) }
        edgeToEdge(rootFrame)

        searchInput = EditText(this).apply {
            hint = "Cari pengaturan"
            setHintTextColor(Th.p.faint)
            setTextColor(Th.p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(8), 0, dp(8), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressWatcher) return
                    query = s?.toString() ?: ""
                    showPage(current)
                }
            })
        }
        searchBox = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(14), 0, dp(10), 0)
            addView(icon(GlyphView.Kind.SEARCH, Th.p.dim), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(searchInput, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        content = ll(true)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val pad = if (tablet) dp(32) else dp(16)
        content.setPadding(pad, dp(8), pad, dp(32))
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))

        headerTitle = tv("", 20f, Th.p.text, true)
        backBtn = FrameLayout(this).apply {
            background = ripple(dpf(20f))
            addView(icon(GlyphView.Kind.BACK), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { onBackPressed() }
        }
        titleBox = ll(false, Gravity.CENTER_VERTICAL)

        if (tablet) {
            val body = ll(false)
            val nav = ll(true).apply { setPadding(dp(16), dp(20), dp(8), dp(12)) }

            val user = ll(false, Gravity.CENTER_VERTICAL).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = ripple(dpf(8f))
                setOnClickListener { goTo("accounts") }
            }
            val holder = FrameLayout(this)
            Profile.fill(holder, 48, 18f)
            navAvatar = holder
            user.addView(holder, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
            val ucol = ll(true)
            navName = tv(Profile.name(this), 15f, Th.p.text, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            ucol.addView(navName)
            ucol.addView(tv("Akun lokal", 12f, Th.p.dim))
            user.addView(ucol, LinearLayout.LayoutParams(0, WRAP, 1f))
            nav.addView(user, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
            nav.addView(searchBox, LinearLayout.LayoutParams(MATCH, dp(40)).apply { bottomMargin = dp(10) })

            val navList = ll(true)
            for (id in topIds) navList.addView(navItem(id), LinearLayout.LayoutParams(MATCH, dp(44)).apply { bottomMargin = dp(2) })
            nav.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(navList)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val main = ll(true)
            main.addView(titleBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(32), dp(28), dp(32), dp(10)) })
            main.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

            body.addView(nav, LinearLayout.LayoutParams(dp(320), MATCH))
            body.addView(main, LinearLayout.LayoutParams(0, MATCH, 1f))
            rootFrame.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val body = ll(true)
            val header = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), dp(4), dp(16), dp(4)) }
            header.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            header.addView(headerTitle, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
            body.addView(header, LinearLayout.LayoutParams(MATCH, dp(56)))
            body.addView(searchBox, LinearLayout.LayoutParams(MATCH, dp(42)).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })
            body.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            rootFrame.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        setContentView(rootFrame)
        applyBarIcons()
    }

    private fun navItem(id: String): View {
        val pg = pages[id]!!
        val item = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(8), 0, dp(12), 0)
            setOnClickListener {
                clearSearchSilently()
                stack.clear()
                showPage(id)
            }
        }
        val bar = View(this).apply { background = rounded(Th.accent, dpf(2f)) }
        item.addView(bar, LinearLayout.LayoutParams(dp(3), dp(16)).apply { rightMargin = dp(10) })
        item.addView(icon(pg.kind), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(14) })
        item.addView(tv(pg.title, 14f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        navViews[id] = Pair(item, bar)
        return item
    }

    private fun topOf(id: String): String {
        var cur = id
        while (true) {
            val parent = pages[cur]?.parent ?: return cur
            cur = parent
        }
    }

    private fun updateNav() {
        if (!tablet) return
        val sel = topOf(current)
        for ((id, v) in navViews) {
            val on = id == sel
            v.first.background = if (on) rounded(Th.p.cardHover, dpf(6f), Th.p.stroke, dp(1)) else ripple(dpf(6f))
            v.second.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    // ---------- navigasi ----------
    private fun goTo(id: String) {
        clearSearchSilently()
        stack.add(current)
        showPage(id)
    }

    private fun clearSearchSilently() {
        suppressWatcher = true
        searchInput.setText("")
        suppressWatcher = false
        query = ""
    }

    private fun clearSearch() {
        clearSearchSilently()
        showPage(current)
    }

    private fun showPage(id: String) {
        current = id
        content.removeAllViews()
        val searching = query.isNotBlank()
        when {
            searching -> renderSearch(query.trim())
            id == "home" -> renderHome()
            else -> pages[id]?.let { renderRows(it.rows(), content) }
        }
        renderTitle(searching)
        updateNav()
        backBtn.visibility = if (!tablet && (id != "home" || searching)) View.VISIBLE else View.INVISIBLE
        scroll.scrollTo(0, 0)
    }

    private fun renderTitle(searching: Boolean) {
        val pg = pages[current]
        val text = when {
            searching -> "Hasil pencarian"
            current == "home" -> "Pengaturan"
            else -> pg?.title ?: "Pengaturan"
        }
        headerTitle.text = text
        titleBox.removeAllViews()
        if (!tablet) return
        if (searching || pg == null) {
            titleBox.addView(tv(text, 26f, Th.p.text, true))
            return
        }
        val chain = ArrayList<SPage>()
        var p: SPage? = pg
        while (p != null) {
            chain.add(0, p)
            p = p.parent?.let { pages[it] }
        }
        for ((i, c) in chain.withIndex()) {
            if (i < chain.size - 1) {
                titleBox.addView(tv(c.title, 24f, Th.p.dim, false).apply {
                    background = ripple(dpf(6f))
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnClickListener {
                        stack.clear()
                        showPage(c.id)
                    }
                })
                titleBox.addView(icon(GlyphView.Kind.CHEV_RIGHT, Th.p.dim), LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(dp(4), 0, dp(4), 0) })
            } else {
                titleBox.addView(tv(c.title, 26f, Th.p.text, true).apply { setPadding(dp(6), 0, 0, 0) })
            }
        }
    }

    private fun renderHome() {
        content.addView(hero(GlyphView.Kind.SYSTEM, deviceName(), "${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}"),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        for (id in topIds) {
            val pg = pages[id] ?: continue
            addCard(content, linkShell(pg.kind, pg.title, null) { goTo(id) })
        }
    }

    private fun renderSearch(q: String) {
        val ql = q.lowercase()
        var found = 0
        for (pg in pages.values) {
            if (pg.title.lowercase().contains(ql)) {
                addCard(content, linkShell(pg.kind, pg.title, "Halaman pengaturan") { goTo(pg.id) })
                found++
            }
            for (r in pg.rows()) {
                val title: String? = when (r) {
                    is SLink -> r.title
                    is SToggle -> r.title
                    is SSegment -> r.title
                    is SInfo -> r.title
                    else -> null
                }
                val sub: String? = when (r) {
                    is SLink -> r.sub
                    is SToggle -> r.sub
                    is SSegment -> r.sub
                    else -> null
                }
                if (title == null) continue
                val hit = title.lowercase().contains(ql) || (sub != null && sub.lowercase().contains(ql))
                if (!hit) continue
                val crumb = pg.title + (if (sub != null) "  •  $sub" else "")
                addCard(content, linkShell(pg.kind, title, crumb) {
                    if (r is SLink) {
                        clearSearchSilently()
                        r.onClick()
                        showPage(current)
                    } else {
                        goTo(pg.id)
                    }
                })
                found++
            }
        }
        if (found == 0) {
            content.addView(tv("Tidak ada hasil untuk \"$q\"", 14f, Th.p.dim).apply { setPadding(dp(4), dp(24), dp(4), dp(8)) })
        }
    }

    // ---------- render baris ----------
    private fun addCard(parent: LinearLayout, row: View) {
        val card = FrameLayout(this).apply { background = rounded(Th.p.card, dpf(8f), Th.p.stroke, dp(1)) }
        card.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        parent.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
    }

    private fun rowShell(kind: GlyphView.Kind?, title: String, sub: String?): LinearLayout {
        val row = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(60)
            background = ripple(dpf(8f))
        }
        if (kind != null) row.addView(icon(kind), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(16) })
        val col = ll(true)
        col.addView(tv(title, 14f))
        if (!sub.isNullOrEmpty()) col.addView(tv(sub, 12f, Th.p.dim).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private fun linkShell(kind: GlyphView.Kind, title: String, sub: String?, onClick: () -> Unit): View {
        val shell = rowShell(kind, title, sub)
        shell.addView(icon(GlyphView.Kind.CHEV_RIGHT, Th.p.faint), LinearLayout.LayoutParams(dp(14), dp(14)))
        shell.setOnClickListener { onClick() }
        return shell
    }

    private fun renderRows(rows: List<SRow>, parent: LinearLayout) {
        for (r in rows) {
            when (r) {
                is SHeader -> parent.addView(tv(r.text, 13f, Th.p.dim, true).apply { setPadding(dp(4), dp(18), 0, dp(6)) })
                is SCustom -> parent.addView(r.build(), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
                is SLink -> addCard(parent, linkShell(r.kind, r.title, r.sub) { r.onClick() })
                is SToggle -> {
                    val shell = rowShell(r.kind, r.title, r.sub)
                    val sw = themedSwitch(prefs.getBoolean(r.key, r.def)) { v ->
                        prefs.edit().putBoolean(r.key, v).apply()
                        bump()
                        r.after?.invoke()
                    }
                    shell.addView(sw)
                    shell.setOnClickListener { sw.toggle() }
                    addCard(parent, shell)
                }
                is SSegment -> {
                    val shell = rowShell(r.kind, r.title, r.sub)
                    val stored = prefs.getInt(r.key, r.def)
                    val idx = (r.values?.indexOf(stored) ?: stored).coerceIn(0, r.options.size - 1)
                    val ctl = segmented(r.options, idx) { i ->
                        val v = r.values?.get(i) ?: i
                        prefs.edit().putInt(r.key, v).apply()
                        bump()
                        r.after?.invoke()
                    }
                    if (tablet) {
                        shell.addView(ctl)
                        addCard(parent, shell)
                    } else {
                        val box = ll(true)
                        box.addView(shell)
                        box.addView(ctl, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), 0, dp(16), dp(14)) })
                        addCard(parent, box)
                    }
                }
                is SInfo -> {
                    val shell = rowShell(null, r.title, null)
                    shell.minimumHeight = dp(48)
                    shell.addView(tv(r.value, 13f, Th.p.dim).apply {
                        gravity = Gravity.END
                        maxWidth = dp(260)
                    })
                    shell.setOnLongClickListener {
                        copy("${r.title}: ${r.value}")
                        true
                    }
                    addCard(parent, shell)
                }
            }
        }
    }
}
