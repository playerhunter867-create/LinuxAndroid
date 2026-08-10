LinOx 0.5.4 FIX

Fixes:
ZipException: Not in GZIP format

Cause: the installer consumed the first two gzip magic bytes while detecting
compression, then passed the stream after those bytes to GZIPInputStream.
0.5.4 uses mark/reset so the complete gzip stream is decoded.

Replace only:
app/src/main/java/com/rudolinux/app/RootfsManager.kt

Keep all other LinOx 0.5.x files, the UI, and:
app/src/main/assets/proot-aarch64-static
