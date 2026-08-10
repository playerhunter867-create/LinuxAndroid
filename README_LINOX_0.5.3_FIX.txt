LinOx 0.5.3 — RootFS downloader fix

Replace only the files included in this archive.

Main fix:
- Docker Registry authentication now follows the WWW-Authenticate Bearer challenge.
- Supports both token and access_token responses.
- Manifest requests can recover from 401.
- Blob requests can recover from 401.
- Registry redirects are followed without forwarding the Bearer token to CDN hosts.
- SHA-256 is checked against every OCI layer digest before extraction.
- RootFS is deleted if installation fails, so a partial install cannot be marked usable.
- /etc/os-release is required before installation is marked complete.
- Fixed the installed-rootfs boolean check.

The existing LinOx 0.5.1 UI, distro list, and PRoot asset are intentionally untouched.
Do NOT remove app/src/main/assets/proot-aarch64-static from the repository.
