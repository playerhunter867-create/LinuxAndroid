# LinOx 0.5

LinOx is a Linux-oriented Android environment.

## 0.5 features

- LinOx branding
- Distribution Manager
- Debian 12 (Bookworm) profile
- Ubuntu 24.04 LTS (Noble) profile
- Ubuntu 22.04 LTS (Jammy) profile
- Alpine Linux profile
- Persistent installed/selected distro state
- Terminal command `distros`
- Dedicated distribution screen

## Important

The distro manager in this incremental 0.5 package is the UI/state layer.
A real Linux userspace requires a verified rootfs archive and a compatible
static PRoot runtime. Those components should be added separately rather than
shipping an unverified binary or pretending Android's shell is a Linux distro.

## Build

Use the GitHub Actions workflow or:

```bash
./gradlew :app:assembleDebug
```
