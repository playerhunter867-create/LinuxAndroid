# LinOx 0.5.2

RootFS download/runtime fix.

Changes:
- Fixes Docker Hub blob downloads that return HTTP 404 after a registry-to-CDN redirect.
- Follows redirects manually and strips the Bearer Authorization header on cross-host CDN redirects.
- Verifies SHA-256 of every OCI layer before extraction.
- Validates `/etc/os-release`, `/bin` and `/usr` after installation.
- Keeps the existing Debian 12, Ubuntu 24.04, Ubuntu 22.04 and Alpine 3.24 UI.
- Keeps the bundled `proot-aarch64-static`.
- Adds a PRoot seccomp compatibility environment variable.
- Version bumped to 0.5.2.

The app still requires an ARM64 Android device for the bundled PRoot runtime.
