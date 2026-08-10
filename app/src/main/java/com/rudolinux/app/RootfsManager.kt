package org.linox.mobile

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Downloads official Linux OCI images from Docker Hub, verifies every layer by
 * its SHA-256 digest, and assembles a rootfs directory.
 *
 * Images:
 *   debian:12
 *   ubuntu:24.04
 *   ubuntu:22.04
 *   alpine:3.24
 *
 * The Docker/OCI registry protocol is used directly, so no Termux is needed.
 */
class RootfsManager(private val context: Context) {

    data class Result(val ok: Boolean, val message: String)

    private val executor = Executors.newCachedThreadPool()

    private val baseDir: File =
        File(context.getExternalFilesDir(null), "linox/distros").apply { mkdirs() }

    fun rootfsDir(id: String): File = File(baseDir, "$id/rootfs")

    fun isInstalled(id: String): Boolean =
        File(rootfsDir(id), "etc/os-release").isFile &&
            File(rootfsDir(id), "bin").isDirectory || File(rootfsDir(id), "usr").isDirectory

    fun installAsync(
        distro: Distro,
        onProgress: (String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        executor.execute {
            try {
                install(distro, onProgress)
                onDone(Result(true, "${distro.name} ${distro.version} installed"))
            } catch (t: Throwable) {
                onDone(Result(false, "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"))
            }
        }
    }

    private fun install(distro: Distro, onProgress: (String) -> Unit) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "This LinOx build currently requires an ARM64 Android device."
        }

        val image = when (distro.id) {
            "debian12" -> "library/debian:12"
            "ubuntu2404" -> "library/ubuntu:24.04"
            "ubuntu2204" -> "library/ubuntu:22.04"
            "alpine" -> "library/alpine:3.24"
            else -> error("Unsupported distribution: ${distro.id}")
        }

        val target = rootfsDir(distro.id)
        val parent = target.parentFile ?: error("Invalid rootfs directory")
        parent.mkdirs()

        val work = File(parent, ".install-${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        try {
            onProgress("Resolving $image...")
            val manifest = resolveManifest(image, onProgress)
            val layers = manifest.getJSONArray("layers")

            onProgress("Found ${layers.length()} ARM64 layers")
            target.deleteRecursively()
            target.mkdirs()

            for (i in 0 until layers.length()) {
                val digest = layers.getJSONObject(i).getString("digest")
                val layer = File(work, "layer-$i.tar.gz")
                onProgress("Downloading layer ${i + 1}/${layers.length()}...")
                downloadBlob(image, digest, layer, onProgress)

                onProgress("Extracting layer ${i + 1}/${layers.length()}...")
                extractTarGz(layer, target)
                layer.delete()
            }

            // Docker/OCI whiteouts represent deletions from lower layers.
            onProgress("Applying filesystem whiteouts...")
            applyWhiteouts(target)

            File(target, "etc").mkdirs()
            File(target, "tmp").mkdirs()

            // A small resolv.conf is useful inside a rootless Android userspace.
            val resolv = File(target, "etc/resolv.conf")
            if (!resolv.exists()) {
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }

            File(parent, ".installed").writeText(
                "image=$image\ninstalled=${System.currentTimeMillis()}\n"
            )

            onProgress("Installation complete.")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun resolveManifest(image: String, onProgress: (String) -> Unit): JSONObject {
        val slash = image.indexOf('/')
        val repo = image.substring(0, slash)
        val tag = image.substring(slash + 1)
        val repoName = repo
        val token = httpText(
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repoName:pull"
        )
        val tokenJson = JSONObject(token)
        val accessToken = tokenJson.getString("token")

        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to listOf(
                "application/vnd.oci.image.index.v1+json",
                "application/vnd.docker.distribution.manifest.list.v2+json",
                "application/vnd.oci.image.manifest.v1+json",
                "application/vnd.docker.distribution.manifest.v2+json"
            ).joinToString(", ")
        )

        val raw = httpText(
            "https://registry-1.docker.io/v2/$repoName/manifests/$tag",
            headers
        )
        val obj = JSONObject(raw)

        if (obj.has("manifests")) {
            val manifests = obj.getJSONArray("manifests")
            var selected: JSONObject? = null
            for (i in 0 until manifests.length()) {
                val m = manifests.getJSONObject(i)
                val platform = m.optJSONObject("platform") ?: continue
                if (platform.optString("os") == "linux" &&
                    platform.optString("architecture") == "arm64") {
                    selected = m
                    break
                }
            }
            val chosen = selected ?: error("No linux/arm64 image found for $image")
            val digest = chosen.getString("digest")
            onProgress("Selected linux/arm64 manifest $digest")
            val chosenRaw = httpText(
                "https://registry-1.docker.io/v2/$repoName/manifests/$digest",
                headers
            )
            return JSONObject(chosenRaw)
        }

        return obj
    }

