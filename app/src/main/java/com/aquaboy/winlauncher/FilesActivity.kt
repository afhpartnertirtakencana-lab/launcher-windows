package com.aquaboy.winlauncher

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.random.Random

/** Pemegang view untuk satu baris/sel pada daftar berkas. */
internal class FmHolder(val root: LinearLayout) {
    var icon: GlyphView? = null
    var thumb: ImageView? = null
    var check: FrameLayout? = null
    var checkIcon: GlyphView? = null
    var name: TextView? = null
    var sub: TextView? = null
    var date: TextView? = null
    var type: TextView? = null
    var size: TextView? = null
    var tag: String? = null
}

/** File Explorer ala Windows 11. */
class FilesActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_PERM = 77
        private var clipFiles: List<File> = emptyList()
        private var clipCut = false
    }

    // ---------- state ----------
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val trashLock = Any()
    private val thumbCache = object : LruCache<String, Bitmap>(
        max(2048, (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var tabletUi = false
    private var seenRev = 0
    private var accessOk = true
    private var volumes: List<FmVolume> = emptyList()

    private var loc = "home"
    private val backStack = ArrayList<String>()
    private val fwdStack = ArrayList<String>()
    private var items: List<FmItem> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selectActive = false
    private var listFailed = false

    private var viewMode = 0
    private var sortKey = 0
    private var sortDesc = false
    private var foldersFirst = true
    private var showHidden = false
    private var showExt = true

    private val loadGen = AtomicInteger(0)
    private val searchGen = AtomicInteger(0)
    private var searching = false
    private var searchDone = true
    private var searchQuery = ""
    private var suppressSearch = false
    private val searchRunnable = Runnable { startSearch(searchInput.text.toString().trim()) }

    private var drawerOpen = false

    // ---------- views ----------
    private lateinit var rootFrame: FrameLayout
    private lateinit var navPane: LinearLayout
    private lateinit var navList: LinearLayout
    private lateinit var scrimView: View
    private lateinit var addrRow: LinearLayout
    private lateinit var selRow: LinearLayout
    private lateinit var selTitle: TextView
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var crumbBox: LinearLayout
    private lateinit var pathInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var searchBox: View
    private var searchRow: View? = null
    private lateinit var cmdBar: HorizontalScrollView
    private lateinit var trashBar: HorizontalScrollView
    private lateinit var banner: LinearLayout
    private lateinit var colHeader: LinearLayout
    private lateinit var grid: GridView
    private lateinit var homeScroll: ScrollView
    private lateinit var homeBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDetail: View
    private lateinit var statusTiles: View
    private lateinit var backBtn: View
    private lateinit var fwdBtn: View
    private lateinit var upBtn: View
    private val cmdButtons = HashMap<String, View>()
    private val fileAdapter = FmAdapter()

    // ---------- util ----------
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun lp(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

    private fun iconBtn(kind: FmK, onClick: (View) -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = ripple(dpf(20f))
            addView(icon(kind), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { v -> onClick(v) }
        }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun detectVolumes(): List<FmVolume> {
        val out = ArrayList<FmVolume>()
        val internal = Environment.getExternalStorageDirectory()
        out.add(FmVolume("Penyimpanan internal", internal))
        try {
            val dirs = getExternalFilesDirs(null)
            for (d in dirs) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android")
                if (i <= 0) continue
                val root = File(p.substring(0, i))
                if (out.any { it.root.absolutePath == root.absolutePath }) continue
                val n = out.size
                out.add(FmVolume(if (n == 1) "Kartu SD / USB" else "Kartu SD / USB $n", root))
            }
        } catch (e: Exception) {
            // abaikan: hanya penyimpanan internal
        }
        return out
    }

    private fun volumeOf(f: File): FmVolume? {
        val p = f.absolutePath
        return volumes.firstOrNull { p == it.root.absolutePath || p.startsWith(it.root.absolutePath + "/") }
    }

    private fun quickFolders(): List<Triple<String, FmK, File>> {
        val root = volumes[0].root
        val defs = listOf(
            Triple("Unduhan", FmK.DOWNLOAD, "Download"),
            Triple("Dokumen", FmK.DOC, "Documents"),
            Triple("Gambar", FmK.IMAGE, "Pictures"),
            Triple("Musik", FmK.MUSIC, "Music"),
            Triple("Video", FmK.VIDEO, "Movies"),
            Triple("Kamera", FmK.PHOTO, "DCIM"),
            Triple("Screenshot", FmK.PHOTO, "Pictures/Screenshots")
        )
        val out = ArrayList<Triple<String, FmK, File>>()
        for (d in defs) {
            var f = File(root, d.third)
            if (!f.isDirectory && d.first == "Screenshot") f = File(root, "DCIM/Screenshots")
            if (f.isDirectory) out.add(Triple(d.first, d.second, f))
        }
        return out
    }

    private fun readRecent(): List<String> =
        (prefs.getString("fm_recent", "") ?: "").split("\n").filter { it.isNotEmpty() }

    private fun addRecent(f: File) {
        val l = readRecent().toMutableList()
        l.remove(f.absolutePath)
        l.add(0, f.absolutePath)
        while (l.size > 20) l.removeAt(l.size - 1)
        prefs.edit().putString("fm_recent", l.joinToString("\n")).apply()
    }

    private fun readTrash(): List<FmTrash> {
        val raw = prefs.getString("fm_trash", "") ?: ""
        val out = ArrayList<FmTrash>()
        for (line in raw.split("\n")) {
            val p = line.split("\t")
            if (p.size >= 3) out.add(FmTrash(p[0], p[1], p[2].toLongOrNull() ?: 0L))
        }
        return out
    }

    private fun writeTrash(list: List<FmTrash>) {
        val s = list.joinToString("\n") { it.binPath.replace('\t', ' ') + "\t" + it.orig.replace('\t', ' ') + "\t" + it.time }
        prefs.edit().putString("fm_trash", s).apply()
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        Th.load(this)
        setTheme(if (Th.dark) R.style.AppDark else R.style.AppLight)
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Th.PREFS, MODE_PRIVATE)
        seenRev = prefs.getInt("rev", 0)
        tabletUi = resources.configuration.smallestScreenWidthDp >= 600
        viewMode = prefs.getInt("fm_view", 0).coerceIn(0, 3)
        sortKey = prefs.getInt("fm_sort", 0).coerceIn(0, 3)
        sortDesc = prefs.getBoolean("fm_desc", false)
        foldersFirst = prefs.getBoolean("fm_folders_first", true)
        showHidden = prefs.getBoolean("fm_hidden", false)
        showExt = prefs.getBoolean("fm_ext", true)
        volumes = detectVolumes()
        accessOk = hasAccess()
        buildUi()

        val wanted = savedInstanceState?.getString("loc") ?: intent?.getStringExtra(EXTRA_PATH) ?: "home"
        val first = if (wanted == "home" || wanted == "trash") {
            wanted
        } else if (File(wanted).isDirectory) {
            File(wanted).absolutePath
        } else {
            "home"
        }
        showLocation(first)
        refreshAccessUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("loc", loc)
    }

    override fun onResume() {
        super.onResume()
        val rev = prefs.getInt("rev", 0)
        if (rev != seenRev) {
            recreate()
            return
        }
        val now = hasAccess()
        if (now != accessOk) {
            accessOk = now
            refreshAccessUi()
            loadLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchGen.incrementAndGet()
        loadGen.incrementAndGet()
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        thumbPool.shutdownNow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            accessOk = hasAccess()
            refreshAccessUi()
            loadLocation()
        }
    }

    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            pathInput.visibility == View.VISIBLE -> endPathEdit()
            selectActive -> clearSelection()
            searching || searchInput.text.isNotEmpty() -> stopSearch()
            backStack.isNotEmpty() -> goBack()
            loc != "home" -> goUp()
            else -> finish()
        }
    }

    // ---------- izin ----------
    private fun refreshAccessUi() {
        banner.visibility = if (accessOk) View.GONE else View.VISIBLE
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    toast("Tidak dapat membuka pengaturan izin")
                }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_PERM
            )
        }
    }

    // ---------- konstruksi UI ----------
    private fun buildUi() {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(Th.p.bg) }
        edgeToEdge(rootFrame)

        buildNavPane()
        val main = buildMain()

        if (tabletUi) {
            val body = ll(false)
            body.addView(navPane, LinearLayout.LayoutParams(dp(248), MATCH))
            body.addView(main, lp(0, MATCH, 1f, 0, dp(8), dp(8), dp(8)))
            rootFrame.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
            scrimView = View(this)
        } else {
            rootFrame.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
            scrimView = View(this).apply {
                setBackgroundColor(0x66000000)
                visibility = View.GONE
                setOnClickListener { closeDrawer() }
            }
            rootFrame.addView(scrimView, FrameLayout.LayoutParams(MATCH, MATCH))
            navPane.visibility = View.GONE
            rootFrame.addView(navPane, FrameLayout.LayoutParams(dp(300), MATCH, Gravity.START))
        }
        setContentView(rootFrame)
        applyBarIcons()
    }

    private fun buildNavPane() {
        navPane = ll(true).apply {
            if (tabletUi) {
                setBackgroundColor(Th.p.bg)
            } else {
                background = rounded(Th.p.card, 0f, Th.p.stroke, dp(1))
                elevation = dpf(16f)
            }
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        val head = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), dp(4), dp(8), dp(12)) }
        head.addView(icon(FmK.FOLDER), LinearLayout.LayoutParams(dp(26), dp(26)))
        head.addView(tv("File Explorer", 17f, Th.p.text, true).apply { setPadding(dp(12), 0, 0, 0) })
        navPane.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        navList = ll(true)
        navPane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(navList, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun cmdButton(
        id: String, kind: FmK, label: String, showLabel: Boolean,
        tint: Int? = null, onClick: (View) -> Unit
    ): View {
        val b = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = ripple(dpf(6f))
            setPadding(dp(10), 0, dp(10), 0)
            addView(icon(kind, tint), LinearLayout.LayoutParams(dp(18), dp(18)))
            if (showLabel) addView(tv(label, 13f, Th.p.text).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { v -> if (v.alpha >= 0.9f) onClick(v) }
        }
        cmdButtons[id] = b
        return b
    }

    private fun sep(): View = View(this).apply { setBackgroundColor(Th.p.stroke) }

    private fun buildCmdBar(): HorizontalScrollView {
        val row = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), 0, dp(8), 0) }
        val lbl = tabletUi
        fun add(v: View) = row.addView(v, lp(WRAP, dp(40)))
        fun addSep() = row.addView(sep(), lp(dp(1), dp(20), 0f, dp(6), 0, dp(6), 0))
        add(cmdButton("new", FmK.PLUS, "Baru", lbl, Th.accent) { showNewMenu(it) })
        addSep()
        add(cmdButton("cut", FmK.CUT, "Potong", lbl) { doCut() })
        add(cmdButton("copy", FmK.COPY, "Salin", lbl) { doCopy() })
        add(cmdButton("paste", FmK.PASTE, "Tempel", lbl) { doPaste() })
        if (tabletUi) {
            add(cmdButton("rename", FmK.RENAME, "Ganti nama", true) { doRename() })
            add(cmdButton("share", FmK.SHARE, "Bagikan", true) { doShareSel() })
        }
        add(cmdButton("delete", FmK.DELETE, "Hapus", lbl) { doDelete() })
        addSep()
        add(cmdButton("sort", FmK.SORT, "Urutkan", lbl) { showSortMenu(it) })
        add(cmdButton("view", FmK.VIEW_LIST, "Tampilan", lbl) { showViewMenu(it) })
        addSep()
        add(cmdButton("more", FmK.MORE, "", false) { showMoreMenu(it) })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, FrameLayout.LayoutParams(WRAP, dp(44)))
        }
    }

    private fun buildTrashBar(): HorizontalScrollView {
        val row = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), 0, dp(8), 0) }
        val lbl = tabletUi
        row.addView(cmdButton("t_restore", FmK.UPDATE, "Pulihkan", lbl, Th.accent) { doRestore(selectedItems()) }, lp(WRAP, dp(40)))
        row.addView(cmdButton("t_delete", FmK.DELETE, "Hapus permanen", lbl) { doDeleteForever(selectedItems()) }, lp(WRAP, dp(40)))
        row.addView(cmdButton("t_empty", FmK.CLOSE, "Kosongkan Keranjang", lbl) { doEmptyTrash() }, lp(WRAP, dp(40)))
        row.addView(cmdButton("t_all", FmK.SELECT_ALL, "Pilih semua", lbl) { selectAll() }, lp(WRAP, dp(40)))
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, FrameLayout.LayoutParams(WRAP, dp(44)))
        }
    }

    private fun buildMain(): LinearLayout {
        val main = ll(true).apply {
            if (tabletUi) background = rounded(Th.p.card, dpf(10f), Th.p.stroke, dp(1))
        }

        // --- bilah alamat ---
        backBtn = iconBtn(FmK.BACK) { goBack() }
        fwdBtn = iconBtn(FmK.FORWARD) { goForward() }
        upBtn = iconBtn(FmK.UP) { goUp() }

        crumbBox = ll(false, Gravity.CENTER_VERTICAL)
        crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(crumbBox, FrameLayout.LayoutParams(WRAP, MATCH))
            setOnLongClickListener {
                startPathEdit()
                true
            }
        }
        pathInput = EditText(this).apply {
            setTextColor(Th.p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    goToTypedPath()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, has -> if (!has) endPathEdit() }
        }
        val addrStack = FrameLayout(this)
        addrStack.addView(crumbScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addrStack.addView(pathInput, FrameLayout.LayoutParams(MATCH, MATCH))

        val addrBox = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(4), 0, dp(2), 0)
        }
        addrBox.addView(addrStack, LinearLayout.LayoutParams(0, MATCH, 1f))
        addrBox.addView(iconBtn(FmK.RENAME) { startPathEdit() }, LinearLayout.LayoutParams(dp(32), dp(32)))

        searchInput = EditText(this).apply {
            hint = "Cari"
            setHintTextColor(Th.p.faint)
            setTextColor(Th.p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), 0, dp(8), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressSearch) return
                    ui.removeCallbacks(searchRunnable)
                    ui.postDelayed(searchRunnable, 350)
                }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    ui.removeCallbacks(searchRunnable)
                    startSearch(text.toString().trim())
                    true
                } else {
                    false
                }
            }
        }
        searchBox = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(12), 0, dp(8), 0)
            addView(icon(FmK.SEARCH, Th.p.dim), LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(searchInput, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        addrRow = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        if (tabletUi) {
            addrRow.addView(backBtn, lp(dp(38), dp(38)))
            addrRow.addView(fwdBtn, lp(dp(38), dp(38)))
            addrRow.addView(upBtn, lp(dp(38), dp(38)))
            addrRow.addView(iconBtn(FmK.UPDATE) { refresh() }, lp(dp(38), dp(38)))
            addrRow.addView(addrBox, lp(0, dp(38), 1f, dp(6), 0, dp(8), 0))
            addrRow.addView(searchBox, lp(dp(240), dp(38)))
        } else {
            addrRow.addView(iconBtn(FmK.MENU) { openDrawer() }, lp(dp(40), dp(40)))
            addrRow.addView(backBtn, lp(dp(40), dp(40)))
            addrRow.addView(upBtn, lp(dp(40), dp(40)))
            addrRow.addView(addrBox, lp(0, dp(38), 1f, dp(4), 0, dp(4), 0))
            addrRow.addView(iconBtn(FmK.SEARCH) { toggleSearchRow() }, lp(dp(40), dp(40)))
        }

        // --- bilah seleksi ---
        selTitle = tv("", 16f, Th.p.text, true)
        selRow = ll(false, Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
            addView(iconBtn(FmK.CLOSE) { clearSelection() }, lp(dp(40), dp(40)))
            addView(selTitle, lp(0, WRAP, 1f, dp(8), 0, 0, 0))
            addView(iconBtn(FmK.SELECT_ALL) { selectAll() }, lp(dp(40), dp(40)))
            addView(iconBtn(FmK.MORE) { showMoreMenu(it) }, lp(dp(40), dp(40)))
        }
        val topFrame = FrameLayout(this)
        topFrame.addView(addrRow, FrameLayout.LayoutParams(MATCH, WRAP))
        topFrame.addView(selRow, FrameLayout.LayoutParams(MATCH, WRAP))
        main.addView(topFrame, LinearLayout.LayoutParams(MATCH, WRAP))

        if (!tabletUi) {
            val row = ll(false, Gravity.CENTER_VERTICAL).apply {
                setPadding(dp(12), 0, dp(12), dp(6))
                visibility = View.GONE
            }
            row.addView(searchBox, LinearLayout.LayoutParams(MATCH, dp(38)))
            searchRow = row
            main.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // --- command bar ---
        cmdBar = buildCmdBar()
        trashBar = buildTrashBar()
        main.addView(cmdBar, LinearLayout.LayoutParams(MATCH, dp(44)))
        main.addView(trashBar, LinearLayout.LayoutParams(MATCH, dp(44)))

        // --- banner izin ---
        banner = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.withAlpha(Th.accent, 0x22), dpf(8f), Th.withAlpha(Th.accent, 0x66), dp(1))
            setPadding(dp(12), dp(8), dp(8), dp(8))
            visibility = View.GONE
            addView(icon(FmK.INFO, Th.accent), LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(
                tv("Izinkan akses ke semua berkas agar File Explorer dapat menampilkan isi penyimpanan.", 13f, Th.p.text),
                lp(0, WRAP, 1f, dp(10), 0, dp(6), 0)
            )
            addView(textButton("Berikan izin", true) { requestAccess() })
        }
        main.addView(banner, lp(MATCH, WRAP, 0f, dp(12), dp(2), dp(12), dp(6)))

        // --- header kolom (tablet, mode Detail) ---
        colHeader = ll(false, Gravity.CENTER_VERTICAL).apply {
            visibility = View.GONE
            setPadding(dp(20), 0, dp(20), 0)
        }
        main.addView(colHeader, LinearLayout.LayoutParams(MATCH, dp(30)))
        main.addView(View(this).apply { setBackgroundColor(Th.p.stroke) }, LinearLayout.LayoutParams(MATCH, 1))

        // --- isi ---
        grid = GridView(this).apply {
            numColumns = 1
            selector = ColorDrawable(0)
            verticalSpacing = dp(2)
            horizontalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(8))
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = fileAdapter
            setOnItemClickListener { _, _, pos, _ -> onItemTap(pos) }
            setOnItemLongClickListener { _, v, pos, _ ->
                onItemLong(pos, v)
                true
            }
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, oldR, _ ->
                if (r - l != oldR - ol) updateColumns()
            }
        }
        homeBox = ll(true).apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        homeScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            addView(homeBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        emptyView = tv("", 14f, Th.p.dim).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val content = FrameLayout(this)
        content.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(homeScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(emptyView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        main.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // --- bilah status ---
        statusText = tv("", 12f, Th.p.dim)
        statusDetail = iconBtn(FmK.VIEW_LIST) { setViewMode(0) }
        statusTiles = iconBtn(FmK.VIEW_GRID) { setViewMode(3) }
        val status = ll(false, Gravity.CENTER_VERTICAL).apply { setPadding(dp(16), 0, dp(8), 0) }
        status.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))
        status.addView(statusDetail, LinearLayout.LayoutParams(dp(30), dp(30)))
        status.addView(statusTiles, LinearLayout.LayoutParams(dp(30), dp(30)))
        main.addView(View(this).apply { setBackgroundColor(Th.p.stroke) }, LinearLayout.LayoutParams(MATCH, 1))
        main.addView(status, LinearLayout.LayoutParams(MATCH, dp(34)))
        return main
    }

    // ---------- navigasi ----------
    private fun showLocation(target: String) {
        loc = target
        stopSearchSilently()
        selected.clear()
        selectActive = false
        endPathEdit()
        renderNav()
        renderCrumbs()
        updateNavButtons()
        updateTopBars()
        updateBars()
        closeDrawer()
        loadLocation()
    }

    private fun navigate(target: String) {
        if (target == loc && !searching) {
            loadLocation()
            return
        }
        backStack.add(loc)
        fwdStack.clear()
        showLocation(target)
    }

    private fun goBack() {
        if (backStack.isEmpty()) return
        fwdStack.add(loc)
        showLocation(backStack.removeAt(backStack.size - 1))
    }

    private fun goForward() {
        if (fwdStack.isEmpty()) return
        backStack.add(loc)
        showLocation(fwdStack.removeAt(fwdStack.size - 1))
    }

    private fun goUp() {
        if (loc == "home") return
        if (loc == "trash") {
            navigate("home")
            return
        }
        val f = File(loc)
        val vol = volumeOf(f)
        if (vol != null && f.absolutePath == vol.root.absolutePath) {
            navigate("home")
        } else {
            navigate(f.parentFile?.absolutePath ?: "home")
        }
    }

    private fun refresh() {
        if (searching) startSearch(searchQuery) else loadLocation()
    }

    private fun updateNavButtons() {
        backBtn.alpha = if (backStack.isNotEmpty()) 1f else 0.4f
        fwdBtn.alpha = if (fwdStack.isNotEmpty()) 1f else 0.4f
        upBtn.alpha = if (loc != "home") 1f else 0.4f
    }

    private fun updateBars() {
        cmdBar.visibility = if (loc == "home" || loc == "trash") View.GONE else View.VISIBLE
        trashBar.visibility = if (loc == "trash") View.VISIBLE else View.GONE
        updateCommandState()
    }

    private fun openDrawer() {
        if (tabletUi) return
        drawerOpen = true
        scrimView.visibility = View.VISIBLE
        navPane.visibility = View.VISIBLE
        navPane.translationX = -dp(300).toFloat()
        navPane.animate().translationX(0f).setDuration(if (Th.animations) 180L else 0L).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        scrimView.visibility = View.GONE
        navPane.animate().translationX(-dp(300).toFloat()).setDuration(if (Th.animations) 160L else 0L)
            .withEndAction { navPane.visibility = View.GONE }.start()
    }

    // ---------- panel navigasi ----------
    private fun navActive(target: String, isVol: Boolean): Boolean {
        if (!isVol) return loc == target
        if (quickFolders().any { it.third.absolutePath == loc }) return false
        return loc == target || loc.startsWith("$target/")
    }

    private fun navHeader(text: String): View =
        tv(text, 12f, Th.p.faint, true).apply { setPadding(dp(14), dp(16), dp(8), dp(6)) }

    private fun volumeUsage(v: FmVolume): Pair<Long, Long> {
        return try {
            val st = StatFs(v.root.absolutePath)
            Pair(st.blockCountLong * st.blockSizeLong, st.availableBlocksLong * st.blockSizeLong)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    private fun navItem(kind: FmK, tint: Int?, label: String, target: String, vol: FmVolume?): View {
        val active = navActive(target, vol != null)
        val item = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = if (active) rounded(Th.p.cardHover, dpf(6f)) else ripple(dpf(6f))
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { navigate(target) }
        }
        item.addView(
            View(this).apply { background = if (active) rounded(Th.accent, dpf(2f)) else null },
            lp(dp(3), dp(16), 0f, 0, 0, dp(10), 0)
        )
        item.addView(icon(kind, tint), LinearLayout.LayoutParams(dp(20), dp(20)))
        val col = ll(true)
        col.addView(tv(label, 14f, Th.p.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (vol != null) {
            val (total, free) = volumeUsage(vol)
            if (total > 0L) {
                val used = (((total - free) * 1000L) / total).toInt().coerceIn(0, 1000)
                val track = ll(false).apply {
                    background = rounded(Th.p.cardHover, dpf(2f))
                    weightSum = 1000f
                }
                track.addView(
                    View(this).apply { background = rounded(if (used > 900) Th.danger else Th.accent, dpf(2f)) },
                    LinearLayout.LayoutParams(0, MATCH, used.toFloat())
                )
                col.addView(track, lp(MATCH, dp(4), 0f, 0, dp(4), 0, 0))
                col.addView(tv("${fmtSize(free)} bebas dari ${fmtSize(total)}", 11f, Th.p.dim))
            }
        }
        item.addView(col, lp(0, WRAP, 1f, dp(12), 0, 0, 0))
        item.minimumHeight = dp(40)
        return item
    }

    private fun renderNav() {
        navList.removeAllViews()
        fun add(v: View) = navList.addView(v, lp(MATCH, WRAP, 0f, 0, dp(2), 0, 0))
        add(navItem(FmK.HOME, Th.accent, "Beranda", "home", null))
        val quick = quickFolders()
        if (quick.isNotEmpty()) {
            navList.addView(navHeader("Akses cepat"))
            for ((name, kind, file) in quick) {
                add(navItem(kind, if (kind == FmK.DOWNLOAD) Th.accent else null, name, file.absolutePath, null))
            }
        }
        navList.addView(navHeader("Perangkat ini"))
        for (v in volumes) add(navItem(FmK.DRIVE, null, v.label, v.root.absolutePath, null.let { v }))
        navList.addView(navHeader("Lainnya"))
        add(navItem(FmK.DELETE, null, "Keranjang Sampah", "trash", null))
    }

    // ---------- breadcrumb ----------
    private fun renderCrumbs() {
        crumbBox.removeAllViews()
        val parts = ArrayList<Pair<String, String>>()
        when {
            loc == "home" -> parts.add(Pair("Beranda", "home"))
            loc == "trash" -> parts.add(Pair("Keranjang Sampah", "trash"))
            else -> {
                val vol = volumeOf(File(loc))
                var cur: String
                val rel: String
                if (vol != null) {
                    parts.add(Pair(vol.label, vol.root.absolutePath))
                    cur = vol.root.absolutePath
                    rel = loc.removePrefix(vol.root.absolutePath).trim('/')
                } else {
                    parts.add(Pair("/", "/"))
                    cur = ""
                    rel = loc.trim('/')
                }
                if (rel.isNotEmpty()) {
                    for (seg in rel.split("/")) {
                        cur = "$cur/$seg"
                        parts.add(Pair(seg, cur))
                    }
                }
            }
        }
        if (searching && searchQuery.isNotEmpty()) parts.add(Pair("Hasil: $searchQuery", ""))
        for ((i, part) in parts.withIndex()) {
            if (i > 0) crumbBox.addView(icon(FmK.CHEV_RIGHT, Th.p.faint), lp(dp(12), dp(12), 0f, dp(2), 0, dp(2), 0))
            val seg = tv(part.first, 14f, if (i == parts.size - 1) Th.p.text else Th.p.dim, i == parts.size - 1).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                maxLines = 1
                if (part.second.isNotEmpty()) {
                    background = ripple(dpf(4f))
                    setOnClickListener { navigate(part.second) }
                }
                setOnLongClickListener {
                    startPathEdit()
                    true
                }
            }
            crumbBox.addView(seg, lp(WRAP, WRAP))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun startPathEdit() {
        pathInput.setText(if (loc == "home" || loc == "trash") volumes[0].root.absolutePath else loc)
        pathInput.setSelection(pathInput.text.length)
        crumbScroll.visibility = View.GONE
        pathInput.visibility = View.VISIBLE
        pathInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(pathInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun endPathEdit() {
        if (pathInput.visibility != View.VISIBLE) return
        pathInput.visibility = View.GONE
        crumbScroll.visibility = View.VISIBLE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(pathInput.windowToken, 0)
    }

    private fun goToTypedPath() {
        val f = File(pathInput.text.toString().trim())
        if (f.isDirectory) {
            endPathEdit()
            navigate(f.absolutePath)
        } else if (f.isFile) {
            endPathEdit()
            openFile(f, false)
        } else {
            toast("Jalur tidak ditemukan")
        }
    }

    // ---------- memuat daftar ----------
    private fun loadLocation() {
        val gen = loadGen.incrementAndGet()
        listFailed = false
        when {
            loc == "home" -> showHome()
            loc == "trash" -> {
                pool.execute {
                    val list = ArrayList<FmItem>()
                    for (t in readTrash()) {
                        val f = File(t.binPath)
                        if (!f.exists()) continue
                        val size = if (f.isDirectory) 0L else f.length()
                        list.add(FmItem(f, File(t.orig).name, f.isDirectory, size, t.time, t.orig))
                    }
                    sortList(list)
                    runOnUiThread {
                        if (!isFinishing && gen == loadGen.get()) {
                            items = list
                            applyItems()
                        }
                    }
                }
            }
            else -> {
                val dir = File(loc)
                pool.execute {
                    val raw = try {
                        dir.listFiles()
                    } catch (e: SecurityException) {
                        null
                    }
                    val list = ArrayList<FmItem>()
                    if (raw != null) {
                        for (f in raw) {
                            if (!showHidden && f.name.startsWith(".")) continue
                            val isDir = f.isDirectory
                            list.add(FmItem(f, f.name, isDir, if (isDir) 0L else f.length(), f.lastModified()))
                        }
                    }
                    sortList(list)
                    runOnUiThread {
                        if (!isFinishing && gen == loadGen.get() && !searching) {
                            listFailed = raw == null
                            items = list
                            applyItems()
                            grid.setSelection(0)
                        }
                    }
                }
            }
        }
    }

    private fun sortList(list: MutableList<FmItem>) {
        val key = sortKey
        val desc = sortDesc
        val first = foldersFirst
        java.util.Collections.sort(list, Comparator<FmItem> { a, b ->
            if (first && a.isDir != b.isDir) {
                if (a.isDir) -1 else 1
            } else {
                var r = when (key) {
                    1 -> a.mod.compareTo(b.mod)
                    2 -> a.size.compareTo(b.size)
                    3 -> FmUtil.typeKey(a).compareTo(FmUtil.typeKey(b))
                    else -> a.name.compareTo(b.name, ignoreCase = true)
                }
                if (r == 0) r = a.name.compareTo(b.name, ignoreCase = true)
                if (desc) -r else r
            }
        })
    }

    private fun applyItems() {
        homeScroll.visibility = View.GONE
        grid.visibility = View.VISIBLE
        updateColumns()
        fileAdapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when {
            loc == "trash" -> "Keranjang Sampah kosong"
            searching && !searchDone -> "Mencari…"
            searching -> "Tidak ada hasil untuk \"$searchQuery\""
            listFailed -> "Tidak dapat membuka folder ini.\nPeriksa izin akses berkas."
            else -> "Folder ini kosong"
        }
        updateDetailHeader()
        updateStatus()
        updateCommandState()
    }

    private fun showHome() {
        items = emptyList()
        grid.visibility = View.GONE
        emptyView.visibility = View.GONE
        homeScroll.visibility = View.VISIBLE
        renderHome()
        updateDetailHeader()
        updateStatus()
        updateCommandState()
    }

    private fun updateColumns() {
        val avail = if (grid.width > 0) grid.width else resources.displayMetrics.widthPixels - (if (tabletUi) dp(260) else 0)
        val cols = when (viewMode) {
            2 -> max(2, avail / dp(112))
            3 -> max(2, avail / dp(160))
            else -> 1
        }
        if (grid.numColumns != cols) grid.numColumns = cols
    }

    private fun setViewMode(m: Int) {
        viewMode = m
        prefs.edit().putInt("fm_view", m).apply()
        updateColumns()
        fileAdapter.notifyDataSetChanged()
        updateDetailHeader()
        updateStatus()
    }

    private fun updateDetailHeader() {
        val show = tabletUi && viewMode == 0 && loc != "home"
        colHeader.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        colHeader.removeAllViews()
        val arrow = if (sortDesc) " ▼" else " ▲"
        fun head(label: String, key: Int, w: Int, weight: Float, gravity: Int): View =
            tv(label + (if (sortKey == key) arrow else ""), 12f, Th.p.dim, true).apply {
                this.gravity = gravity
                background = ripple(dpf(4f))
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { setSort(key) }
            }.also { colHeader.addView(it, LinearLayout.LayoutParams(w, MATCH, weight)) }
        colHeader.addView(View(this), LinearLayout.LayoutParams(dp(64), 1))
        head("Nama", 0, 0, 1f, Gravity.CENTER_VERTICAL)
        head("Tanggal diubah", 1, dp(150), 0f, Gravity.CENTER_VERTICAL)
        head("Jenis", 3, dp(120), 0f, Gravity.CENTER_VERTICAL)
        head("Ukuran", 2, dp(90), 0f, Gravity.CENTER_VERTICAL or Gravity.END)
    }

    private fun setSort(key: Int) {
        if (sortKey == key) sortDesc = !sortDesc else {
            sortKey = key
            sortDesc = false
        }
        saveSort()
    }

    private fun saveSort() {
        prefs.edit().putInt("fm_sort", sortKey).putBoolean("fm_desc", sortDesc)
            .putBoolean("fm_folders_first", foldersFirst).apply()
        val list = items.toMutableList()
        sortList(list)
        items = list
        fileAdapter.notifyDataSetChanged()
        updateDetailHeader()
    }

    // ---------- adapter ----------
    private inner class FmAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 4
        override fun getItemViewType(position: Int): Int = viewMode

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h: FmHolder
            val v: View
            if (convertView == null) {
                h = makeHolder(viewMode)
                v = h.root
                v.tag = h
            } else {
                v = convertView
                h = convertView.tag as FmHolder
            }
            bind(h, items[position])
            return v
        }
    }

    private fun makeHolder(mode: Int): FmHolder {
        val tile = mode >= 2
        val root = ll(!tile, if (tile) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL)
        val h = FmHolder(root)

        val chkIcon = icon(FmK.CHECK, Th.onAccent)
        chkIcon.visibility = View.GONE
        val chk = FrameLayout(this)
        chk.addView(chkIcon, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        h.check = chk
        h.checkIcon = chkIcon

        val isz = when (mode) {
            0 -> if (tabletUi) 22 else 28
            1 -> 24
            2 -> 52
            else -> 88
        }
        val glyph = icon(FmK.FILE)
        h.icon = glyph
        val iconBox = FrameLayout(this)
        iconBox.addView(glyph, FrameLayout.LayoutParams(dp(isz), dp(isz), Gravity.CENTER))

        if (tile) {
            val th = ImageView(this)
            th.scaleType = ImageView.ScaleType.CENTER_CROP
            th.background = rounded(Th.p.cardHover, dpf(6f))
            th.clipToOutline = true
            th.visibility = View.GONE
            iconBox.addView(th, FrameLayout.LayoutParams(dp(isz), dp(isz), Gravity.CENTER))
            h.thumb = th
            iconBox.addView(chk, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START))
            root.setPadding(dp(6), dp(8), dp(6), dp(8))
            root.addView(iconBox, LinearLayout.LayoutParams(dp(isz + 8), dp(isz + 8)))
            val nm = tv("", 12f, Th.p.text).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            root.addView(nm, LinearLayout.LayoutParams(MATCH, WRAP))
            h.name = nm
            root.layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
        } else {
            root.setPadding(dp(12), 0, dp(12), 0)
            root.addView(chk, lp(dp(20), dp(20), 0f, 0, 0, dp(10), 0))
            root.addView(iconBox, lp(dp(isz + 4), dp(isz + 4), 0f, 0, 0, dp(12), 0))
            val nm = tv("", 14f, Th.p.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            h.name = nm
            if (mode == 0 && tabletUi) {
                root.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
                val date = tv("", 13f, Th.p.dim).apply { maxLines = 1 }
                val type = tv("", 13f, Th.p.dim).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val size = tv("", 13f, Th.p.dim).apply {
                    maxLines = 1
                    gravity = Gravity.END
                }
                root.addView(date, LinearLayout.LayoutParams(dp(150), WRAP))
                root.addView(type, LinearLayout.LayoutParams(dp(120), WRAP))
                root.addView(size, LinearLayout.LayoutParams(dp(90), WRAP))
                h.date = date
                h.type = type
                h.size = size
                root.layoutParams = AbsListView.LayoutParams(MATCH, dp(40))
            } else if (mode == 0) {
                val col = ll(true)
                col.addView(nm)
                val sb = tv("", 12f, Th.p.dim).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
                col.addView(sb)
                h.sub = sb
                root.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                root.layoutParams = AbsListView.LayoutParams(MATCH, dp(58))
            } else {
                root.addView(nm, LinearLayout.LayoutParams(0, WRAP, 1f))
                root.layoutParams = AbsListView.LayoutParams(MATCH, dp(44))
            }
        }
        return h
    }

    private fun bind(h: FmHolder, item: FmItem) {
        val path = item.file.absolutePath
        h.tag = path
        h.icon?.let {
            it.kind = FmUtil.kindFor(item.isDir, item.name)
            it.tint = null
            it.invalidate()
        }
        val shown = if (!showExt && !item.isDir) FmUtil.baseName(item.name) else item.name
        h.name?.text = shown
        val dateStr = fmtDate(item.mod)
        val sizeStr = if (item.isDir) "" else fmtSize(item.size)
        h.date?.text = dateStr
        h.type?.text = FmUtil.typeLabel(item.isDir, item.name)
        h.size?.text = sizeStr
        h.sub?.text = item.sub ?: (if (sizeStr.isEmpty()) dateStr else "$dateStr  ·  $sizeStr")

        val on = selected.contains(path)
        h.root.background = if (on) rounded(Th.withAlpha(Th.accent, 0x33), dpf(6f)) else ripple(dpf(6f))
        val chk = h.check
        if (chk != null) {
            chk.visibility = if (selectActive) View.VISIBLE else (if (viewMode >= 2) View.GONE else View.INVISIBLE)
            chk.background = if (on) rounded(Th.accent, dpf(4f)) else rounded(Th.p.card, dpf(4f), Th.p.dim, dp(2))
        }
        h.checkIcon?.visibility = if (on) View.VISIBLE else View.GONE

        val th = h.thumb
        if (th != null) {
            th.visibility = View.GONE
            val cat = if (item.isDir) 0 else FmUtil.category(item.name)
            if (cat == 1 || cat == 2) {
                val px = dp(if (viewMode == 2) 52 else 88)
                val key = "$path|${item.mod}|$px"
                val bm = thumbCache.get(key)
                if (bm != null) {
                    th.setImageBitmap(bm)
                    th.visibility = View.VISIBLE
                } else {
                    loadThumb(h, item.file, cat, px, key)
                }
            }
        }
    }

    private fun loadThumb(h: FmHolder, f: File, cat: Int, px: Int, key: String) {
        val path = f.absolutePath
        try {
            thumbPool.execute {
                val bm = try {
                    if (cat == 1) decodeSampled(path, px) else videoThumb(path)
                } catch (e: Throwable) {
                    null
                }
                if (bm != null) {
                    thumbCache.put(key, bm)
                    runOnUiThread {
                        if (!isFinishing && h.tag == path) {
                            h.thumb?.setImageBitmap(bm)
                            h.thumb?.visibility = View.VISIBLE
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // pool sudah ditutup
        }
    }

    private fun decodeSampled(path: String, target: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeFile(path, o2)
    }

    @Suppress("DEPRECATION")
    private fun videoThumb(path: String): Bitmap? =
        ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)

    // ---------- seleksi ----------
    private fun selectedItems(): List<FmItem> = items.filter { selected.contains(it.file.absolutePath) }

    private fun onItemTap(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        val item = items[pos]
        if (selectActive || loc == "trash") {
            toggleSelect(item)
        } else {
            openItem(item)
        }
    }

    private fun onItemLong(pos: Int, anchor: View) {
        if (pos < 0 || pos >= items.size) return
        val item = items[pos]
        selectActive = true
        selected.add(item.file.absolutePath)
        onSelectionChanged()
        showItemMenu(anchor, item)
    }

    private fun toggleSelect(item: FmItem) {
        val p = item.file.absolutePath
        if (!selected.remove(p)) selected.add(p)
        selectActive = selected.isNotEmpty()
        onSelectionChanged()
    }

    private fun selectAll() {
        selected.clear()
        for (i in items) selected.add(i.file.absolutePath)
        selectActive = selected.isNotEmpty()
        onSelectionChanged()
    }

    private fun invertSelection() {
        val next = LinkedHashSet<String>()
        for (i in items) {
            val p = i.file.absolutePath
            if (!selected.contains(p)) next.add(p)
        }
        selected.clear()
        selected.addAll(next)
        selectActive = selected.isNotEmpty()
        onSelectionChanged()
    }

    private fun clearSelection() {
        selected.clear()
        selectActive = false
        onSelectionChanged()
    }

    private fun onSelectionChanged() {
        fileAdapter.notifyDataSetChanged()
        updateTopBars()
        updateCommandState()
        updateStatus()
    }

    private fun updateTopBars() {
        addrRow.visibility = if (selectActive) View.GONE else View.VISIBLE
        selRow.visibility = if (selectActive) View.VISIBLE else View.GONE
        selTitle.text = "${selected.size} dipilih"
    }

    private fun enable(id: String, on: Boolean) {
        cmdButtons[id]?.alpha = if (on) 1f else 0.4f
    }

    private fun updateCommandState() {
        if (!::cmdBar.isInitialized) return
        val realFolder = loc != "home" && loc != "trash" && !searching
        val n = selected.size
        enable("new", realFolder)
        enable("cut", n > 0)
        enable("copy", n > 0)
        enable("paste", realFolder && clipFiles.isNotEmpty())
        enable("rename", n == 1)
        enable("share", n > 0 && selectedItems().none { it.isDir })
        enable("delete", n > 0)
        enable("sort", true)
        enable("view", true)
        enable("more", true)
        enable("t_restore", n > 0)
        enable("t_delete", n > 0)
        enable("t_empty", items.isNotEmpty())
        enable("t_all", items.isNotEmpty())
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        var t = when {
            loc == "home" -> ""
            searching -> "${items.size} hasil" + (if (!searchDone) " (mencari…)" else "")
            else -> "${items.size} item"
        }
        if (selected.isNotEmpty()) {
            t += "   |   ${selected.size} dipilih"
            val sel = selectedItems()
            if (sel.isNotEmpty() && sel.none { it.isDir }) t += "  ${fmtSize(sel.sumOf { it.size })}"
        }
        statusText.text = t
        val show = loc != "home"
        statusDetail.visibility = if (show) View.VISIBLE else View.GONE
        statusTiles.visibility = if (show) View.VISIBLE else View.GONE
        statusDetail.background = if (viewMode < 2) rounded(Th.p.cardHover, dpf(15f)) else ripple(dpf(15f))
        statusTiles.background = if (viewMode >= 2) rounded(Th.p.cardHover, dpf(15f)) else ripple(dpf(15f))
    }

    // ---------- membuka ----------
    private fun openItem(item: FmItem) {
        if (item.isDir) navigate(item.file.absolutePath) else openFile(item.file, false)
    }

    private fun openFile(f: File, chooser: Boolean) {
        addRecent(f)
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, FmUtil.mime(f))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (chooser) {
                val c = Intent.createChooser(i, "Buka dengan")
                c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(c)
            } else {
                startActivity(i)
            }
        } catch (e: ActivityNotFoundException) {
            toast("Tidak ada aplikasi untuk membuka berkas ini")
        } catch (e: Exception) {
            toast("Tidak dapat membuka berkas")
        }
    }

    // ---------- menu ----------
    private fun menu(anchor: View): PopupMenu = PopupMenu(Th.themed(this), anchor)

    private fun showNewMenu(anchor: View) {
        val m = menu(anchor)
        m.menu.add(0, 1, 0, "Folder")
        m.menu.add(0, 2, 0, "Berkas teks")
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> newFolder()
                2 -> newTextFile()
            }
            true
        }
        m.show()
    }

    private fun showSortMenu(anchor: View) {
        val m = menu(anchor)
        val names = listOf("Nama", "Tanggal diubah", "Ukuran", "Jenis")
        for ((i, n) in names.withIndex()) m.menu.add(1, 100 + i, 0, n).isChecked = i == sortKey
        m.menu.setGroupCheckable(1, true, true)
        m.menu.add(2, 200, 0, "Naik").isChecked = !sortDesc
        m.menu.add(2, 201, 0, "Turun").isChecked = sortDesc
        m.menu.setGroupCheckable(2, true, true)
        val ff = m.menu.add(3, 300, 0, "Folder lebih dulu")
        ff.isCheckable = true
        ff.isChecked = foldersFirst
        m.setOnMenuItemClickListener { mi ->
            val id = mi.itemId
            when {
                id in 100..103 -> {
                    sortKey = id - 100
                    saveSort()
                }
                id == 200 -> {
                    sortDesc = false
                    saveSort()
                }
                id == 201 -> {
                    sortDesc = true
                    saveSort()
                }
                id == 300 -> {
                    foldersFirst = !foldersFirst
                    saveSort()
                }
            }
            true
        }
        m.show()
    }

    private fun showViewMenu(anchor: View) {
        val m = menu(anchor)
        val names = listOf("Detail", "Daftar", "Ikon sedang", "Ikon besar")
        for ((i, n) in names.withIndex()) m.menu.add(1, 100 + i, 0, n).isChecked = i == viewMode
        m.menu.setGroupCheckable(1, true, true)
        m.setOnMenuItemClickListener { mi ->
            setViewMode(mi.itemId - 100)
            true
        }
        m.show()
    }

    private fun showMoreMenu(anchor: View) {
        val m = menu(anchor)
        val mm = m.menu
        mm.add(0, 1, 0, "Pilih semua")
        mm.add(0, 2, 0, "Balik pilihan")
        if (loc != "trash") {
            if (!tabletUi) {
                mm.add(0, 20, 0, "Ganti nama")
                mm.add(0, 21, 0, "Bagikan")
                mm.add(0, 22, 0, "Maju")
                mm.add(0, 23, 0, "Segarkan")
            }
            val hid = mm.add(1, 3, 0, "Tampilkan berkas tersembunyi")
            hid.isCheckable = true
            hid.isChecked = showHidden
            val ext = mm.add(1, 4, 0, "Tampilkan ekstensi")
            ext.isCheckable = true
            ext.isChecked = showExt
            mm.add(2, 5, 0, "Kompres ke ZIP")
            mm.add(2, 6, 0, "Ekstrak ZIP")
            mm.add(2, 8, 0, "Buka dengan")
            mm.add(2, 7, 0, "Properti")
        }
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> selectAll()
                2 -> invertSelection()
                3 -> {
                    showHidden = !showHidden
                    prefs.edit().putBoolean("fm_hidden", showHidden).apply()
                    refresh()
                }
                4 -> {
                    showExt = !showExt
                    prefs.edit().putBoolean("fm_ext", showExt).apply()
                    fileAdapter.notifyDataSetChanged()
                }
                5 -> doCompress()
                6 -> doExtract()
                7 -> showProps(if (selected.isEmpty()) currentFolderItem() else selectedItems())
                8 -> {
                    val s = selectedItems()
                    if (s.size == 1 && !s[0].isDir) openFile(s[0].file, true) else toast("Pilih satu berkas")
                }
                20 -> doRename()
                21 -> doShareSel()
                22 -> goForward()
                23 -> refresh()
            }
            true
        }
        m.show()
    }

    private fun currentFolderItem(): List<FmItem> {
        if (loc == "home" || loc == "trash") return emptyList()
        val f = File(loc)
        return listOf(FmItem(f, f.name.ifEmpty { f.absolutePath }, true, 0L, f.lastModified(), f.parent))
    }

    private fun showItemMenu(anchor: View, item: FmItem) {
        val m = menu(anchor)
        val mm = m.menu
        val single = selected.size <= 1
        if (loc == "trash") {
            mm.add(0, 10, 0, "Pulihkan")
            mm.add(0, 11, 0, "Hapus permanen")
            mm.add(0, 8, 0, "Properti")
        } else {
            if (single) {
                mm.add(0, 1, 0, "Buka")
                if (!item.isDir) mm.add(0, 2, 0, "Buka dengan")
            }
            mm.add(0, 3, 0, "Potong")
            mm.add(0, 4, 0, "Salin")
            if (single) mm.add(0, 5, 0, "Ganti nama")
            mm.add(0, 6, 0, "Bagikan")
            if (single && !item.isDir && FmUtil.ext(item.name) == "zip") mm.add(0, 9, 0, "Ekstrak di sini")
            mm.add(0, 7, 0, "Hapus")
            mm.add(0, 8, 0, "Properti")
        }
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> {
                    clearSelection()
                    openItem(item)
                }
                2 -> openFile(item.file, true)
                3 -> doCut()
                4 -> doCopy()
                5 -> doRename()
                6 -> doShareSel()
                7 -> doDelete()
                8 -> showProps(selectedItems())
                9 -> doExtract()
                10 -> doRestore(selectedItems())
                11 -> doDeleteForever(selectedItems())
            }
            true
        }
        m.show()
    }

    // ---------- dialog ----------
    private fun promptName(title: String, initial: String, selectLen: Int, okText: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setTextColor(Th.p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(Th.p.input, dpf(8f), Th.p.stroke, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSelection(0, selectLen.coerceIn(0, initial.length))
        }
        showDialog(title, input, okText, "Batal") { onOk(input.text.toString().trim()) }
        input.post {
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun validateName(name: String, dir: File?, old: String?): String? {
        if (name.isEmpty()) return "Nama tidak boleh kosong"
        if (name == "." || name == "..") return "Nama tidak valid"
        if (name.any { it == '/' || it == '\u0000' }) return "Nama tidak boleh mengandung karakter /"
        if (dir != null && name != old && File(dir, name).exists()) return "Nama sudah dipakai"
        return null
    }

    private fun runOp(title: String, work: (FmProgress) -> Unit, after: (Boolean) -> Unit) {
        val pr = FmProgress()
        val bar = ProgressBar(this)
        bar.isIndeterminate = true
        bar.indeterminateTintList = ColorStateList.valueOf(Th.accent)
        val label = tv("Menyiapkan…", 13f, Th.p.dim)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.MIDDLE
        val body = ll(true)
        body.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(label, lp(MATCH, WRAP, 0f, 0, dp(8), 0, 0))
        val dlg = showDialog(title, body, null, "Batal")
        dlg.setCancelable(false)
        dlg.setOnDismissListener { pr.cancelled = true }
        val ticker = object : Runnable {
            override fun run() {
                if (pr.text.isNotEmpty()) label.text = pr.text
                if (dlg.isShowing) ui.postDelayed(this, 150)
            }
        }
        ui.post(ticker)
        pool.execute {
            var ok = true
            var err: String? = null
            try {
                work(pr)
            } catch (e: Exception) {
                ok = false
                err = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (dlg.isShowing) {
                    dlg.setOnDismissListener(null)
                    try {
                        dlg.dismiss()
                    } catch (e: Exception) {
                        // jendela sudah hilang
                    }
                }
                if (!isFinishing) {
                    if (err != null) toast("Gagal: $err") else if (pr.cancelled) toast("Dibatalkan")
                    after(ok)
                }
            }
        }
    }

    private fun reloadAfterOp() {
        selected.clear()
        selectActive = false
        updateTopBars()
        if (searching) startSearch(searchQuery) else loadLocation()
    }

    // ---------- operasi berkas ----------
    private fun doCopy() {
        val sel = selectedItems()
        if (sel.isEmpty()) return
        clipFiles = sel.map { it.file }
        clipCut = false
        toast("${sel.size} item disalin")
        updateCommandState()
    }

    private fun doCut() {
        val sel = selectedItems()
        if (sel.isEmpty()) return
        clipFiles = sel.map { it.file }
        clipCut = true
        toast("${sel.size} item dipotong")
        updateCommandState()
    }

    private fun doPaste() {
        if (loc == "home" || loc == "trash" || searching) return
        val files = clipFiles
        if (files.isEmpty()) return
        val cut = clipCut
        val dir = File(loc)
        runOp(if (cut) "Memindahkan" else "Menyalin", { pr ->
            for (f in files) {
                if (pr.cancelled) break
                pr.text = f.name
                val dp0 = dir.absolutePath
                if (f.isDirectory && (dp0 == f.absolutePath || dp0.startsWith(f.absolutePath + "/"))) {
                    throw IllegalStateException("Folder tujuan berada di dalam folder sumber")
                }
                if (cut && f.parentFile?.absolutePath == dp0) continue
                val target = FmUtil.unique(dir, f.name, f.isDirectory)
                if (cut) {
                    if (!f.renameTo(target)) {
                        FmUtil.copyTree(f, target, pr)
                        if (!pr.cancelled) FmUtil.deleteTree(f)
                    }
                } else {
                    FmUtil.copyTree(f, target, pr)
                }
            }
        }) { ok ->
            if (cut && ok) clipFiles = emptyList()
            reloadAfterOp()
        }
    }

    private fun doRename() {
        val sel = selectedItems()
        if (sel.size != 1) return
        val item = sel[0]
        val parent = item.file.parentFile
        val baseLen = if (item.isDir) item.name.length else FmUtil.baseName(item.name).length
        promptName("Ganti nama", item.name, baseLen, "Ganti nama") { nn ->
            val err = validateName(nn, parent, item.name)
            if (err != null) {
                toast(err)
            } else if (nn != item.name) {
                pool.execute {
                    val ok = item.file.renameTo(File(parent, nn))
                    runOnUiThread {
                        if (!isFinishing) {
                            if (!ok) toast("Tidak dapat mengganti nama")
                            reloadAfterOp()
                        }
                    }
                }
            }
        }
    }

    private fun newFolder() {
        if (loc == "home" || loc == "trash") return
        val dir = File(loc)
        val def = FmUtil.unique(dir, "Folder baru", true).name
        promptName("Folder baru", def, def.length, "Buat") { nn ->
            val err = validateName(nn, dir, null)
            if (err != null) {
                toast(err)
            } else {
                pool.execute {
                    val ok = File(dir, nn).mkdirs()
                    runOnUiThread {
                        if (!isFinishing) {
                            if (!ok) toast("Tidak dapat membuat folder")
                            reloadAfterOp()
                        }
                    }
                }
            }
        }
    }

    private fun newTextFile() {
        if (loc == "home" || loc == "trash") return
        val dir = File(loc)
        val def = FmUtil.unique(dir, "Dokumen baru.txt", false).name
        promptName("Berkas teks baru", def, FmUtil.baseName(def).length, "Buat") { nn ->
            val err = validateName(nn, dir, null)
            if (err != null) {
                toast(err)
            } else {
                pool.execute {
                    val ok = try {
                        File(dir, nn).createNewFile()
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        if (!isFinishing) {
                            if (!ok) toast("Tidak dapat membuat berkas")
                            reloadAfterOp()
                        }
                    }
                }
            }
        }
    }

    private fun doDelete() {
        val sel = selectedItems()
        if (sel.isEmpty()) return
        val files = sel.map { it.file }
        var permanent = false
        val msg = if (sel.size == 1) {
            "Pindahkan \"${sel[0].name}\" ke Keranjang Sampah?"
        } else {
            "Pindahkan ${sel.size} item ke Keranjang Sampah?"
        }
        val body = ll(true)
        body.addView(tv(msg, 14f, Th.p.dim))
        val row = ll(false, Gravity.CENTER_VERTICAL)
        row.addView(tv("Hapus permanen (tanpa Keranjang)", 13f, Th.p.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(themedSwitch(false) { permanent = it })
        body.addView(row, lp(MATCH, WRAP, 0f, 0, dp(12), 0, 0))
        showDialog("Hapus", body, "Hapus", "Batal") {
            val perm = permanent
            runOp(if (perm) "Menghapus" else "Memindahkan ke Keranjang", { pr ->
                for (f in files) {
                    if (pr.cancelled) break
                    pr.text = f.name
                    if (perm) FmUtil.deleteTree(f) else moveToTrash(f, pr)
                }
            }) { reloadAfterOp() }
        }
    }

    private fun moveToTrash(f: File, pr: FmProgress) {
        val vol = volumeOf(f)
        if (vol == null) {
            FmUtil.deleteTree(f)
            return
        }
        val bin = File(vol.root, ".WinRecycleBin")
        bin.mkdirs()
        val id = System.currentTimeMillis().toString() + "_" + Random.nextInt(100000)
        val target = File(bin, id + "_" + f.name)
        if (!f.renameTo(target)) {
            FmUtil.copyTree(f, target, pr)
            if (pr.cancelled) {
                FmUtil.deleteTree(target)
                return
            }
            FmUtil.deleteTree(f)
        }
        synchronized(trashLock) {
            val l = readTrash().toMutableList()
            l.add(FmTrash(target.absolutePath, f.absolutePath, System.currentTimeMillis()))
            writeTrash(l)
        }
    }

    private fun doRestore(sel: List<FmItem>) {
        if (sel.isEmpty()) return
        runOp("Memulihkan", { pr ->
            val done = HashSet<String>()
            val entries = readTrash()
            for (item in sel) {
                if (pr.cancelled) break
                val entry = entries.firstOrNull { it.binPath == item.file.absolutePath } ?: continue
                pr.text = item.name
                val orig = File(entry.orig)
                val parent = orig.parentFile ?: continue
                parent.mkdirs()
                val target = FmUtil.unique(parent, orig.name, item.isDir)
                if (!item.file.renameTo(target)) {
                    FmUtil.copyTree(item.file, target, pr)
                    if (!pr.cancelled) FmUtil.deleteTree(item.file)
                }
                done.add(entry.binPath)
            }
            synchronized(trashLock) {
                writeTrash(readTrash().filter { it.binPath !in done })
            }
        }) { reloadAfterOp() }
    }

    private fun doDeleteForever(sel: List<FmItem>) {
        if (sel.isEmpty()) return
        val msg = if (sel.size == 1) "Hapus \"${sel[0].name}\" secara permanen? Tindakan ini tidak dapat dibatalkan." else
            "Hapus ${sel.size} item secara permanen? Tindakan ini tidak dapat dibatalkan."
        confirmDialog("Hapus permanen", msg, "Hapus") {
            runOp("Menghapus", { pr ->
                val gone = HashSet<String>()
                for (item in sel) {
                    if (pr.cancelled) break
                    pr.text = item.name
                    FmUtil.deleteTree(item.file)
                    gone.add(item.file.absolutePath)
                }
                synchronized(trashLock) {
                    writeTrash(readTrash().filter { it.binPath !in gone })
                }
            }) { reloadAfterOp() }
        }
    }

    private fun doEmptyTrash() {
        if (items.isEmpty()) return
        confirmDialog("Kosongkan Keranjang Sampah", "Semua item di Keranjang Sampah akan dihapus permanen.", "Kosongkan") {
            runOp("Mengosongkan", { pr ->
                for (t in readTrash()) {
                    if (pr.cancelled) break
                    val f = File(t.binPath)
                    pr.text = f.name
                    FmUtil.deleteTree(f)
                }
                if (!pr.cancelled) synchronized(trashLock) { writeTrash(emptyList()) }
            }) { reloadAfterOp() }
        }
    }

    private fun doCompress() {
        val sel = selectedItems()
        if (sel.isEmpty() || loc == "trash") {
            toast("Pilih berkas atau folder terlebih dahulu")
            return
        }
        val dir = if (loc != "home" && !searching) File(loc) else (sel[0].file.parentFile ?: return)
        val base = if (sel.size == 1) (if (sel[0].isDir) sel[0].name else FmUtil.baseName(sel[0].name)) else "Arsip"
        val zipFile = FmUtil.unique(dir, "$base.zip", false)
        val files = sel.map { it.file }
        runOp("Mengompres", { pr ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { z ->
                for (f in files) {
                    if (pr.cancelled) break
                    val parent = f.parentFile ?: continue
                    FmUtil.addZip(z, f, parent.absolutePath.length + 1, pr)
                }
            }
            if (pr.cancelled) zipFile.delete()
        }) { reloadAfterOp() }
    }

    private fun doExtract() {
        val sel = selectedItems()
        if (sel.size != 1 || sel[0].isDir || FmUtil.ext(sel[0].name) != "zip") {
            toast("Pilih satu berkas ZIP")
            return
        }
        val zip = sel[0].file
        val parent = zip.parentFile ?: return
        val dest = FmUtil.unique(parent, FmUtil.baseName(zip.name), true)
        runOp("Mengekstrak", { pr ->
            dest.mkdirs()
            val destPath = dest.canonicalPath
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
                while (!pr.cancelled) {
                    val e = zin.nextEntry ?: break
                    val out = File(dest, e.name)
                    val cp = out.canonicalPath
                    if (cp != destPath && !cp.startsWith(destPath + File.separator)) {
                        throw SecurityException("Entri ZIP tidak valid")
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        pr.text = e.name
                        FileOutputStream(out).use { o -> zin.copyTo(o) }
                    }
                    zin.closeEntry()
                }
            }
        }) { reloadAfterOp() }
    }

    private fun doShareSel() {
        val sel = selectedItems()
        if (sel.isEmpty()) return
        shareFiles(sel.map { it.file })
    }

    private fun shareFiles(files: List<File>) {
        val fl = files.filter { it.isFile }
        if (fl.isEmpty()) {
            toast("Folder tidak dapat dibagikan")
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (f in fl) uris.add(FileProvider.getUriForFile(this, "$packageName.files", f))
            val i = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = FmUtil.mime(fl[0])
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Bagikan"))
        } catch (e: Exception) {
            toast("Tidak dapat membagikan berkas")
        }
    }

    private fun showProps(list: List<FmItem>) {
        if (list.isEmpty()) return
        val body = ll(true)
        fun row(label: String, value: String): TextView {
            val v = tv(value, 14f, Th.p.text)
            body.addView(tv(label, 12f, Th.p.faint), lp(MATCH, WRAP, 0f, 0, dp(10), 0, 0))
            body.addView(v)
            return v
        }
        val sizeTv: TextView
        var countTv: TextView? = null
        if (list.size == 1) {
            val item = list[0]
            row("Nama", item.name)
            row("Jenis", FmUtil.typeLabel(item.isDir, item.name))
            row("Lokasi", item.sub ?: (item.file.parent ?: "/"))
            sizeTv = row("Ukuran", "Menghitung…")
            if (item.isDir) countTv = row("Berisi", "Menghitung…")
            row("Tanggal diubah", fmtDate(item.mod))
            val attrs = ArrayList<String>()
            if (item.file.isHidden || item.name.startsWith(".")) attrs.add("Tersembunyi")
            if (!item.file.canWrite()) attrs.add("Hanya-baca")
            if (attrs.isNotEmpty()) row("Atribut", attrs.joinToString(", "))
        } else {
            row("Dipilih", "${list.size} item")
            sizeTv = row("Ukuran", "Menghitung…")
            countTv = row("Berisi", "Menghitung…")
            row("Lokasi", list[0].sub ?: (list[0].file.parent ?: "/"))
        }
        val dlg = showDialog("Properti", ScrollView(this).apply { addView(body) }, "Tutup", null)
        val cTv = countTv
        pool.execute {
            var total = 0L
            var files = 0L
            var dirs = 0L
            for (it in list) {
                val r = FmUtil.sizeOf(it.file)
                total += r[0]
                files += r[1]
                dirs += r[2]
                if (it.isDir) dirs += 1L
            }
            runOnUiThread {
                if (!isFinishing && dlg.isShowing) {
                    sizeTv.text = "${fmtSize(total)} ($total bayt)"
                    cTv?.text = "$files berkas, $dirs folder"
                }
            }
        }
    }

    // ---------- pencarian ----------
    private fun toggleSearchRow() {
        val row = searchRow ?: return
        if (row.visibility == View.VISIBLE) {
            row.visibility = View.GONE
            stopSearch()
        } else {
            row.visibility = View.VISIBLE
            searchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun stopSearchSilently() {
        ui.removeCallbacks(searchRunnable)
        searchGen.incrementAndGet()
        searching = false
        searchDone = true
        searchQuery = ""
        suppressSearch = true
        searchInput.setText("")
        suppressSearch = false
    }

    private fun stopSearch() {
        val was = searching
        stopSearchSilently()
        renderCrumbs()
        if (was) loadLocation()
        updateBars()
    }

    private fun startSearch(q: String) {
        if (q.isBlank()) {
            if (searching) stopSearch()
            return
        }
        if (loc == "trash") {
            toast("Pencarian tidak tersedia di Keranjang Sampah")
            return
        }
        val base = if (loc == "home") volumes[0].root else File(loc)
        val gen = searchGen.incrementAndGet()
        loadGen.incrementAndGet()
        searching = true
        searchDone = false
        searchQuery = q
        selected.clear()
        selectActive = false
        updateTopBars()
        items = emptyList()
        renderCrumbs()
        applyItems()
        updateBars()
        val ql = q.lowercase()
        pool.execute {
            val found = ArrayList<FmItem>()
            val stack = ArrayList<File>()
            stack.add(base)
            var lastPush = System.currentTimeMillis()
            var pushedCount = 0
            while (stack.isNotEmpty() && searchGen.get() == gen) {
                val d = stack.removeAt(stack.size - 1)
                val kids = try {
                    d.listFiles()
                } catch (e: SecurityException) {
                    null
                }
                if (kids == null) continue
                for (f in kids) {
                    if (searchGen.get() != gen) break
                    if (!showHidden && f.name.startsWith(".")) continue
                    val isDir = f.isDirectory
                    if (isDir) stack.add(f)
                    if (f.name.lowercase().contains(ql)) {
                        found.add(FmItem(f, f.name, isDir, if (isDir) 0L else f.length(), f.lastModified(), f.parent))
                    }
                }
                val now = System.currentTimeMillis()
                if (found.size != pushedCount && now - lastPush > 400L) {
                    lastPush = now
                    pushedCount = found.size
                    pushResults(gen, ArrayList(found), false)
                }
            }
            pushResults(gen, ArrayList(found), true)
        }
    }

    private fun pushResults(gen: Int, list: ArrayList<FmItem>, done: Boolean) {
        runOnUiThread {
            if (!isFinishing && searchGen.get() == gen) {
                sortList(list)
                items = list
                searchDone = done
                applyItems()
            }
        }
    }

    // ---------- layar Beranda ----------
    private fun sectionTitle(text: String): TextView =
        tv(text, 15f, Th.p.text, true).apply { setPadding(dp(4), dp(18), dp(4), dp(8)) }

    private fun renderHome() {
        homeBox.removeAllViews()
        homeBox.addView(sectionTitle("Akses cepat"))
        val quick = quickFolders()
        val cols = if (tabletUi) 4 else 2
        if (quick.isEmpty()) {
            homeBox.addView(tv("Belum ada folder akses cepat.", 13f, Th.p.dim))
        } else {
            for (rowItems in quick.chunked(cols)) {
                val row = ll(false)
                for (i in 0 until cols) {
                    if (i < rowItems.size) {
                        val q = rowItems[i]
                        row.addView(homeTile(q.first, q.third), lp(0, WRAP, 1f, dp(4), dp(4), dp(4), dp(4)))
                    } else {
                        row.addView(View(this), lp(0, WRAP, 1f, dp(4), dp(4), dp(4), dp(4)))
                    }
                }
                homeBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }

        homeBox.addView(sectionTitle("Perangkat dan drive"))
        for (v in volumes) homeBox.addView(driveCard(v), lp(MATCH, WRAP, 0f, dp(4), dp(4), dp(4), dp(4)))

        val head = ll(false, Gravity.CENTER_VERTICAL)
        head.addView(sectionTitle("File terbaru"), LinearLayout.LayoutParams(0, WRAP, 1f))
        val rec = readRecent().map { File(it) }.filter { it.exists() }
        if (rec.isNotEmpty()) {
            head.addView(textButton("Hapus riwayat", false) {
                prefs.edit().putString("fm_recent", "").apply()
                renderHome()
            })
        }
        homeBox.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        if (rec.isEmpty()) {
            homeBox.addView(tv("Berkas yang baru dibuka akan muncul di sini.", 13f, Th.p.dim).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4))
            })
        } else {
            for (f in rec) homeBox.addView(recentRow(f), lp(MATCH, dp(52)))
        }
    }

    private fun homeTile(name: String, dir: File): View {
        val tile = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = rounded(Th.p.bg, dpf(8f), Th.p.stroke, dp(1))
            foreground = ripple(dpf(8f))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { navigate(dir.absolutePath) }
        }
        tile.addView(icon(FmK.FOLDER), LinearLayout.LayoutParams(dp(28), dp(28)))
        tile.addView(tv(name, 14f, Th.p.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(0, WRAP, 1f, dp(12), 0, 0, 0))
        return tile
    }

    private fun driveCard(v: FmVolume): View {
        val (total, free) = volumeUsage(v)
        val card = ll(true).apply {
            background = rounded(Th.p.bg, dpf(8f), Th.p.stroke, dp(1))
            foreground = ripple(dpf(8f))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { navigate(v.root.absolutePath) }
        }
        val top = ll(false, Gravity.CENTER_VERTICAL)
        top.addView(icon(FmK.DRIVE, Th.accent), LinearLayout.LayoutParams(dp(28), dp(28)))
        top.addView(tv(v.label, 14f, Th.p.text, true), lp(0, WRAP, 1f, dp(12), 0, 0, 0))
        card.addView(top)
        if (total > 0L) {
            val used = (((total - free) * 1000L) / total).toInt().coerceIn(0, 1000)
            val track = ll(false).apply {
                background = rounded(Th.p.cardHover, dpf(3f))
                weightSum = 1000f
            }
            track.addView(
                View(this).apply { background = rounded(if (used > 900) Th.danger else Th.accent, dpf(3f)) },
                LinearLayout.LayoutParams(0, MATCH, used.toFloat())
            )
            card.addView(track, lp(MATCH, dp(6), 0f, 0, dp(12), 0, dp(6)))
            card.addView(tv("${fmtSize(free)} tersisa dari ${fmtSize(total)}", 12f, Th.p.dim))
        } else {
            card.addView(tv(v.root.absolutePath, 12f, Th.p.dim), lp(MATCH, WRAP, 0f, 0, dp(6), 0, 0))
        }
        return card
    }

    private fun recentRow(f: File): View {
        val row = ll(false, Gravity.CENTER_VERTICAL).apply {
            background = ripple(dpf(6f))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { if (f.isDirectory) navigate(f.absolutePath) else openFile(f, false) }
        }
        val g = icon(FmUtil.kindFor(f.isDirectory, f.name))
        row.addView(g, LinearLayout.LayoutParams(dp(24), dp(24)))
        val col = ll(true)
        col.addView(tv(f.name, 14f, Th.p.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(tv(f.parent ?: "", 12f, Th.p.dim).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        row.addView(col, lp(0, WRAP, 1f, dp(12), 0, 0, 0))
        row.addView(tv(fmtDate(f.lastModified()), 12f, Th.p.dim))
        return row
    }
}
