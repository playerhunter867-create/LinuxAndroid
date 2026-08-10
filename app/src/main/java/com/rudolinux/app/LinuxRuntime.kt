package org.linox.mobile

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry

/**
 * Rootless Linux runtime for LinOx.
 *
 * Android stays the host kernel.
 * PRoot supplies the Linux rootfs view.
 * No root access is required.
 */
class LinuxRuntime(private val context: Context) {

    private val runtimeDir = File(context.filesDir, "linox-runtime")
    private val proot = File(runtimeDir, "proot")
    private val rootfs = File(runtimeDir, "rootfs")
    private val home = File(runtimeDir, "home")
    private val tmp = File(runtimeDir, "tmp")

    private var activeRootfsFile = rootfs

    init {
        installLayout()
        ensureBundledProot()

        val saved = context
            .getSharedPreferences("linox", Context.MODE_PRIVATE)
            .getString("active_rootfs", null)

        if (!saved.isNullOrBlank()) {
            val candidate = runCatching {
                File(saved).canonicalFile
            }.getOrNull()

            if (
                candidate != null &&
                candidate.isDirectory &&
                isAllowedRootfsPath(candidate)
            ) {
                activeRootfsFile = candidate
            }
        }
    }

    fun installLayout(): File {
        runtimeDir.mkdirs()
        rootfs.mkdirs()
        home.mkdirs()
        tmp.mkdirs()
        return runtimeDir
    }

    // -------------------------------------------------------------------------
    // STATUS
    // -------------------------------------------------------------------------

    fun hasProot(): Boolean =
        proot.isFile && proot.canExecute()

    fun isLinuxReady(): Boolean =
        hasProot() &&
            activeRootfsFile.isDirectory &&
            (
                File(activeRootfsFile, "bin/sh").isFile ||
                File(activeRootfsFile, "usr/bin/sh").isFile
            ) &&
            File(activeRootfsFile, "etc").isDirectory

    fun status(): String = when {
        isLinuxReady() ->
            "Linux userspace: READY"

        hasProot() ->
            "PRoot installed — choose a Linux distribution"

        File(activeRootfsFile, "bin/sh").isFile ->
            "Linux rootfs found — PRoot is missing"

        else ->
            "Linux userspace: NOT INSTALLED"
    }

    fun runtimePath(): File = runtimeDir

    fun rootfsPath(): File = activeRootfsFile

    fun activeRootfs(): File = activeRootfsFile

    fun homePath(): File = home

    fun prootPath(): File = proot

    // -------------------------------------------------------------------------
    // ROOTFS SELECTION
    // -------------------------------------------------------------------------

    fun activateRootfs(path: File) {
        val canonical = path.canonicalFile

        require(isAllowedRootfsPath(canonical)) {
            "Rootfs must live inside LinOx app storage"
        }

        require(canonical.isDirectory) {
            "Rootfs does not exist: $canonical"
        }

        require(
            File(canonical, "bin/sh").isFile ||
                File(canonical, "usr/bin/sh").isFile
        ) {
            "Rootfs has no usable /bin/sh or /usr/bin/sh"
        }

        activeRootfsFile = canonical

        context
            .getSharedPreferences("linox", Context.MODE_PRIVATE)
            .edit()
            .putString("active_rootfs", canonical.absolutePath)
            .apply()

        prepareNetworking()
        installLinOxCommands()
    }

    fun resetToDefaultRootfs() {
        activeRootfsFile = rootfs.canonicalFile

        context
            .getSharedPreferences("linox", Context.MODE_PRIVATE)
            .edit()
            .remove("active_rootfs")
            .apply()
    }

    // -------------------------------------------------------------------------
    // PROOT
    // -------------------------------------------------------------------------

    fun installProot(source: Uri) {
        val input = requireNotNull(
            context.contentResolver.openInputStream(source)
        ) {
            "Unable to open PRoot binary"
        }

        input.use {
            installProotStream(it)
        }
    }

