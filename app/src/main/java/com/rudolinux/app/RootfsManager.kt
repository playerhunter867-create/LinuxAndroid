package org.linox.mobile

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory

/**
 * LinOx RootFS installer.
 *
 * Pulls public Docker Hub OCI images for linux/arm64 without requiring
 * Docker credentials. Authentication follows the registry's WWW-Authenticate
 * Bearer challenge, which avoids hard-coding assumptions about Docker Hub's
 * token endpoint.
 *
 * Images:
 *   library/debian:12
 *   library/ubuntu:24.04
 *   library/ubuntu:22.04
 *   library/alpine:3.24
 *
 * Every downloaded blob is SHA-256 checked against its OCI digest before
 * extraction. Registry redirects to blob/CDN URLs are followed without
 * forwarding the Bearer token to the redirected host.
 */
class RootfsManager(private val context: Context) {

    data class Result(val ok: Boolean, val message: String)

    private data class AuthChallenge(
        val realm: String,
        val service: String?,
        val scope: String?
    )

    private val executor = Executors.newCachedThreadPool()

    private val baseDir: File =
        File(context.getExternalFilesDir(null), "linox/distros").apply { mkdirs() }

    fun rootfsDir(id: String): File = File(baseDir, "$id/rootfs")

    fun isInstalled(id: String): Boolean {
        val root = rootfsDir(id)
        return File(root, "etc/os-release").isFile &&
            (File(root, "bin").isDirectory || File(root, "usr").isDirectory)
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
            onProgress("Connecting to Docker Registry...")
            val manifest = resolveManifest(image, onProgress)
            val layers = manifest.optJSONArray("layers")
                ?: error("Selected image manifest contains no layers")

            onProgress("Selected linux/arm64 image")
            onProgress("Found ${layers.length()} filesystem layers")

            target.deleteRecursively()
            target.mkdirs()

            for (i in 0 until layers.length()) {
                val digest = layers.getJSONObject(i).getString("digest")
                val layer = File(work, "layer-$i")
                onProgress("Downloading layer ${i + 1}/${layers.length()}...")
                downloadBlob(image, digest, layer, onProgress)

                onProgress("Extracting layer ${i + 1}/${layers.length()}...")
                extractLayer(layer, target)
                layer.delete()
            }

            File(target, "etc").mkdirs()
            File(target, "tmp").mkdirs()

            val resolv = File(target, "etc/resolv.conf")
            if (!resolv.exists() || resolv.length() == 0L) {
                resolv.writeText(
                    "nameserver 1.1.1.1\n" +
                        "nameserver 8.8.8.8\n"
                )
            }

            check(File(target, "etc/os-release").isFile) {
                "RootFS extraction completed but /etc/os-release is missing."
            }

            File(parent, ".installed").writeText(
                "image=$image\n" +
                    "architecture=linux/arm64\n" +
                    "installed=${System.currentTimeMillis()}\n"
            )

            onProgress("✓ RootFS verified and installed.")
        } catch (t: Throwable) {
            target.deleteRecursively()
            throw t
        } finally {
            work.deleteRecursively()
        }
    }

    private fun resolveManifest(
        image: String,
        onProgress: (String) -> Unit
    ): JSONObject {
        val (repoName, reference) = splitImage(image)
        val manifestUrl =
            "https://registry-1.docker.io/v2/$repoName/manifests/$reference"

        val accepted = listOf(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json"
        ).joinToString(", ")

        var response = requestText(
            URL(manifestUrl),
            headers = mapOf("Accept" to accepted)
        )

        var raw = response.body
        var obj = JSONObject(raw)

        if (obj.has("manifests")) {
            val manifests = obj.getJSONArray("manifests")
            var selected: JSONObject? = null

            for (i in 0 until manifests.length()) {
                val m = manifests.getJSONObject(i)
                val platform = m.optJSONObject("platform") ?: continue

                val os = platform.optString("os")
                val architecture = platform.optString("architecture")
                val variant = platform.optString("variant")

                if (os == "linux" && architecture == "arm64") {
                    selected = m
                    onProgress(
                        "Found ARM64 variant" +
                            if (variant.isBlank()) "" else " ($variant)"
                    )
                    break
                }
            }

            val chosen = selected ?: error("No linux/arm64 image found for $image")
            val digest = chosen.getString("digest")

            response = requestText(
                URL(
                    "https://registry-1.docker.io/v2/$repoName/manifests/$digest"
                ),
                headers = mapOf("Accept" to accepted)
            )

            raw = response.body
            obj = JSONObject(raw)
        }

        return obj
    }

    private fun downloadBlob(
        image: String,
        digest: String,
        output: File,
        onProgress: (String) -> Unit
    ) {
        val (repoName, _) = splitImage(image)
        val blobUrl = URL(
            "https://registry-1.docker.io/v2/$repoName/blobs/$digest"
        )

        var connection = openWithBearer(blobUrl)
        var responseCode = connection.responseCode

        // Docker Hub commonly returns a redirect to a CDN. Never forward
        // the Bearer token to the redirected host.
        if (responseCode in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: error("Registry returned HTTP $responseCode without Location")
            connection.disconnect()
            val redirected = URL(blobUrl, location)
            connection = openNoAuth(redirected)
            responseCode = connection.responseCode
        }

        // A token can expire or be rejected. Re-discover the challenge once.
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val challenge = parseBearerChallenge(
                connection.getHeaderField("WWW-Authenticate")
            )
            connection.disconnect()

            if (challenge != null) {
                val token = fetchToken(challenge)
                connection = openWithToken(blobUrl, token)
                responseCode = connection.responseCode

                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Registry redirect did not include Location")
                    connection.disconnect()
                    connection = openNoAuth(URL(blobUrl, location))
                    responseCode = connection.responseCode
                }
            }
        }

        check(responseCode in 200..299) {
            val detail = try {
                connection.errorStream?.bufferedReader()?.readText()?.take(500)
            } catch (_: Throwable) {
                null
            }
            connection.disconnect()
            "Layer download HTTP $responseCode" +
                if (detail.isNullOrBlank()) "" else ": $detail"
        }

        output.parentFile?.mkdirs()

        val expected = digest.removePrefix("sha256:").lowercase()
        val md = MessageDigest.getInstance("SHA-256")
        val total = connection.contentLengthLong
        var received = 0L

        try {
            connection.inputStream.use { input ->
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(1024 * 256)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue

                        md.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        received += n

                        if (total > 0L) {
                            onProgress(
                                "  ${(received * 100L / total)}%  " +
                                    "${formatBytes(received)} / ${formatBytes(total)}"
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        val actual = md.digest().joinToString("") { "%02x".format(it) }

        check(actual.equals(expected, ignoreCase = true)) {
            "SHA-256 mismatch for $digest\nExpected: $expected\nActual: $actual"
        }

        onProgress("✓ SHA-256 verified")
    }

    private fun openWithBearer(url: URL): HttpURLConnection {
        val connection = openNoAuth(url)
        val code = connection.responseCode

        if (code != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return connection
        }

        val challenge = parseBearerChallenge(
            connection.getHeaderField("WWW-Authenticate")
        ) ?: run {
            connection.disconnect()
            error("Registry returned HTTP 401 without a Bearer challenge.")
        }

        connection.disconnect()

        val token = fetchToken(challenge)
        return openWithToken(url, token)
    }

    private fun openWithToken(url: URL, token: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            // Keep OCI blobs byte-for-byte compressed; this is required for digest verification.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun openNoAuth(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            // Keep OCI blobs byte-for-byte compressed; this is required for digest verification.
            setRequestProperty("Accept-Encoding", "identity")
        }

    private data class TextResponse(
        val body: String,
        val code: Int
    )

    private fun requestText(
        url: URL,
        headers: Map<String, String> = emptyMap()
    ): TextResponse {
        var connection = openNoAuth(url)
        headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        var code = connection.responseCode

        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val challenge = parseBearerChallenge(
                connection.getHeaderField("WWW-Authenticate")
            ) ?: run {
                val body = readError(connection)
                connection.disconnect()
                error("HTTP 401: $body")
            }

            connection.disconnect()

            val token = fetchToken(challenge)
            connection = openWithToken(url, token)
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            code = connection.responseCode
        }

        // Manifest requests normally do not redirect, but follow a redirect
        // defensively without carrying Authorization to another host.
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: error("HTTP $code without Location")
            connection.disconnect()

            connection = openNoAuth(URL(url, location))
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            code = connection.responseCode
        }

        if (code !in 200..299) {
            val body = readError(connection)
            connection.disconnect()
            error("HTTP $code${if (body.isBlank()) "" else ": $body"}")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        return TextResponse(body, code)
    }

    private fun fetchToken(challenge: AuthChallenge): String {
        val query = StringBuilder()
            .append("service=")
            .append(urlEncode(challenge.service ?: "registry.docker.io"))

        if (!challenge.scope.isNullOrBlank()) {
            query.append("&scope=").append(urlEncode(challenge.scope))
        }

        // Docker accepts this client_id for anonymous token requests.
        query.append("&client_id=linox")

        val tokenUrl = URL("${challenge.realm}?$query")
        val connection = openNoAuth(tokenUrl)
        val code = connection.responseCode

        if (code !in 200..299) {
            val body = readError(connection)
            connection.disconnect()
            error("Token request HTTP $code${if (body.isBlank()) "" else ": $body"}")
        }

        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(raw)
        return json.optString("token").ifBlank {
            json.optString("access_token")
        }.ifBlank {
            error("Registry token response did not contain a token.")
        }
    }

    private fun parseBearerChallenge(value: String?): AuthChallenge? {
        if (value.isNullOrBlank()) return null
        if (!value.trim().startsWith("Bearer", ignoreCase = true)) return null

        val params = value.substringAfter("Bearer", "").trim()
        val result = mutableMapOf<String, String>()

        val regex = Regex("""([A-Za-z][A-Za-z0-9_-]*)="([^"]*)""")
        for (match in regex.findAll(params)) {
            result[match.groupValues[1].lowercase()] = match.groupValues[2]
        }

        val realm = result["realm"] ?: return null
        return AuthChallenge(
            realm = realm,
            service = result["service"],
            scope = result["scope"]
        )
    }

    private fun splitImage(image: String): Pair<String, String> {
        val slash = image.indexOf('/')
        val colon = image.lastIndexOf(':')

        if (slash <= 0 || colon <= slash) {
            error("Invalid Docker image reference: $image")
        }

        return image.substring(0, colon) to image.substring(colon + 1)
    }

    private fun readError(connection: HttpURLConnection): String =
        try {
            connection.errorStream?.bufferedReader()?.readText()?.take(1000) ?: ""
        } catch (_: Throwable) {
            ""
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
        if (bytes < 1024L * 1024L * 1024L) {
            return "${bytes / (1024L * 1024L)} MB"
        }
        return "${bytes / (1024L * 1024L * 1024L)} GB"
    }

    /**
     * Extract one OCI/Docker filesystem layer without asking Android's
     * /system/bin/tar to create Linux hard-links.
     *
     * Android app filesystems can reject some tar link operations. OCI layers
     * legitimately contain symlinks and hard-links, so we materialize hard
     * links as normal files and create symlinks ourselves. This also lets us
     * safely reject path traversal entries.
     */
    private data class PendingSymlink(
        val destination: File,
        val linkName: String
    )

    private data class HardLinkPending(
        val destination: File,
        val linkName: String
    )

    /**
     * Extract an OCI/Docker layer without letting Android's tar implementation
     * create Linux links.
     *
     * Important details:
     *  - whiteouts are applied BEFORE the new layer is extracted. Applying an
     *    opaque whiteout after extraction would delete files that belong to the
     *    same layer (including /etc/os-release).
     *  - symlinks are retried after the whole layer has been read. This matters
     *    for links such as Debian's /etc/os-release -> ../usr/lib/os-release
     *    when the link target appears later in the tar stream.
     *  - hard links are materialized as normal files.
     */
    private fun extractLayer(archive: File, target: File) {
        target.mkdirs()

        // First pass: process OCI whiteouts. We intentionally scan the archive
        // twice because the layer is already downloaded locally and this makes
        // whiteout semantics deterministic.
        FileInputStream(archive).use { fileInput ->
            val buffered = fileInput.buffered()
            val compressor = CompressorStreamFactory().createCompressorInputStream(buffered)
            TarArchiveInputStream(compressor).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val name = normalizeArchivePath(entry.name)
                    if (name.isNotEmpty()) {
                        val base = name.substringAfterLast('/')
                        val parentName = name.substringBeforeLast('/', "")
                        val parent = safeTarget(target, parentName)

                        if (base == ".wh..wh..opq") {
                            parent.listFiles()?.forEach { it.deleteRecursively() }
                        } else if (base.startsWith(".wh.")) {
                            val victim = File(parent, base.removePrefix(".wh."))
                            deleteAny(victim)
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }

        val pendingSymlinks = mutableListOf<PendingSymlink>()
        val pendingHardLinks = mutableListOf<HardLinkPending>()

        // Second pass: extract actual filesystem entries. Whiteout marker files
        // themselves are not part of the resulting rootfs.
        FileInputStream(archive).use { fileInput ->
            val buffered = fileInput.buffered()
            val compressor = CompressorStreamFactory().createCompressorInputStream(buffered)
            TarArchiveInputStream(compressor).use { tar ->
                var entry = tar.nextTarEntry

                while (entry != null) {
                    val current = entry
                    val name = normalizeArchivePath(current.name)

                    if (name.isEmpty()) {
                        entry = tar.nextTarEntry
                        continue
                    }

                    val base = name.substringAfterLast('/')
                    if (base == ".wh..wh..opq" || base.startsWith(".wh.")) {
                        entry = tar.nextTarEntry
                        continue
                    }

                    val destination = safeTarget(target, name)

                    when {
                        current.isDirectory -> {
                            destination.mkdirs()
                            applyMode(destination, current.mode)
                        }

                        current.isSymbolicLink -> {
                            deleteAny(destination)
                            destination.parentFile?.mkdirs()

                            val linkName = current.linkName
                            require(linkName.isNotBlank()) {
                                "Invalid empty symlink target: $name"
                            }

                            val created = runCatching {
                                Files.createSymbolicLink(
                                    destination.toPath(),
                                    Paths.get(linkName)
                                )
                            }.isSuccess

                            if (!created) {
                                pendingSymlinks += PendingSymlink(
                                    destination = destination,
                                    linkName = linkName
                                )
                            }
                        }

                        current.isLink -> {
                            deleteAny(destination)
                            destination.parentFile?.mkdirs()
                            pendingHardLinks += HardLinkPending(
                                destination = destination,
                                linkName = current.linkName
                            )
                        }

                        current.isFile -> {
                            deleteAny(destination)
                            destination.parentFile?.mkdirs()

                            FileOutputStream(destination).use { output ->
                                tar.copyTo(output)
                            }

                            applyMode(destination, current.mode)
                        }

                        else -> {
                            // Device nodes/FIFOs cannot safely be created in
                            // ordinary Android app storage; skip them.
                        }
                    }

                    entry = tar.nextTarEntry
                }
            }
        }

        // Resolve links after every regular file in this layer exists.
        repeat(4) {
            var progress = false

            val symlinkIterator = pendingSymlinks.iterator()
            while (symlinkIterator.hasNext()) {
                val pending = symlinkIterator.next()
                val resolved = resolveArchiveLink(
                    target,
                    pending.destination,
                    pending.linkName
                )

                if (resolved != null && resolved.exists()) {
                    deleteAny(pending.destination)

                    val created = runCatching {
                        Files.createSymbolicLink(
                            pending.destination.toPath(),
                            Paths.get(pending.linkName)
                        )
                    }.isSuccess

                    if (!created) {
                        copyFileOrDirectory(resolved, pending.destination)
                    }

                    symlinkIterator.remove()
                    progress = true
                }
            }

            val hardIterator = pendingHardLinks.iterator()
            while (hardIterator.hasNext()) {
                val pending = hardIterator.next()
                val source = safeTarget(
                    target,
                    normalizeArchivePath(pending.linkName)
                )

                if (source.isFile) {
                    copyFileOrDirectory(source, pending.destination)
                    hardIterator.remove()
                    progress = true
                }
            }

            if (!progress) return@repeat
        }

        // A dangling symlink is legal in Linux, so if the Android filesystem
        // refuses real symlinks and the target is unavailable, leave it absent
        // rather than creating a bogus zero-byte file.
        pendingSymlinks.forEach { pending ->
            pending.destination.delete()
        }

        pendingHardLinks.forEach { pending ->
            if (pending.destination.length() == 0L) {
                pending.destination.delete()
            }
        }
    }

    private fun normalizeArchivePath(raw: String): String {
        val normalized = raw
            .replace('\\', '/')
            .trimStart('/')

        val parts = normalized.split('/')
            .filter { it.isNotEmpty() && it != "." }

        require(parts.none { it == ".." }) {
            "Unsafe archive path: $raw"
        }

        return parts.joinToString("/")
    }

    private fun safeTarget(base: File, name: String): File {
        val root = base.canonicalFile
        val candidate = File(root, name).canonicalFile

        require(
            candidate.path == root.path ||
                candidate.path.startsWith(root.path + File.separator)
        ) {
            "Unsafe archive path: $name"
        }

        return candidate
    }

    private fun resolveArchiveLink(
        base: File,
        linkFile: File,
        linkName: String
    ): File? {
        return runCatching {
            val raw = Paths.get(linkName)
            val resolved = if (raw.isAbsolute) {
                base.toPath().resolve(raw.toString().removePrefix("/"))
            } else {
                linkFile.parentFile.toPath().resolve(raw)
            }

            val candidate = resolved.normalize().toFile().canonicalFile
            val root = base.canonicalFile

            require(
                candidate.path == root.path ||
                    candidate.path.startsWith(root.path + File.separator)
            )

            candidate
        }.getOrNull()
    }

    private fun deleteAny(file: File) {
        if (file.exists() || Files.isSymbolicLink(file.toPath())) {
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }

    private fun copyFileOrDirectory(source: File, destination: File) {
        if (source.isDirectory && !Files.isSymbolicLink(source.toPath())) {
            destination.mkdirs()
            source.listFiles()?.forEach { child ->
                copyFileOrDirectory(child, File(destination, child.name))
            }
        } else if (source.isFile) {
            destination.parentFile?.mkdirs()
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            destination.setReadable(source.canRead(), false)
            destination.setWritable(source.canWrite(), false)
            destination.setExecutable(source.canExecute(), false)
        }
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable((mode and 0b100_100_100) != 0, false)
        file.setWritable((mode and 0b010_010_010) != 0, false)
        file.setExecutable((mode and 0b001_001_001) != 0, false)
    }

}
