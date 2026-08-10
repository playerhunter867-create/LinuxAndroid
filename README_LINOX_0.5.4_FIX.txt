LinOx 0.5.4 — RootFS extraction fix

This patch keeps the existing LinOx 0.5.x interface and PRoot.

Replace only:
  app/src/main/java/com/rudolinux/app/RootfsManager.kt
  app/build.gradle.kts

Do not remove:
  app/src/main/assets/proot-aarch64-static

Fixes:
- Removes Java GZIPInputStream from OCI layer extraction.
- Preserves downloaded blobs byte-for-byte with Accept-Encoding: identity.
- Lets Android toybox tar extract gzip/xz/bzip2/zstd/plain tar based on magic.
- Adds clear format/magic/tar diagnostics if the device's tar cannot extract a layer.
- Increments APK versionCode to 7 / versionName 0.5.4.