    private fun downloadBlob(
        image: String,
        digest: String,
        output: File,
        onProgress: (String) -> Unit
    ) {
        val slash = image.indexOf('/')
        val repoName = image.substring(0, slash)
        val token = JSONObject(
            httpText(
                "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repoName:pull"
            )
        ).getString("token")

        val url = URL("https://registry-1.docker.io/v2/$repoName/blobs/$digest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
        }

        check(connection.responseCode in 200..299) {
            "Layer download HTTP ${connection.responseCode}"
        }

        output.parentFile?.mkdirs()
        val expected = digest.removePrefix("sha256:")
        val md = MessageDigest.getInstance("SHA-256")
        val total = connection.contentLengthLong
        var received = 0L

        connection.inputStream.use { input ->
            FileOutputStream(output).use { out ->
                val buffer = ByteArray(1024 * 256)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    md.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    received += n
                    if (total > 0) {
                        onProgress("  ${(received * 100 / total)}%")
                    }
                }
            }
        }
        connection.disconnect()

        val actual = md.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expected, ignoreCase = true)) {
            "SHA-256 mismatch for $digest"
        }
    }

    private fun httpText(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            requestMethod = "GET"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val error = try { c.errorStream?.bufferedReader()?.readText() } catch (_: Throwable) { null }
            c.disconnect()
            error("HTTP $code${if (error.isNullOrBlank()) "" else ": $error"}")
        }
        val result = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return result
    }

    private fun extractTarGz(archive: File, target: File) {
        // Android's system tar handles Linux symlinks and permissions better
        // than a custom Java tar implementation. We decompress gzip in Java
        // first so this does not depend on whether a particular Android
        // build exposes toybox tar's gzip option.
        val tar = File("/system/bin/tar")
        check(tar.exists()) { "Android tar utility not found" }

        val tarFile = File(archive.parentFile, archive.name + ".tar")
        try {
            BufferedInputStream(FileInputStream(archive)).use { input ->
                val a = input.read()
                val b = input.read()
                if (a == 0x1f && b == 0x8b) {
                    GZIPInputStream(input).use { gz ->
                        FileOutputStream(tarFile).use { out -> gz.copyTo(out, 1024 * 256) }
                    }
                } else {
                    input.close()
                    archive.copyTo(tarFile, overwrite = true)
                }
            }

            val p = ProcessBuilder(
                tar.absolutePath, "-xf", tarFile.absolutePath, "-C", target.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            val output = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            check(code == 0) {
                "tar extraction failed ($code): ${output.take(1000)}"
            }
        } finally {
            tarFile.delete()
        }
    }

    private fun applyWhiteouts(root: File) {
        val markers = mutableListOf<File>()
        root.walkTopDown().forEach { f ->
            if (f.name.startsWith(".wh.")) markers += f
        }

        for (marker in markers) {
            val parent = marker.parentFile ?: continue
            if (marker.name == ".wh..wh..opq") {
                parent.listFiles()?.forEach { child ->
                    if (child != marker) child.deleteRecursively()
                }
            } else {
                val target = File(parent, marker.name.removePrefix(".wh."))
                target.deleteRecursively()
            }
            marker.delete()
        }
    }
}
