# Win Launcher 2.0

Launcher Android bergaya Windows 11 untuk tablet dan HP, lengkap dengan **Pengaturan** dan **File Explorer** sendiri.
Native Kotlin; satu-satunya dependensi adalah `androidx.core` (untuk FileProvider).

## Tema Azure Blue
- Mode **Terang**, **Gelap**, atau **Ikuti sistem**; juga tersedia di Quick Settings (tombol mode gelap).
- Warna aksen: Azure (bawaan), Cyan, Teal, Indigo, Ungu, Mawar, Oranye, Hijau, Grafit.
- Efek transparansi dan animasi bisa dimatikan. Wallpaper bawaan mengikuti warna aksen dan mode.
- Lokasi: **Pengaturan Win → Personalisasi**.

## Launcher
- **Taskbar:** tombol Start, cari, aplikasi tersemat, tray (jaringan, volume, baterai), jam dan tanggal. Rata tengah atau kiri.
- **Menu Start:** pencarian, Disematkan, Direkomendasikan, Semua aplikasi A–Z, pencarian web, tombol Pengaturan/File Explorer/Power.
- **Quick Settings:** Wi-Fi, Bluetooth, mode pesawat, penghemat baterai, lokasi, mode gelap, slider volume dan kecerahan.
- **Kalender dan jam**, desktop dengan ikon pintasan, menu tekan-lama pada ikon dan desktop.

## Pengaturan Win (meniru Pengaturan Windows 11)
Navigasi kiri dengan pencarian pengaturan. Halaman:
**Sistem** (Layar, Suara, Daya & baterai, Penyimpanan, Tentang), **Bluetooth & perangkat**, **Jaringan & internet**,
**Personalisasi** (tema, aksen, wallpaper, Taskbar, Start, Desktop), **Aplikasi** (termasuk daftar aplikasi terpasang),
**Akun**, **Waktu & bahasa**, **Aksesibilitas**, **Privasi & keamanan**, **Pembaruan**.
Pengaturan milik launcher dikelola langsung di sini; yang lain membuka halaman Pengaturan Android yang sesuai.

## File Explorer
- Panel navigasi: Beranda, Akses cepat, Drive/penyimpanan (internal dan SD card), Recycle Bin.
- Bilah alamat dengan breadcrumb, tombol Back/Forward/Up/Refresh, pencarian.
- Tampilan detail, daftar, dan ikon (dengan thumbnail gambar dan video); urut berdasarkan nama/tanggal/jenis/ukuran.
- Pilih banyak berkas; Salin, Potong, Tempel, Ganti nama, Hapus (ke Recycle Bin, bisa dipulihkan), Folder baru, Berkas teks baru.
- Kompres ke ZIP, ekstrak ZIP, Bagikan, Buka dengan, Properti, tampilkan berkas tersembunyi.
- Saat pertama dibuka, beri izin **Akses semua berkas** (Android 11+) atau izin penyimpanan (Android 10 ke bawah).

## Cara membuat APK

### A. Lewat GitHub (tanpa Android Studio, bisa dari HP/tablet)
1. Buat repo GitHub baru, unggah seluruh isi folder ini.
2. Buka tab **Actions** → **Build APK** → **Run workflow** (juga jalan otomatis setiap push ke `main`).
3. Unduh artifact **WinLauncher-debug-apk** → ekstrak → `app-debug.apk`.

### B. Android Studio
**File → Open** → pilih folder ini → tunggu sync → **Run**.

### C. Command line
```
./gradlew assembleDebug
```
Butuh JDK 17 dan Android SDK platform 35. Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

## Memasang
1. Pasang APK (izinkan "pasang dari sumber tidak dikenal" jika diminta).
2. Tekan Home → pilih **Win Launcher** → **Selalu**. Atau: Pengaturan Android → Aplikasi default → Aplikasi layar beranda.
3. Kembali ke launcher lama dari pengaturan yang sama.

## Batasan
- Kode ditulis tanpa kompilasi (tidak ada Android SDK di lingkungan pembuatnya). Jika build pertama menampilkan error, kirim lognya.
- Tidak ada blur akrilik sungguhan (panel memakai transparansi), tidak ada widget, dan tombol power tidak bisa mematikan perangkat.
- Sakelar radio (Wi-Fi, Bluetooth, mode pesawat) tidak bisa diubah langsung oleh aplikasi biasa; tombolnya membuka panel sistem.
- Wallpaper bawaan adalah desain sendiri, bukan wallpaper Microsoft.

## Struktur kode
- `Ui.kt`: tema `Th` (palet gelap/terang + aksen), komponen UI, dialog.
- `Views.kt`: `WallpaperView` dan `GlyphView` (semua ikon digambar lewat kode).
- `MainActivity.kt`: launcher. `SettingsActivity.kt`: Pengaturan. `FilesActivity.kt` + `FilesModel.kt`: File Explorer.
