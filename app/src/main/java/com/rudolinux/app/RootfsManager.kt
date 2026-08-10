package org.linox.mobile

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Downloads official Docker/OCI Linux images from Docker Hub.
 *
 * The previous implementation failed with HTTP 404 when Docker Hub
 * redirected blob downloads to a different CDN host while the Bearer
 * Authorization header was still attached. Docker Hub CDNs can reject
 * that header. This implementation follows redirects manually and only
 * forwards Authorization when the redirect stays on the same host.
 */
class RootfsManager(private val context: Context) {

    data class Result(val ok: Boolean, val message: String)

    private val executor = Executors.newCachedThreadPool()

    private val baseDir: File =
        File(context.getExternalFilesDir(null), "linox/distros").apply { mkdirs() }

    fun rootfsDir(id: String): File = File(baseDir, "$id/rootfs")

    fun isInstalled(id: String): Boolean {
        val root = rootfsDir(id)
        return File(root, "etc/os-release").isFile &&
            File(root, "bin").isDirectory &&
            File(root, "usr").isDirectory
    }

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
                onDone(
                    Result(
                        false,
                        "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                    )
                )
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
            val layers = manifest.optJSONArray("layers")
                ?: error("Registry returned a manifest without layers")

            onProgress("Found ${layers.length()} ARM64 layers")
            target.deleteRecursively()
            target.mkdirs()

            for (i in 0 until layers.length()) {
                val digest = layers.getJSONObject(i).getString("digest")
                val layer = File(work, "layer-$i.tar")
                onProgress("Downloading layer ${i + 1}/${layers.length()}...")
                downloadBlob(image, digest, layer, onProgress)

                onProgress("Extracting layer ${i + 1}/${layers.length()}...")
                extractLayer(layer, target)
                layer.delete()
            }

            onProgress("Applying filesystem whiteouts...")
            applyWhiteouts(target)

            check(File(target, "etc/os-release").isFile) {
                "Downloaded image did not produce a valid Linux rootfs (/etc/os-release missing)"
            }
            check(File(target, "bin").isDirectory || File(target, "usr/bin").isDirectory) {
                "Downloaded image did not produce a valid Linux rootfs (/bin or /usr/bin missing)"
            }

            File(target, "tmp").mkdirs()

            val resolv = File(target, "etc/resolv.conf")
            if (!resolv.exists()) {
                resolv.parentFile?.mkdirs()
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
        require(slash > 0) { "Invalid image reference: $image" }

        val repoName = image.substring(0, slash)
        val tag = image.substring(slash + 1)

        val tokenUrl =
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repoName:pull"

        val tokenJson = JSONObject(httpText(tokenUrl))
        val accessToken = tokenJson.optString("token").ifBlank {
            tokenJson.optString("access_token")
        }
        check(accessToken.isNotBlank()) { "Docker Hub did not return a Bearer token" }

        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to listOf(
                "application/vnd.oci.image.index.v1+json",
                "application/vnd.oci.image.manifest.v1+json",
                "application/vnd.docker.distribution.manifest.list.v2+json",
                "application/vnd.docker.distribution.manifest.v2+json"
            ).joinToString(", "),
            "User-Agent" to "LinOx/0.5.2"
        )

        val manifestUrl =
            "https://registry-1.docker.io/v2/$repoName/manifests/$tag"

        val obj = JSONObject(httpText(manifestUrl, headers))

        if (!obj.has("manifests")) {
            return obj
        }

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

        return JSONObject(
            httpText(
                "https://registry-1.docker.io/v2/$repoName/manifests/$digest",
                headers
            )
        )
    }

