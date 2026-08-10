package org.linox.mobile

import android.content.Context

data class Distro(
    val id: String,
    val name: String,
    val version: String,
    val codename: String,
    val architecture: String,
    val size: String,
    val description: String
)

class DistroManager(private val context: Context) {

    val available = listOf(
        Distro(
            "debian12", "Debian", "12", "Bookworm", "arm64",
            "~100–200 MB+", "Debian 12 userspace from the official Docker image."
        ),
        Distro(
            "ubuntu2404", "Ubuntu", "24.04 LTS", "Noble", "arm64",
            "~30–100 MB+", "Ubuntu 24.04 LTS userspace from the official Docker image."
        ),
        Distro(
            "ubuntu2204", "Ubuntu", "22.04 LTS", "Jammy", "arm64",
            "~30–100 MB+", "Ubuntu 22.04 LTS userspace from the official Docker image."
        ),
        Distro(
            "alpine", "Alpine Linux", "3.24", "Alpine", "arm64",
            "~10–20 MB+", "Small Alpine Linux userspace from the official Docker image."
        )
    )

    fun getDefault(): Distro = available.first()
}