    private fun ensureBundledProot() {
        if (hasProot()) return

        runCatching {
            context.assets
                .open("proot-aarch64-static")
                .use { input ->
                    installProotStream(input)
                }
        }
    }

    private fun installProotStream(input: InputStream) {
        val staging = File(runtimeDir, "proot.new")

        if (staging.exists()) {
            staging.delete()
        }

        FileOutputStream(staging).use { output ->
            input.copyTo(output)
        }

        require(staging.length() >= 4096) {
            "Selected PRoot file is too small"
        }

        require(isArm64Elf(staging)) {
            "PRoot is not a 64-bit ARM (AArch64) ELF executable"
        }

        staging.setReadable(true, true)
        staging.setWritable(true, true)
        staging.setExecutable(true, true)

        val test = runCatching {
            ProcessBuilder(
                staging.absolutePath,
                "--version"
            )
                .redirectErrorStream(true)
                .start()
                .also { p ->
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        error("PRoot validation timed out")
                    }
                }
        }.getOrElse { e ->
            staging.delete()
            error(
                "PRoot cannot be executed on this Android device: " +
                    e.message
            )
        }

        if (test.exitValue() != 0) {
            val text = test.inputStream
                .bufferedReader()
                .use { it.readText() }
                .trim()

            staging.delete()

            error(
                "PRoot failed validation" +
                    if (text.isNotEmpty()) ": $text" else ""
            )
        }

        if (proot.exists()) {
            proot.delete()
        }

        check(staging.renameTo(proot)) {
            "Could not activate PRoot"
        }