    private fun downloadBlob(
        image: String,
        digest: String,
        output: File,
        onProgress: (String) -> Unit
    ) {
        val slash = image.indexOf('/')
        require(slash > 0) { "Invalid image reference: $image" }
        val repoName = image.substring(0, slash)

        val tokenJson = JSONObject(
            httpText(
                "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repoName:pull"
            )
        )
        val token = tokenJson.optString("token").ifBlank {
            tokenJson.optString("access_token")
        }
        check(token.isNotBlank()) { "Docker Hub did not return a Blob token" }

        val url = URL("https://registry-1.docker.io/v2/$repoName/blobs/$digest")
        val expected = digest.removePrefix("sha256:")
        val temp = File(output.parentFile, output.name + ".part")

        temp.delete()
        var currentUrl = url
        var authorization = "Bearer $token"

        try {
            repeat(6) { redirectNumber ->
                val c = (currentUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "LinOx/0.5.2")

                    // Keep Bearer auth for the registry host. If Docker Hub
                    // redirects to a different CDN host, do NOT forward it.
                    if (currentUrl.host == "registry-1.docker.io") {
                        setRequestProperty("Authorization", authorization)
                    }
                }

                val code = c.responseCode

                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    check(!location.isNullOrBlank()) {
                        "Registry returned redirect without Location"
                    }
                    currentUrl = URL(currentUrl, location)
                    authorization = ""
                    onProgress("Following secure download redirect ${redirectNumber + 1}...")
                    return@repeat
                }

                check(code in 200..299) {
                    val error = try {
                        c.errorStream?.bufferedReader()?.readText()
                    } catch (_: Throwable) {
                        null
                    }
                    c.disconnect()
                    "Layer download HTTP $code${if (error.isNullOrBlank()) "" else ": ${error.take(300)}"}"
                }

                output.parentFile?.mkdirs()
                val md = MessageDigest.getInstance("SHA-256")
                val total = c.contentLengthLong
                var received = 0L

                c.inputStream.use { input ->
                    FileOutputStream(temp).use { out ->
                        val buffer = ByteArray(1024 * 256)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            md.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            received += n

                            if (total > 0) {
                                onProgress("  ${received * 100 / total}%")
                            }
                        }
                    }
                }
                c.disconnect()

                val actual = md.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expected, ignoreCase = true)) {
                    "SHA-256 mismatch for $digest"
                }

                if (!temp.renameTo(output)) {
                    temp.copyTo(output, overwrite = true)
                    temp.delete()
                }

                onProgress("SHA-256 verified.")
                return
            }

            error("Too many HTTP redirects while downloading $digest")
        } finally {
            temp.delete()
        }
    }

    private fun httpText(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        val code = c.responseCode
        if (code !in 200..299) {
            val error = try {
                c.errorStream?.bufferedReader()?.readText()
            } catch (_: Throwable) {
                null
            }
            c.disconnect()
            error("HTTP $code${if (error.isNullOrBlank()) "" else ": ${error.take(500)}"}")
        }

        val result = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return result
    }

    private fun extractLayer(archive: File, target: File) {
        val tar = File("/system/bin/tar")
        check(tar.exists()) { "Android tar utility not found" }

        val tarFile = File(archive.parentFile, archive.name + ".tmp.tar")
        try {
            BufferedInputStream(FileInputStream(archive)).use { input ->
                val a = input.read()
                val b = input.read()

                if (a == 0x1f && b == 0x8b) {
                    GZIPInputStream(input).use { gz ->
                        FileOutputStream(tarFile).use { out ->
                            gz.copyTo(out, 1024 * 256)
                        }
                    }
                } else {
                    FileOutputStream(tarFile).use { out ->
                        input.copyTo(out, 1024 * 256)
                    }
                }
            }

            val process = ProcessBuilder(
                tar.absolutePath,
                "-xf",
                tarFile.absolutePath,
                "-C",
                target.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()

            check(code == 0) {
                "tar extraction failed ($code): ${output.take(1000)}"
            }
        } finally {
            tarFile.delete()
        }
    }

    private fun applyWhiteouts(root: File) {
        val markers = mutableListOf<File>()

        root.walkTopDown().forEach { file ->
            if (file.name.startsWith(".wh.")) {
                markers += file
            }
        }

        for (marker in markers) {
            val parent = marker.parentFile ?: continue

            if (marker.name == ".wh..wh..opq") {
                parent.listFiles()?.forEach { child ->
                    if (child != marker) child.deleteRecursively()
                }
            } else {
                File(parent, marker.name.removePrefix(".wh.")).deleteRecursively()
            }

            marker.delete()
        }
    }
}
