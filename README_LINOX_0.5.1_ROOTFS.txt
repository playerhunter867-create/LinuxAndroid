LinOx 0.5.1 — real ARM64 rootfs runtime

This patch adds:
- bundled ARM64 static PRoot binary
- Docker Registry v2/OCI downloader
- SHA-256 verification for every downloaded OCI layer
- rootfs extraction and whiteout handling
- Debian 12, Ubuntu 24.04, Ubuntu 22.04 and Alpine 3.24 installers
- real /bin/sh session launched through PRoot
- Linux terminal activity for the installed distro

No Android root permission is required.

Storage:
Android app-specific external storage:
linox/distros/<distro>/rootfs

Important:
The downloaded distributions are public Docker Hub images. The app verifies
each layer digest supplied by the registry before extraction. Internet access
is required for the first installation.

The PRoot binary supplied with this patch is:
SHA-256: fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45
Architecture: ARM64/aarch64
