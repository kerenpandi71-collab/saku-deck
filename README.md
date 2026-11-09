# Saku Deck

Aplikasi Android yang mengubah HP jadi konsol genggam: D-pad, analog, ABXY, L/R dan Start/Select di kiri-kanan, dan **layar virtual** di tengah tempat aplikasi atau game berjalan. Layar virtual dibuat lewat izin **Shizuku**.

## Unduh

APK terbaru selalu ada di sini:
**https://github.com/kerenpandi71-collab/saku-deck/releases/download/latest/SakuDeck.apk**

## Cara pakai

1. Pasang [Shizuku](https://shizuku.rikka.app/download/) dan nyalakan lewat *Wireless debugging*.
2. Pasang `SakuDeck.apk`, buka, lalu tekan **Izinkan** untuk memberi izin Shizuku.
3. Pilih aplikasi atau game. Aplikasinya terbuka di layar tengah.
4. Tombol **MENU** (oranye) membuka daftar aplikasi lagi. **KEMBALI** sama dengan tombol back.

Kalau aplikasi yang dipilih sudah terbuka di layar utama, tutup dulu dari menu aplikasi terbaru, lalu buka lagi dari Saku Deck.

## Cara kerja

- Aplikasi utama (Jetpack Compose) menggambar badan konsol dan tombol-tombolnya.
- Layanan pembantu (`DeckUserService`) dijalankan oleh Shizuku dengan izin *shell*. Layanan ini membuat layar virtual yang digambar ke kotak di tengah, membuka aplikasi di layar itu, dan mengirim tombol/sentuhan ke sana.
- Ide layar virtual + Shizuku terinspirasi dari [Dextop](https://github.com/NarYuki/Dextop) oleh NarYuki (GPL-3.0). Tidak ada kode Dextop yang disalin.

## Build

Setiap push ke `main` di-compile otomatis oleh GitHub Actions (runner gratis untuk repo publik) dan diterbitkan ke rilis `latest`.