        proot.setReadable(true, true)
        proot.setExecutable(true, true)
    }

    // -------------------------------------------------------------------------
    // ROOTFS INSTALLATION
    // -------------------------------------------------------------------------

    /**
     * Installs a .tar.gz Linux rootfs.
     *
     * Important:
     * Linux rootfs archives contain both symbolic links and hard links.
     * Android storage can reject some link operations, so links are handled
     * separately and hard links are materialized as regular files.
     */
    fun installRootfsTarGz(
        source: Uri,
        onProgress: (String) -> Unit = {}
    ) {
        val staging = File(runtimeDir, "rootfs.new")

        if (staging.exists()) {
            staging.deleteRecursively()
        }

        staging.mkdirs()

        val pendingSymlinks = mutableListOf<PendingSymlink>()
        val pendingHardlinks = mutableListOf<PendingHardlink>()

        try {
            val raw = requireNotNull(
                context.contentResolver.openInputStream(source)
            ) {
                "Unable to open rootfs archive"
            }

            raw.use { input ->

                GZIPInputStream(input.buffered()).use { gzip ->

                    TarArchiveInputStream(gzip).use { tar ->

                        var entry: TarArchiveEntry? = tar.nextTarEntry
                        var count = 0

                        while (entry != null) {

                            val current = entry
                                ?: break

                            val name = normalizeArchiveName(
                                current.name
                            )

                            if (name.isEmpty()) {
                                entry = tar.nextTarEntry
                                continue
                            }

                            val target = safeTarget(
                                staging,
                                name
                            )

                            when {

                                // -------------------------------------------------
                                // DIRECTORY
                                // -------------------------------------------------

                                current.isDirectory -> {
                                    target.mkdirs()
                                    applyMode(
                                        target,
                                        current.mode
                                    )
                                }

                                // -------------------------------------------------
                                // SYMBOLIC LINK
                                // -------------------------------------------------

                                current.isSymbolicLink -> {
                                    deleteAny(target)

                                    target.parentFile?.mkdirs()

                                    pendingSymlinks += PendingSymlink(
                                        target = target,
                                        linkName = current.linkName,
                                        entryName = name
                                    )
                                }

                                // -------------------------------------------------
                                // HARD LINK
                                // -------------------------------------------------

                                current.isLink -> {
                                    deleteAny(target)

                                    target.parentFile?.mkdirs()

                                    pendingHardlinks += PendingHardlink(
                                        target = target,
                                        linkName = current.linkName,
                                        entryName = name
                                    )
                                }

                                // -------------------------------------------------
                                // REGULAR FILE
                                // -------------------------------------------------

                                current.isFile -> {
                                    deleteAny(target)

                                    target.parentFile?.mkdirs()

                                    FileOutputStream(target).use { output ->
                                        tar.copyTo(output)
                                    }

                                    applyMode(
                                        target,
                                        current.mode
                                    )
                                }

                                // -------------------------------------------------
                                // OTHER TAR ENTRY
                                // -------------------------------------------------

                                else -> {
                                    // Device nodes, FIFOs, etc. cannot safely be
                                    // created inside normal Android app storage.
                                    // Skip them instead of aborting the entire
                                    // rootfs installation.
                                }
                            }

                            count++

                            if (count % 250 == 0) {
                                onProgress(
                                    "Extracted $count files…"
                                )
                            }

                            entry = tar.nextTarEntry
                        }
                    }
                }
            }

            onProgress("Restoring hard links…")

            restoreHardlinks(
                staging,
                pendingHardlinks
            )

            onProgress("Restoring symbolic links…")

            restoreSymlinks(
                staging,
                pendingSymlinks
            )

            // -------------------------------------------------------------
            // ROOTFS VALIDATION
            // -------------------------------------------------------------

            require(
                File(staging, "bin/sh").isFile ||
                    File(staging, "usr/bin/sh").isFile
            ) {
                "Invalid rootfs: no /bin/sh or /usr/bin/sh"
            }

            require(
                File(staging, "etc").isDirectory
            ) {
                "Invalid rootfs: /etc was not found"
            }

            // Some rootfs archives contain /bin -> usr/bin.
            // Check that /bin or /usr/bin exists.
            require(
                File(staging, "bin").exists() ||
                    File(staging, "usr/bin").exists()
            ) {
                "Invalid rootfs: no usable binary directory"
            }

            // -------------------------------------------------------------
            // ACTIVATE ROOTFS
            // -------------------------------------------------------------

            if (rootfs.exists()) {
                rootfs.deleteRecursively()
            }

            check(staging.renameTo(rootfs)) {
                "Could not activate rootfs"
            }

            activeRootfsFile = rootfs.canonicalFile

            context
                .getSharedPreferences(
                    "linox",
                    Context.MODE_PRIVATE
                )
                .edit()
                .remove("active_rootfs")
                .apply()

            prepareNetworking()
            installLinOxCommands()

            onProgress(
                "Linux rootfs installed successfully"
            )

        } catch (e: Exception) {

            staging.deleteRecursively()

            throw e
        }
    }

    // -------------------------------------------------------------------------
    // HARD LINKS
    // -------------------------------------------------------------------------

    private fun restoreHardlinks(
        base: File,
        links: List<PendingHardlink>
    ) {
        for (link in links) {

            val target = safeTarget(
                base,
                normalizeArchiveName(
                    relativeArchivePath(
                        base,
                        link.linkName
                    )
                )
            )

            val destination = link.target

            if (!target.exists()) {
                // Hard-link target may itself be another hard-link.
                // Nothing to do yet; a later pass may resolve it.
                continue
            }

            deleteAny(destination)
            destination.parentFile?.mkdirs()

            copyFileOrDirectory(
                target,
                destination
            )
        }

        // Second pass handles chains of hard links.
        for (link in links) {

            if (link.target.exists()) {
                continue
            }

            val normalized = normalizeLinkTarget(
                link.target,
                link.linkName,
                base
            )

            val source = safeTarget(
                base,
                normalized
            )

            if (source.exists()) {
                link.target.parentFile?.mkdirs()

                copyFileOrDirectory(
                    source,
                    link.target
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // SYMBOLIC LINKS
    // -------------------------------------------------------------------------

    private fun restoreSymlinks(
        base: File,
        links: List<PendingSymlink>
    ) {
        // Multiple passes are intentional.
        // Linux rootfs archives frequently contain chains such as:
        //
        // /bin -> usr/bin
        // /usr/bin/sh -> dash
        //
        // We try to create real links first. If Android refuses a link,
        // we materialize the target instead.
        repeat(3) {

            for (link in links) {

                if (
                    Files.isSymbolicLink(
                        link.target.toPath()
                    )
                ) {
                    continue
                }

                if (link.target.exists()) {
                    continue
                }

                link.target.parentFile?.mkdirs()

                val resolved = resolveLinkTarget(
                    base,
                    link.target,
                    link.linkName
                )

                val created = runCatching {
                    createSymbolicLinkCompat(
                        link.target,
                        link.linkName
                    )
                }.isSuccess

                if (created) {
                    continue
                }

                // Android may reject symlink creation on some filesystems.
                // If the target already exists, materialize it.
                if (resolved != null && resolved.exists()) {

                    runCatching {
                        copyFileOrDirectory(
                            resolved,
                            link.target
                        )
                    }
                }
            }
        }
    }

    private fun createSymbolicLinkCompat(
        target: File,
        linkName: String
    ) {
        require(linkName.isNotEmpty()) {
            "Empty symlink target"
        }

        deleteAny(target)

        target.parentFile?.mkdirs()

        Files.createSymbolicLink(
            target.toPath(),
            Paths.get(linkName)
        )
    }

    // -------------------------------------------------------------------------
    // LINK RESOLUTION
    // -------------------------------------------------------------------------

    private fun resolveLinkTarget(
        base: File,
        linkFile: File,
        linkName: String
    ): File? {

        return runCatching {

            val rawTarget = Paths.get(linkName)

            val resolved: Path =
                if (rawTarget.isAbsolute) {
                    base.toPath().resolve(
                        rawTarget.toString()
                            .removePrefix("/")
                    )
                } else {
                    linkFile.parentFile
                        .toPath()
                        .resolve(rawTarget)
                }

            val canonical = resolved
                .normalize()
                .toFile()

            val baseCanonical =
                base.canonicalFile

            val canonicalPath =
                canonical.canonicalPath

            require(
                canonicalPath == baseCanonical.path ||
                    canonicalPath.startsWith(
                        baseCanonical.path +
                            File.separator
                    )
            ) {
                "Unsafe symlink target: $linkName"
            }

            canonical
        }.getOrNull()
    }

    private fun normalizeLinkTarget(
        linkFile: File,
        linkName: String,
        base: File
    ): String {

        val resolved = resolveLinkTarget(
            base,
            linkFile,
            linkName
        ) ?: return ""

        val basePath =
            base.canonicalPath

        val resolvedPath =
            resolved.canonicalPath

        return if (
            resolvedPath == basePath
        ) {
            ""
        } else {
            resolvedPath
                .removePrefix(
                    basePath + File.separator
                )
        }
    }

    private fun relativeArchivePath(
        base: File,
        path: String
    ): String {
        if (path.startsWith("/")) {
            return path.removePrefix("/")
        }

        return path
    }

    // -------------------------------------------------------------------------
    // COPY HELPERS
    // -------------------------------------------------------------------------

    private fun copyFileOrDirectory(
        source: File,
        destination: File
    ) {

        if (source.isDirectory) {

            destination.mkdirs()

            source.listFiles()?.forEach { child ->

                val target =
                    File(destination, child.name)

                copyFileOrDirectory(
                    child,
                    target
                )
            }

        } else if (source.isFile) {

            destination.parentFile?.mkdirs()

            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }

            destination.setReadable(
                source.canRead(),
                false
            )

            destination.setWritable(
                source.canWrite(),
                false
            )

            destination.setExecutable(
                source.canExecute(),
                false
            )
        }
    }

    // -------------------------------------------------------------------------
    // NETWORK
    // -------------------------------------------------------------------------

    /**
     * Android network is reused by PRoot.
     * This only supplies DNS inside the guest.
     */
    fun prepareNetworking() {

        if (!activeRootfsFile.isDirectory) {
            return
        }

        val etc =
            File(activeRootfsFile, "etc")

        etc.mkdirs()

        val resolv =
            File(etc, "resolv.conf")

        val dns = listOf(
            readAndroidProperty("net.dns1"),
            readAndroidProperty("net.dns2"),
            readAndroidProperty("net.dns3"),
            readAndroidProperty("net.dns4")
        )
            .filter {
                it.isNotBlank() &&
                    it != "0.0.0.0"
            }
            .distinct()

        val servers =
            if (dns.isNotEmpty()) {
                dns
            } else {
                listOf(
                    "1.1.1.1",
                    "8.8.8.8"
                )
            }

        runCatching {

            if (
                Files.isSymbolicLink(
                    resolv.toPath()
                ) ||
                resolv.exists()
            ) {
                resolv.delete()
            }

            resolv.writeText(
                servers.joinToString("\n") {
                    "nameserver $it"
                } + "\n"
            )
        }

        File(
            etc,
            "hostname"
        ).writeText("linox\n")
    }

    // -------------------------------------------------------------------------
    // DEVELOPMENT TOOLS
    // -------------------------------------------------------------------------

    fun bootstrapDevelopmentTools(
        packageManager: String
    ) {

        check(isLinuxReady()) {
            "Linux rootfs is not ready"
        }

        prepareNetworking()

        val command =
            when (packageManager.lowercase()) {

                "apt" ->
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "apt-get update -y && " +
                        "apt-get install -y " +
                        "python3 python3-pip nano git curl wget " +
                        "ca-certificates bash"

                "apk" ->
                    "apk update && " +
                        "apk add --no-cache " +
                        "python3 py3-pip nano git curl wget " +
                        "ca-certificates bash"

                "dnf" ->
                    "dnf -y install " +
                        "python3 python3-pip nano git curl wget " +
                        "ca-certificates bash"

                "pacman" ->
                    "pacman -Sy --noconfirm " +
                        "python python-pip nano git curl wget " +
                        "ca-certificates bash"

                "zypper" ->
                    "zypper --non-interactive refresh && " +
                        "zypper --non-interactive install -y " +
                        "python3 python3-pip nano git curl wget " +
                        "ca-certificates bash"

                else ->
                    "true"
            }

        val result =
            execute(
                command,
                20 * 60
            )

        require(result.exitCode == 0) {
            "Developer tools installation failed " +
                "(exit ${result.exitCode}): " +
                result.output.takeLast(4000)
        }

        execute(
            "if command -v python3 >/dev/null 2>&1 && " +
                "! command -v python >/dev/null 2>&1; then " +
                "ln -s \"$(command -v python3)\" " +
                "/usr/local/bin/python 2>/dev/null || true; fi",
            30
        )

        execute(
            "if command -v pip3 >/dev/null 2>&1 && " +
                "! command -v pip >/dev/null 2>&1; then " +
                "ln -s \"$(command -v pip3)\" " +
                "/usr/local/bin/pip 2>/dev/null || true; fi",
            30
        )

        installLinOxCommands()
    }

    // -------------------------------------------------------------------------
    // LINOX COMMANDS
    // -------------------------------------------------------------------------

    fun installLinOxCommands() {

        if (!activeRootfsFile.isDirectory) {
            return
        }

        prepareNetworking()

        val bin =
            File(
                activeRootfsFile,
                "usr/local/bin"
            )

        bin.mkdirs()

        File(bin, "linox-info").apply {

            writeText(
                """
                #!/bin/sh
                echo "LinOx Mobile 0.9"
                echo "Userspace: ${'$'}(cat /etc/os-release 2>/dev/null | sed -n '1p' || echo Linux)"
                echo "Architecture: ${'$'}(uname -m)"
                echo "Host kernel: ${'$'}(uname -sr)"
                echo "Python: ${'$'}(python --version 2>&1 || python3 --version 2>&1 || echo not-installed)"
                echo "Git: ${'$'}(git --version 2>/dev/null || echo not-installed)"
                echo "Nano: ${'$'}(nano --version 2>/dev/null | head -n 1 || echo not-installed)"
                """.trimIndent() + "\n"
            )

            setExecutable(
                true,
                false
            )
        }

        File(bin, "linox-doctor").apply {

            writeText(
                """
                #!/bin/sh
                ok=0
                command -v sh >/dev/null 2>&1 && echo "[OK] shell" || { echo "[FAIL] shell"; ok=1; }
                command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 && echo "[OK] Python" || echo "[WARN] Python not installed"
                command -v git >/dev/null 2>&1 && echo "[OK] Git" || echo "[WARN] Git not installed"
                test -f /etc/resolv.conf && echo "[OK] DNS configuration" || echo "[WARN] /etc/resolv.conf missing"
                printf "Kernel: "
                uname -sr
                printf "Arch: "
                uname -m
                exit ${'$'}ok
                """.trimIndent() + "\n"
            )

            setExecutable(
                true,
                false
            )
        }

        File(bin, "linox").apply {

            writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  info) linox-info ;;
                  doctor) linox-doctor ;;
                  shell) exec /bin/sh ;;
                  *) echo "LinOx commands: info | doctor | shell" ;;
                esac
                """.trimIndent() + "\n"
            )

            setExecutable(
                true,
                false
            )
        }

        File(bin, "ll").apply {

            if (!exists()) {

                writeText(
                    "#!/bin/sh\n" +
                        "ls -lah \"${'$'}@\"\n"
                )

                setExecutable(
                    true,
                    false
                )
            }
        }

        val profile =
            File(
                activeRootfsFile,
                "etc/profile.d/linox.sh"
            )

        profile.parentFile?.mkdirs()

        profile.writeText(
            """
            export TERM="${'$'}{TERM:-xterm-256color}"
            export PATH="/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin:${'$'}PATH"
            export LANG="${'$'}{LANG:-C.UTF-8}"
            """.trimIndent() + "\n"
        )
    }

    // -------------------------------------------------------------------------
    // INTERACTIVE TERMINAL
    // -------------------------------------------------------------------------

    fun startInteractivePty(
        onText: (String) -> Unit
    ): PtySession {

        check(isLinuxReady()) {
            "Install an ARM64 PRoot and a Linux ARM64 distribution first."
        }

        prepareNetworking()
        installLinOxCommands()

        val shell =
            when {

                File(
                    activeRootfsFile,
                    "bin/bash"
                ).exists() ->
                    listOf(
                        "/bin/bash",
                        "--login"
                    )

                File(
                    activeRootfsFile,
                    "usr/bin/bash"
                ).exists() ->
                    listOf(
                        "/usr/bin/bash",
                        "--login"
                    )

                File(
                    activeRootfsFile,
                    "bin/sh"
                ).exists() ->
                    listOf(
                        "/bin/sh",
                        "-l"
                    )

                else ->
                    listOf(
                        "/usr/bin/sh",
                        "-l"
                    )
            }

        val env =
            linkedMapOf(
                "HOME" to "/root",
                "TERM" to "xterm-256color",
                "TMPDIR" to "/tmp",
                "LANG" to "C.UTF-8",
                "LC_ALL" to "C.UTF-8",
                "PROOT_NO_SECCOMP" to "1",
                "LINOX_ANDROID_HOME" to home.absolutePath,
                "PATH" to
                    "/usr/local/bin:" +
                    "/usr/local/sbin:" +
                    "/usr/bin:" +
                    "/usr/sbin:" +
                    "/bin:/sbin"
            )

        val session =
            PtySession.start(
                proot.absolutePath,
                baseProotArgs() + shell,
                env,
                home.absolutePath
            )

        session.readLoop(
            onText
        ) {}

        return session
    }

    // -------------------------------------------------------------------------
    // COMMAND EXECUTION
    // -------------------------------------------------------------------------

    fun execute(
        command: String,
        timeoutSeconds: Long = 60
    ): CommandResult {

        if (command.isBlank()) {
            return CommandResult(
                "",
                0
            )
        }

        val linux =
            isLinuxReady()

        if (linux) {
            prepareNetworking()
            installLinOxCommands()
        }

        val args =
            if (linux) {

                baseProotArgs() +
                    listOf(
                        "/bin/sh",
                        "-lc",
                        command
                    )

            } else {

                listOf(
                    "/system/bin/sh",
                    "-lc",
                    command
                )
            }

        return try {

            val process =
                ProcessBuilder(args)
                    .directory(
                        if (linux)
                            home
                        else
                            context.filesDir
                    )
                    .redirectErrorStream(true)
                    .apply {

                        environment()["HOME"] =
                            if (linux)
                                "/root"
                            else
                                home.absolutePath

                        environment()["LINOX_ANDROID_HOME"] =
                            home.absolutePath

                        environment()["TERM"] =
                            "xterm-256color"

                        environment()["PROOT_NO_SECCOMP"] =
                            "1"

                        environment()["TMPDIR"] =
                            if (linux)
                                "/tmp"
                            else
                                tmp.absolutePath

                        if (linux) {

                            environment()["PATH"] =
                                "/usr/local/bin:" +
                                "/usr/local/sbin:" +
                                "/usr/bin:" +
                                "/usr/sbin:" +
                                "/bin:/sbin"
                        }
                    }
                    .start()

            val output =
                StringBuilder()

            val readerThread =
                Thread {

                    runCatching {

                        process.inputStream
                            .bufferedReader()
                            .use { reader ->

                                val buffer =
                                    CharArray(8192)

                                while (true) {

                                    val n =
                                        reader.read(buffer)

                                    if (n < 0) {
                                        break
                                    }

                                    synchronized(output) {
                                        output.append(
                                            buffer,
                                            0,
                                            n
                                        )
                                    }
                                }
                            }
                    }
                }.apply {

                    name =
                        "LinOx-command-output"

                    isDaemon = true

                    start()
                }

            val finished =
                process.waitFor(
                    timeoutSeconds,
                    TimeUnit.SECONDS
                )

            if (!finished) {

                process.destroyForcibly()

                process.waitFor(
                    2,
                    TimeUnit.SECONDS
                )

                readerThread.join(2000)

                CommandResult(
                    synchronized(output) {
                        output.toString()
                    } +
                        "\n[LinOx] command timed out",
                    124
                )

            } else {

                readerThread.join(2000)

                CommandResult(
                    synchronized(output) {
                        output.toString()
                    },
                    process.exitValue()
                )
            }

        } catch (e: Exception) {

            CommandResult(
                "[LinOx] " +
                    "${e.javaClass.simpleName}: " +
                    e.message,
                1
            )
        }
    }

    // -------------------------------------------------------------------------
    // PROOT ARGUMENTS
    // -------------------------------------------------------------------------

    private fun baseProotArgs(): List<String> =
        buildList {

            addAll(
                listOf(
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r",
                    activeRootfsFile.absolutePath
                )
            )

            listOf(
                "/dev",
                "/proc",
                "/sys"
            ).forEach { mount ->

                addAll(
                    listOf(
                        "-b",
                        mount
                    )
                )
            }

            addAll(
                listOf(
                    "-b",
                    "/system/bin:/android-bin"
                )
            )

            addAll(
                listOf(
                    "-b",
                    home.absolutePath + ":/root"
                )
            )

            addAll(
                listOf(
                    "-b",
                    tmp.absolutePath + ":/tmp"
                )
            )

            addAll(
                listOf(
                    "-w",
                    "/root"
                )
            )
        }

    // -------------------------------------------------------------------------
    // ARCHIVE SAFETY
    // -------------------------------------------------------------------------

    private fun normalizeArchiveName(
        raw: String
    ): String {

        val name =
            raw
                .replace('\\', '/')
                .trimStart('/')

        val parts =
            name
                .split('/')
                .filter {
                    it.isNotEmpty() &&
                        it != "."
                }

        require(
            parts.none {
                it == ".."
            }
        ) {
            "Unsafe archive entry: $raw"
        }

        return parts.joinToString("/")
    }

    private fun safeTarget(
        base: File,
        name: String
    ): File {

        val root =
            base.canonicalFile

        val target =
            File(
                root,
                name
            ).canonicalFile

        require(
            target.path == root.path ||
                target.path.startsWith(
                    root.path +
                        File.separator
                )
        ) {
            "Unsafe archive path: $name"
        }

        return target
    }

    // -------------------------------------------------------------------------
    // FILE HELPERS
    // -------------------------------------------------------------------------

    private fun deleteAny(
        file: File
    ) {

        if (
            file.exists() ||
            Files.isSymbolicLink(
                file.toPath()
            )
        ) {
            file.deleteRecursively()
        }
    }

    private fun applyMode(
        file: File,
        mode: Int
    ) {

        file.setReadable(
            (mode and 0b100_100_100) != 0,
            false
        )

        file.setWritable(
            (mode and 0b010_010_010) != 0,
            false
        )

        file.setExecutable(
            (mode and 0b001_001_001) != 0,
            false
        )
    }

    // -------------------------------------------------------------------------
    // PATH / SECURITY
    // -------------------------------------------------------------------------

    private fun isAllowedRootfsPath(
        candidate: File
    ): Boolean {

        val files =
            context.filesDir.canonicalFile

        val path =
            candidate.canonicalFile.path

        return path == files.path ||
            path.startsWith(
                files.path +
                    File.separator
            )
    }

    // -------------------------------------------------------------------------
    // ANDROID PROPERTIES
    // -------------------------------------------------------------------------

    private fun readAndroidProperty(
        name: String
    ): String =
        runCatching {

            Runtime.getRuntime()
                .exec(
                    arrayOf(
                        "/system/bin/getprop",
                        name
                    )
                )
                .inputStream
                .bufferedReader()
                .use {
                    it.readText().trim()
                }

        }.getOrDefault("")

    // -------------------------------------------------------------------------
    // ELF CHECK
    // -------------------------------------------------------------------------

    private fun isArm64Elf(
        file: File
    ): Boolean =
        runCatching {

            RandomAccessFile(
                file,
                "r"
            ).use { raf ->

                val ident =
                    ByteArray(20)

                raf.readFully(
                    ident
                )

                ident[0] ==
                    0x7f.toByte() &&

                    ident[1] ==
                    'E'.code.toByte() &&

                    ident[2] ==
                    'L'.code.toByte() &&

                    ident[3] ==
                    'F'.code.toByte() &&

                    ident[4].toInt() == 2 &&

                    ident[5].toInt() == 1 &&

                    (ident[18].toInt() and 0xff) == 183
            }

        }.getOrDefault(false)

    // -------------------------------------------------------------------------
    // DEVICE INFO
    // -------------------------------------------------------------------------

    fun architecture(): String =
        Build.SUPPORTED_ABIS
            .firstOrNull()
            ?: "unknown"

    fun isArm64Device(): Boolean =
        Build.SUPPORTED_ABIS.any {
            it == "arm64-v8a"
        }

    fun storageUsedBytes(): Long {

        fun dirSize(
            file: File
        ): Long {

            return if (file.isFile) {

                file.length()

            } else {

                file.listFiles()
                    ?.sumOf(::dirSize)
                    ?: 0L
            }
        }

        return dirSize(runtimeDir) +
            dirSize(
                File(
                    context.filesDir,
                    "linox-distros"
                )
            )
    }

    // -------------------------------------------------------------------------
    // INTERNAL LINK DATA
    // -------------------------------------------------------------------------

    private data class PendingSymlink(
        val target: File,
        val linkName: String,
        val entryName: String
    )

    private data class PendingHardlink(
        val target: File,
        val linkName: String,
        val entryName: String
    )

    // -------------------------------------------------------------------------
    // RESULT
    // -------------------------------------------------------------------------

    data class CommandResult(
        val output: String,
        val exitCode: Int
    )
}
