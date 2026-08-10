package org.linox.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    private val prefs = context.getSharedPreferences("linox_distros", Context.MODE_PRIVATE)

    val available = listOf(
        Distro(
            "debian12",
            "Debian",
            "12",
            "Bookworm",
            "arm64",
            "~150 MB*",
            "Stable Debian 12 userspace for LinOx."
        ),
        Distro(
            "ubuntu2404",
            "Ubuntu",
            "24.04 LTS",
            "Noble",
            "arm64",
            "~200 MB*",
            "Ubuntu 24.04 LTS userspace."
        ),
        Distro(
            "ubuntu2204",
            "Ubuntu",
            "22.04 LTS",
            "Jammy",
            "arm64",
            "~190 MB*",
            "Ubuntu 22.04 LTS userspace."
        ),
        Distro(
            "alpine",
            "Alpine Linux",
            "3.x",
            "Alpine",
            "arm64",
            "~10 MB*",
            "Very small Linux userspace."
        )
    )

    fun isInstalled(id: String): Boolean =
        prefs.getBoolean("installed_$id", false)

    fun setInstalled(id: String, installed: Boolean) {
        prefs.edit().putBoolean("installed_$id", installed).apply()
    }

    fun getDefault(): Distro = available.first()

    fun getInstalled(): List<Distro> =
        available.filter { isInstalled(it.id) }

    /*
     * 0.5 stores distro state and provides the UI foundation.
     * The actual PRoot/rootfs bootstrap is intentionally kept separate
     * so a signed/static PRoot binary and verified rootfs archives can be
     * added without pretending that Android's /system shell is a Linux distro.
     */
}
