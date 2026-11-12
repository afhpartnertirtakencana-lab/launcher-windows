# Win Launcher

Launcher Android bergaya Windows 11 untuk tablet dan HP. Native Kotlin, tanpa library tambahan (hanya API Android).

## Fitur

- **Taskbar** di bawah: tombol Start, cari, aplikasi tersemat, tray (Wi-Fi/seluler, volume, baterai), jam dan tanggal.
  Di tablet ikon berada di tengah seperti Windows 11; di HP ikon rata kiri.
- **Menu Start**: kolom cari, aplikasi **Disematkan**, **Direkomendasikan** (aplikasi terakhir dibuka), daftar **Semua aplikasi** (A–Z), pencarian aplikasi dan web, tombol power (menu Pengaturan / ganti launcher).
- **Desktop**: ikon pintasan di kiri atas, tersusun otomatis mengikuti tinggi layar.
- **Panel jam/kalender**: ketuk jam di taskbar.
- **Tekan lama** ikon mana pun: sematkan/lepas dari Start, taskbar, atau desktop; info aplikasi; copot pemasangan.
- **Tekan lama desktop kosong**: ganti wallpaper, pilih wallpaper bawaan atau wallpaper sistem, pengaturan tampilan.
- Tombol Back hanya menutup panel. Daftar aplikasi otomatis diperbarui saat ada aplikasi dipasang atau dicopot.

## Cara membuat APK

### A. Lewat GitHub (tanpa Android Studio, bisa dari HP/tablet)
1. Buat repo GitHub baru, unggah seluruh isi folder ini.
2. Buka tab **Actions** → workflow **Build APK** → **Run workflow** (juga jalan otomatis setiap push ke `main`).
3. Setelah selesai, unduh artifact **WinLauncher-debug-apk** → ekstrak → `app-debug.apk`.

### B. Android Studio
1. **File → Open** → pilih folder ini, tunggu Gradle sync.
2. Hubungkan perangkat (atau emulator) lalu tekan **Run**.

### C. Command line
```
./gradlew assembleDebug
```
Hasil: `app/build/outputs/apk/debug/app-debug.apk` (butuh JDK 17 dan Android SDK platform 35).

## Memasang dan menjadikan launcher utama
1. Pasang APK (izinkan "pasang dari sumber tidak dikenal" jika diminta).
2. Tekan tombol Home → pilih **Win Launcher** → **Selalu**.
   Atau: Pengaturan → Aplikasi → Aplikasi default → Aplikasi layar beranda.
3. Untuk kembali ke launcher lama: Pengaturan → Aplikasi default → Aplikasi layar beranda.

## Batasan
- Tanpa efek blur akrilik sungguhan; panel memakai transparansi.
- Tidak ada widget, panel notifikasi, atau jendela multi-tugas ala desktop (itu butuh izin sistem khusus).
- Tombol power tidak bisa mematikan perangkat (Android tidak mengizinkannya untuk aplikasi biasa).
- Wallpaper bawaan adalah desain abstrak sendiri, bukan wallpaper Microsoft.

## Struktur kode
- `MainActivity.kt`: seluruh logika dan UI (dibangun lewat kode, tanpa XML layout).
- `Views.kt`: `WallpaperView` (wallpaper) dan `GlyphView` (ikon logo Start, cari, Wi-Fi, volume, baterai, power).
- `AndroidManifest.xml`: kategori `HOME` supaya muncul sebagai pilihan launcher, plus `<queries>` untuk daftar aplikasi di Android 11+.
