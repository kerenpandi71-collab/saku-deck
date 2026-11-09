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
5. Untuk ML, tekan **MAP** di layar tengah. Pilih fungsi, lalu ketuk posisi tombol itu pada game. Analog kiri menggerakkan joystick dengan touch, analog kanan mengirim drag kamera, dan tombol X/Y/B/A, Select, L1/L2, serta R1/R2 dapat dipetakan. Preset awal: X/Y/B/A untuk Skill 1/2/Ultimate/Serang, Select untuk Recall, L1 untuk Regen, R2 untuk Battle Spell, R1 untuk item cepat. Posisi mapping disimpan di perangkat dan dapat dikalibrasi ulang untuk layout game yang berbeda.
6. Di editor mapping, **Cek status touch** menampilkan hasil injeksi terakhir dari layanan Shizuku.

Kalau aplikasi yang dipilih sudah terbuka di layar utama, tutup dulu dari menu aplikasi terbaru, lalu buka lagi dari Saku Deck.

## Cara kerja

- Aplikasi utama (Jetpack Compose) menggambar badan konsol dan tombol-tombolnya.
- Layanan pembantu (`DeckUserService`) dijalankan oleh Shizuku dengan izin *shell*. Layanan ini membuat layar virtual yang digambar ke kotak di tengah, membuka aplikasi di layar itu, dan mengirim tombol/sentuhan ke sana. Direct touch dan touch mapping memakai satu stream multi-pointer supaya joystick dan tombol skill dapat ditekan bersamaan.
- Ide layar virtual + Shizuku terinspirasi dari [Dextop](https://github.com/NarYuki/Dextop) oleh NarYuki (GPL-3.0). Tidak ada kode Dextop yang disalin.

## Build

Push ke `main` dan `feature/**` di-compile otomatis oleh GitHub Actions (runner gratis untuk repo publik). Hanya push ke `main` yang diterbitkan ke rilis `latest`; branch fitur menyimpan APK sebagai artifact Actions.
