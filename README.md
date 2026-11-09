# Saku Deck

Aplikasi Android yang mengubah HP jadi konsol genggam: D-pad, analog, ABXY, L/R dan Start/Select di kiri-kanan, dan **layar virtual** di tengah tempat aplikasi atau game berjalan. Layar virtual dibuat lewat izin **Shizuku**.

## Unduh

APK terbaru selalu ada di sini:
**https://github.com/kerenpandi71-collab/saku-deck/releases/download/latest/SakuDeck.apk**

## Cara pakai

1. Pasang [Shizuku](https://shizuku.rikka.app/download/) dan nyalakan lewat *Wireless debugging*.
2. Pasang `SakuDeck.apk`, buka, lalu tekan **Izinkan** untuk memberi izin Shizuku.
3. Pilih aplikasi atau game. Aplikasinya terbuka di layar tengah.
4. Tombol **MENU** (oranye) membuka daftar aplikasi lagi. **KEMBALI** mengirim tombol Back; **HOME** mengirim tombol Home Android.
5. Untuk ML, tekan **MAP** di layar tengah. Pilih fungsi, lalu ketuk posisi tombol itu pada game. Tombol **A/B/X/Y** memakai pola tekan-tahan, seret untuk membidik skill wheel, lalu lepaskan untuk cast. Pilih **Atur posisi A/B/X/Y + analog kanan** untuk menyeret kontrol; tekan **SIMPAN** agar layout tersimpan di perangkat. **RESET** memulihkan posisi awal dan **BATAL** membuang perubahan yang belum disimpan.
6. Keyboard internal mengikuti dukungan IME pada Android/ROM. Jika tidak muncul di virtual display, ketuk kolom input di aplikasi tujuan lalu tekan **KBD LUAR** untuk membuka Gboard di layar utama dan meneruskan ketikan ke virtual display. Keyboard luar mengirim teks standar; karakter khusus dapat bergantung pada dukungan keymap Android.
7. Analog kiri menggerakkan joystick dengan touch, analog kanan mengirim drag kamera. Preset mapping ML: X/Y/B/A untuk Skill 1/2/Ultimate/Serang, Select untuk Recall, L1 untuk Regen, R2 untuk Battle Spell, R1 untuk item cepat. Posisi mapping layar game dapat dikalibrasi ulang untuk layout game yang berbeda.
8. Di editor mapping, **Cek status touch** menampilkan hasil injeksi terakhir dari layanan Shizuku.

Kalau aplikasi yang dipilih sudah terbuka di layar utama, tutup dulu dari menu aplikasi terbaru, lalu buka lagi dari Saku Deck.

## Cara kerja

- Aplikasi utama (Jetpack Compose) menggambar badan konsol dan tombol-tombolnya.
- Layanan pembantu (`DeckUserService`) dijalankan oleh Shizuku dengan izin *shell*. Layanan ini membuat layar virtual yang digambar ke kotak di tengah, membuka aplikasi di layar itu, dan mengirim tombol/sentuhan ke sana. Direct touch dan touch mapping memakai satu stream multi-pointer supaya joystick dan tombol skill dapat ditekan bersamaan.
- Ide layar virtual + Shizuku terinspirasi dari [Dextop](https://github.com/NarYuki/Dextop) oleh NarYuki (GPL-3.0). Tidak ada kode Dextop yang disalin.

## Build

Push ke `main` dan `feature/**` di-compile otomatis oleh GitHub Actions (runner gratis untuk repo publik). Hanya push ke `main` yang diterbitkan ke rilis `latest`; branch fitur menyimpan APK sebagai artifact Actions.
