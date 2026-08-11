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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * LinOx rootless Linux runtime.
 *
 * Android remains the host kernel.
 * PRoot provides the Linux userspace/rootfs environment.
 *
 * No Android root is required.
 */
class LinuxRuntime(
    private val context: Context
) {

    private val runtimeDir =
        File(context.filesDir, "linox-runtime")

    private val proot =
        File(runtimeDir, "proot")

    private val rootfs =
        File(runtimeDir, "rootfs")

    private val home =
        File(runtimeDir, "home")

    private val tmp =
        File(runtimeDir, "tmp")

    private var activeRootfsFile: File =
        rootfs

    init {
        installLayout()
        ensureBundledProot()
        restoreActiveRootfs()
    }

    // -------------------------------------------------------------------------
    // LAYOUT
    // -------------------------------------------------------------------------

    private fun installLayout(): File {
        runtimeDir.mkdirs()
        rootfs.mkdirs()
        home.mkdirs()
        tmp.mkdirs()

        return runtimeDir
    }

    private fun restoreActiveRootfs() {

        val saved =
            context
                .getSharedPreferences(
                    "linox",
                    Context.MODE_PRIVATE
                )
                .getString(
                    "active_rootfs",
                    null
                )

        if (saved.isNullOrBlank()) {
            return
        }

        val candidate =
            runCatching {
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

    // -------------------------------------------------------------------------
    // STATUS
    // -------------------------------------------------------------------------

    fun hasProot(): Boolean =
        proot.isFile &&
            proot.canExecute()

    fun isLinuxReady(): Boolean {

        val shell =
            File(
                activeRootfsFile,
                "bin/sh"
            ).isFile ||
                File(
                    activeRootfsFile,
                    "usr/bin/sh"
                ).isFile

        return hasProot() &&
            activeRootfsFile.isDirectory &&
            shell &&
            File(
                activeRootfsFile,
                "etc"
            ).isDirectory
    }

    fun status(): String =
        when {

            isLinuxReady() ->
                "Linux userspace: READY"

            hasProot() ->
                "PRoot installed — choose a Linux distribution"

            activeRootfsFile.isDirectory &&
                File(
                    activeRootfsFile,
                    "etc"
                ).isDirectory ->
                "Linux rootfs found — PRoot is missing"

            else ->
                "Linux userspace: NOT INSTALLED"
        }

    fun runtimePath(): File =
        runtimeDir

    fun rootfsPath(): File =
        activeRootfsFile

    fun activeRootfs(): File =
        activeRootfsFile

    fun homePath(): File =
        home

    fun prootPath(): File =
        proot

    // -------------------------------------------------------------------------
    // ROOTFS SELECTION
    // -------------------------------------------------------------------------

    fun activateRootfs(
        path: File
    ) {

        val canonical =
            path.canonicalFile

        require(
            isAllowedRootfsPath(canonical)
        ) {
            "Rootfs must live inside LinOx app storage"
        }

        require(
            canonical.isDirectory
        ) {
            "Rootfs does not exist: $canonical"
        }

        require(
            File(
                canonical,
                "bin/sh"
            ).isFile ||
                File(
                    canonical,
                    "usr/bin/sh"
                ).isFile
        ) {
            "Rootfs has no usable /bin/sh or /usr/bin/sh"
        }

        activeRootfsFile =
            canonical

        context
            .getSharedPreferences(
                "linox",
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                "active_rootfs",
                canonical.absolutePath
            )
            .apply()

        prepareNetworking()
        installLinOxCommands()
    }

    /**
     * Compatibility method used by LinuxTerminalActivity.
     *
     * The distro id is currently used by DistroManager outside this runtime.
     * The runtime verifies that the currently active rootfs is usable.
     */
    fun activateInstalledDistro(
        distroId: String
    ): Boolean {

        val candidateDirectories =
            listOf(
                File(
                    context.filesDir,
                    "linox-distros/$distroId/rootfs"
                ),
                File(
                    context.filesDir,
                    "linox-distros/$distroId"
                ),
                File(
                    runtimeDir,
                    "distros/$distroId/rootfs"
                ),
                File(
                    runtimeDir,
                    "distros/$distroId"
                )
            )

        val candidate =
            candidateDirectories.firstOrNull { directory ->

                directory.isDirectory &&
                    isUsableRootfs(directory)
            }

        if (candidate != null) {

            activateRootfs(candidate)

            return true
        }

        if (
            isUsableRootfs(rootfs)
        ) {

            activeRootfsFile =
                rootfs.canonicalFile

            prepareNetworking()
            installLinOxCommands()

            return true
        }

        return false
    }

    fun resetToDefaultRootfs() {

        activeRootfsFile =
            rootfs.canonicalFile

        context
            .getSharedPreferences(
                "linox",
                Context.MODE_PRIVATE
            )
            .edit()
            .remove("active_rootfs")
            .apply()
    }

    // -------------------------------------------------------------------------
    // PROOT INSTALLATION
    // -------------------------------------------------------------------------

    fun installProot(
        source: Uri
    ) {

        val input: InputStream =
            requireNotNull(
                context.contentResolver.openInputStream(
                    source
                )
            ) {
                "Unable to open PRoot binary"
            }

        input.use { stream ->
            installProotStream(stream)
        }
    }

    private fun ensureBundledProot() {

        if (hasProot()) {
            return
        }

        runCatching {

            context.assets
                .open(
                    "proot-aarch64-static"
                )
                .use { input ->
                    installProotStream(input)
                }
        }
    }

    private fun installProotStream(
        input: InputStream
    ) {

        runtimeDir.mkdirs()

        val staging =
            File(
                runtimeDir,
                "proot.new"
            )

        if (staging.exists()) {
            staging.delete()
        }

        FileOutputStream(
            staging
        ).use { output ->
            input.copyTo(output)
        }

        require(
            staging.length() >= 4096
        ) {
            "Selected PRoot file is too small"
        }

        require(
            isArm64Elf(staging)
        ) {
            "PRoot is not a 64-bit ARM (AArch64) ELF executable"
        }

        staging.setReadable(
            true,
            true
        )

        staging.setWritable(
            true,
            true
        )

        staging.setExecutable(
            true,
            true
        )

        val process =
            runCatching {

                ProcessBuilder(
                    staging.absolutePath,
                    "--version"
                )
                    .redirectErrorStream(true)
                    .start()

            }.getOrElse { error ->

                staging.delete()

                throw IllegalStateException(
                    "PRoot cannot be executed: " +
                        (
                            error.message
                                ?: "unknown error"
                            )
                )
            }

        if (
            !process.waitFor(
                5,
                TimeUnit.SECONDS
            )
        ) {

            process.destroyForcibly()
            staging.delete()

            throw IllegalStateException(
                "PRoot validation timed out"
            )
        }

        val output =
            runCatching {

                process.inputStream
                    .bufferedReader()
                    .use { reader ->
                        reader.readText()
                    }
                    .trim()

            }.getOrDefault("")

        if (
            process.exitValue() != 0
        ) {

            staging.delete()

            throw IllegalStateException(
                "PRoot validation failed" +
                    if (output.isNotEmpty()) {
                        ": $output"
                    } else {
                        ""
                    }
            )
        }

        if (proot.exists()) {
            proot.delete()
        }

        require(
            staging.renameTo(proot)
        ) {
            "Could not activate PRoot"
        }

        proot.setReadable(
            true,
            true
        )

        proot.setExecutable(
            true,
            true
        )
    }

    // -------------------------------------------------------------------------
    // ROOTFS TAR.GZ INSTALLATION
    // -------------------------------------------------------------------------

    fun installRootfsTarGz(
        source: Uri,
        onProgress: (String) -> Unit = {}
    ) {

        val staging =
            File(
                runtimeDir,
                "rootfs.new"
            )

        if (staging.exists()) {
            staging.deleteRecursively()
        }

        staging.mkdirs()

        val symlinks =
            mutableListOf<PendingSymlink>()

        val hardlinks =
            mutableListOf<PendingHardlink>()

        try {

            val raw: InputStream =
                requireNotNull(
                    context.contentResolver.openInputStream(
                        source
                    )
                ) {
                    "Unable to open rootfs archive"
                }

            raw.use { input ->

                GZIPInputStream(
                    input.buffered()
                ).use { gzip ->

                    TarArchiveInputStream(
                        gzip
                    ).use { tar ->

                        var entry:
                            TarArchiveEntry? =
                            tar.nextTarEntry

                        var count = 0

                        while (entry != null) {

                            val current =
                                entry
                                    ?: break

                            val name =
                                normalizeArchiveName(
                                    current.name
                                )

                            if (name.isEmpty()) {

                                entry =
                                    tar.nextTarEntry

                                continue
                            }

                            val target =
                                safeTarget(
                                    staging,
                                    name
                                )

                            when {

                                current.isDirectory -> {

                                    target.mkdirs()

                                    applyMode(
                                        target,
                                        current.mode
                                    )
                                }

                                current.isSymbolicLink -> {

                                    deleteAny(
                                        target
                                    )

                                    target.parentFile?.mkdirs()

                                    symlinks +=
                                        PendingSymlink(
                                            target =
                                                target,
                                            linkName =
                                                current.linkName,
                                            entryName =
                                                name
                                        )
                                }

                                current.isLink -> {

                                    deleteAny(
                                        target
                                    )

                                    target.parentFile?.mkdirs()

                                    hardlinks +=
                                        PendingHardlink(
                                            target =
                                                target,
                                            linkName =
                                                current.linkName,
                                            entryName =
                                                name
                                        )
                                }

                                current.isFile -> {

                                    deleteAny(
                                        target
                                    )

                                    target.parentFile?.mkdirs()

                                    FileOutputStream(
                                        target
                                    ).use { output ->
                                        tar.copyTo(output)
                                    }

                                    applyMode(
                                        target,
                                        current.mode
                                    )
                                }

                                else -> {
                                    // Special device/FIFO entries
                                    // are intentionally skipped.
                                }
                            }

                            count++

                            if (
                                count % 250 == 0
                            ) {

                                onProgress(
                                    "Extracted $count files..."
                                )
                            }

                            entry =
                                tar.nextTarEntry
                        }
                    }
                }
            }

            onProgress(
                "Restoring hard links..."
            )

            restoreHardlinks(
                staging,
                hardlinks
            )

            onProgress(
                "Restoring symbolic links..."
            )

            restoreSymlinks(
                staging,
                symlinks
            )

            validateRootfs(
                staging
            )

            if (rootfs.exists()) {
                rootfs.deleteRecursively()
            }

            require(
                staging.renameTo(rootfs)
            ) {
                "Could not activate rootfs"
            }

            activeRootfsFile =
                rootfs.canonicalFile

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

        } catch (error: Exception) {

            staging.deleteRecursively()

            throw error
        }
    }

    // -------------------------------------------------------------------------
    // ROOTFS VALIDATION
    // -------------------------------------------------------------------------

    private fun validateRootfs(
        directory: File
    ) {

        require(
            directory.isDirectory
        ) {
            "Invalid rootfs directory"
        }

        require(
            File(
                directory,
                "etc"
            ).isDirectory
        ) {
            "Invalid rootfs: /etc was not found"
        }

        require(
            isUsableRootfs(directory)
        ) {
            "Invalid rootfs: no /bin/sh or /usr/bin/sh"
        }
    }

    private fun isUsableRootfs(
        directory: File
    ): Boolean {

        if (!directory.isDirectory) {
            return false
        }

        val hasEtc =
            File(
                directory,
                "etc"
            ).isDirectory

        val hasShell =
            File(
                directory,
                "bin/sh"
            ).isFile ||
                File(
                    directory,
                    "usr/bin/sh"
                ).isFile

        return hasEtc && hasShell
    }

    // -------------------------------------------------------------------------
    // HARD LINKS
    // -------------------------------------------------------------------------

    private fun restoreHardlinks(
        base: File,
        links: List<PendingHardlink>
    ) {

        for (link in links) {

            val source =
                resolveLinkTarget(
                    base,
                    link.target,
                    link.linkName
                )

            if (
                source != null &&
                source.exists()
            ) {

                deleteAny(
                    link.target
                )

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

                val resolved =
                    resolveLinkTarget(
                        base,
                        link.target,
                        link.linkName
                    )

                val created =
                    runCatching {

                        createSymbolicLinkCompat(
                            link.target,
                            link.linkName
                        )

                    }.isSuccess

                if (created) {
                    continue
                }

                if (
                    resolved != null &&
                    resolved.exists()
                ) {

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

        require(
            linkName.isNotEmpty()
        ) {
            "Empty symbolic link target"
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

            val raw =
                Paths.get(linkName)

            val resolved: Path =
                if (raw.isAbsolute) {

                    base.toPath().resolve(
                        raw.toString()
                            .removePrefix("/")
                    )

                } else {

                    linkFile.parentFile
                        .toPath()
                        .resolve(raw)
                }

            val normalized =
                resolved.normalize()

            val baseCanonical =
                base.canonicalFile

            val candidate =
                normalized.toFile()

            val candidatePath =
                candidate.canonicalPath

            require(
                candidatePath ==
                    baseCanonical.path ||
                    candidatePath.startsWith(
                        baseCanonical.path +
                            File.separator
                    )
            ) {
                "Unsafe link target: $linkName"
            }

            candidate

        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // COPY
    // -------------------------------------------------------------------------

    private fun copyFileOrDirectory(
        source: File,
        destination: File
    ) {

        if (source.isDirectory) {

            destination.mkdirs()

            source.listFiles()
                ?.forEach { child ->

                    copyFileOrDirectory(
                        child,
                        File(
                            destination,
                            child.name
                        )
                    )
                }

            return
        }

        if (source.isFile) {

            destination.parentFile?.mkdirs()

            source.inputStream().use { input ->

                FileOutputStream(
                    destination
                ).use { output ->

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
    // NETWORKING
    // -------------------------------------------------------------------------

    fun prepareNetworking() {

        if (!activeRootfsFile.isDirectory) {
            return
        }

        val etc =
            File(
                activeRootfsFile,
                "etc"
            )

        etc.mkdirs()

        val resolv =
            File(
                etc,
                "resolv.conf"
            )

        val dns =
            listOf(
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
                )
            ) {

                Files.delete(
                    resolv.toPath()
                )

            } else if (
                resolv.exists()
            ) {

                resolv.delete()
            }

            resolv.writeText(
                servers.joinToString(
                    separator = "\n"
                ) {
                    "nameserver $it"
                } + "\n"
            )
        }

        File(
            etc,
            "hostname"
        ).writeText(
            "linox\n"
        )
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
            when (
                packageManager.lowercase()
            ) {

                "apt" ->
                    """
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update -y
                    apt-get install -y python3 python3-pip nano git curl wget ca-certificates bash
                    """.trimIndent()

                "apk" ->
                    """
                    apk update
                    apk add --no-cache python3 py3-pip nano git curl wget ca-certificates bash
                    """.trimIndent()

                "dnf" ->
                    """
                    dnf -y install python3 python3-pip nano git curl wget ca-certificates bash
                    """.trimIndent()

                "pacman" ->
                    """
                    pacman -Sy --noconfirm python python-pip nano git curl wget ca-certificates bash
                    """.trimIndent()

                "zypper" ->
                    """
                    zypper --non-interactive refresh
                    zypper --non-interactive install -y python3 python3-pip nano git curl wget ca-certificates bash
                    """.trimIndent()

                else ->
                    "true"
            }

        val result =
            execute(
                command,
                20 * 60
            )

        require(
            result.exitCode == 0
        ) {
            "Developer tools installation failed " +
                "(exit ${result.exitCode}): " +
                result.output.takeLast(4000)
        }

        execute(
            """
            if command -v python3 >/dev/null 2>&1 &&
               ! command -v python >/dev/null 2>&1; then
                mkdir -p /usr/local/bin
                ln -sf "$(command -v python3)" /usr/local/bin/python
            fi
            """.trimIndent(),
            30
        )

        execute(
            """
            if command -v pip3 >/dev/null 2>&1 &&
               ! command -v pip >/dev/null 2>&1; then
                mkdir -p /usr/local/bin
                ln -sf "$(command -v pip3)" /usr/local/bin/pip
            fi
            """.trimIndent(),
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

        val bin =
            File(
                activeRootfsFile,
                "usr/local/bin"
            )

        bin.mkdirs()

        File(
            bin,
            "linox-info"
        ).apply {

            writeText(
                """
                #!/bin/sh
                echo "LinOx Mobile"
                echo "Userspace: ${'$'}(cat /etc/os-release 2>/dev/null | sed -n '1p' || echo Linux)"
                echo "Architecture: ${'$'}(uname -m)"
                echo "Host kernel: ${'$'}(uname -sr)"
                echo "Python: ${'$'}(python --version 2>&1 || python3 --version 2>&1 || echo not-installed)"
                echo "Git: ${'$'}(git --version 2>/dev/null || echo not-installed)"
                """.trimIndent() + "\n"
            )

            setExecutable(
                true,
                false
            )
        }

        File(
            bin,
            "linox-doctor"
        ).apply {

            writeText(
                """
                #!/bin/sh

                ok=0

                if command -v sh >/dev/null 2>&1; then
                    echo "[OK] shell"
                else
                    echo "[FAIL] shell"
                    ok=1
                fi

                if command -v python3 >/dev/null 2>&1 ||
                   command -v python >/dev/null 2>&1; then
                    echo "[OK] Python"
                else
                    echo "[WARN] Python not installed"
                fi

                if command -v git >/dev/null 2>&1; then
                    echo "[OK] Git"
                else
                    echo "[WARN] Git not installed"
                fi

                if [ -f /etc/resolv.conf ]; then
                    echo "[OK] DNS configuration"
                else
                    echo "[WARN] /etc/resolv.conf missing"
                fi

                echo "Kernel: ${'$'}(uname -sr)"
                echo "Arch: ${'$'}(uname -m)"

                exit "${'$'}ok"
                """.trimIndent() + "\n"
            )

            setExecutable(
                true,
                false
            )
        }

        File(
            bin,
            "linox"
        ).apply {

            writeText(
                """
                #!/bin/sh

                case "${'$'}1" in
                    info)
                        exec linox-info
                        ;;
                    doctor)
                        exec linox-doctor
                        ;;
                    shell)
                        exec /bin/sh
                        ;;
                    *)
                        echo "LinOx commands: info | doctor | shell"
                        ;;
                esac
                """.trimIndent() + "\n"
            )

            setExecutable(
                true,
                false
            )
        }

        val ll =
            File(
                bin,
                "ll"
            )

        if (!ll.exists()) {

            ll.writeText(
                """
                #!/bin/sh
                ls -lah "${'$'}@"
                """.trimIndent() + "\n"
            )

            ll.setExecutable(
                true,
                false
            )
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
    // INTERACTIVE PTY
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
                ).isFile -> {

                    listOf(
                        "/bin/bash",
                        "--login"
                    )
                }

                File(
                    activeRootfsFile,
                    "usr/bin/bash"
                ).isFile -> {

                    listOf(
                        "/usr/bin/bash",
                        "--login"
                    )
                }

                File(
                    activeRootfsFile,
                    "bin/sh"
                ).isFile -> {

                    listOf(
                        "/bin/sh",
                        "-l"
                    )
                }

                else -> {

                    listOf(
                        "/usr/bin/sh",
                        "-l"
                    )
                }
            }

        val environment =
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
                executable =
                    proot.absolutePath,
                args =
                    baseProotArgs() + shell,
                environment =
                    environment,
                workingDirectory =
                    home.absolutePath
            )

        session.readLoop(
            onText
        )

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
                output = "",
                exitCode = 0
            )
        }

        val linux =
            isLinuxReady()

        if (linux) {

            prepareNetworking()
            installLinOxCommands()
        }

        val shellPath =
            if (linux) {

                when {

                    File(
                        activeRootfsFile,
                        "bin/sh"
                    ).isFile ->
                        "/bin/sh"

                    File(
                        activeRootfsFile,
                        "usr/bin/sh"
                    ).isFile ->
                        "/usr/bin/sh"

                    else ->
                        "/bin/sh"
                }

            } else {

                "/system/bin/sh"
            }

        val args =
            if (linux) {

                baseProotArgs() +
                    listOf(
                        shellPath,
                        "-lc",
                        command
                    )

            } else {

                listOf(
                    shellPath,
                    "-lc",
                    command
                )
            }

        return try {

            val builder =
                ProcessBuilder(args)
                    .directory(
                        if (linux) {
                            home
                        } else {
                            context.filesDir
                        }
                    )
                    .redirectErrorStream(true)

            val environment =
                builder.environment()

            environment["HOME"] =
                if (linux) {
                    "/root"
                } else {
                    home.absolutePath
                }

            environment["LINOX_ANDROID_HOME"] =
                home.absolutePath

            environment["TERM"] =
                "xterm-256color"

            environment["PROOT_NO_SECCOMP"] =
                "1"

            environment["TMPDIR"] =
                if (linux) {
                    "/tmp"
                } else {
                    tmp.absolutePath
                }

            if (linux) {

                environment["PATH"] =
                    "/usr/local/bin:" +
                        "/usr/local/sbin:" +
                        "/usr/bin:" +
                        "/usr/sbin:" +
                        "/bin:/sbin"
            }

            val process =
                builder.start()

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

                                    val count =
                                        reader.read(buffer)

                                    if (count < 0) {
                                        break
                                    }

                                    if (count > 0) {

                                        synchronized(
                                            output
                                        ) {

                                            output.append(
                                                buffer,
                                                0,
                                                count
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }

            readerThread.name =
                "LinOx-command-output"

            readerThread.isDaemon =
                true

            readerThread.start()

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

                readerThread.join(
                    2000
                )

                CommandResult(
                    output =
                        synchronized(output) {
                            output.toString()
                        } +
                            "\n[LinOx] command timed out",
                    exitCode = 124
                )

            } else {

                readerThread.join(
                    2000
                )

                CommandResult(
                    output =
                        synchronized(output) {
                            output.toString()
                        },
                    exitCode =
                        process.exitValue()
                )
            }

        } catch (error: Exception) {

            CommandResult(
                output =
                    "[LinOx] " +
                        "${error.javaClass.simpleName}: " +
                        (
                            error.message
                                ?: "unknown error"
                            ),
                exitCode = 1
            )
        }
    }

    // -------------------------------------------------------------------------
    // PROOT ARGUMENTS
    // -------------------------------------------------------------------------

    private fun baseProotArgs(): List<String> {

        return buildList {

            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")

            add("-r")
            add(
                activeRootfsFile.absolutePath
            )

            add("-b")
            add("/dev")

            add("-b")
            add("/proc")

            add("-b")
            add("/sys")

            add("-b")
            add("/system/bin:/android-bin")

            add("-b")
            add(
                home.absolutePath +
                    ":/root"
            )

            add("-b")
            add(
                tmp.absolutePath +
                    ":/tmp"
            )

            add("-w")
            add("/root")
        }
    }

    // -------------------------------------------------------------------------
    // ARCHIVE SAFETY
    // -------------------------------------------------------------------------

    private fun normalizeArchiveName(
        raw: String
    ): String {

        val name =
            raw
                .replace(
                    '\\',
                    '/'
                )
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
    // SECURITY
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
    // ANDROID DNS
    // -------------------------------------------------------------------------

    private fun readAndroidProperty(
        name: String
    ): String {

        return runCatching {

            Runtime.getRuntime()
                .exec(
                    arrayOf(
                        "/system/bin/getprop",
                        name
                    )
                )
                .inputStream
                .bufferedReader()
                .use { reader ->
                    reader.readText().trim()
                }

        }.getOrDefault("")
    }

    // -------------------------------------------------------------------------
    // ELF CHECK
    // -------------------------------------------------------------------------

    private fun isArm64Elf(
        file: File
    ): Boolean {

        return runCatching {

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

                    (ident[18].toInt() and 0xff) ==
                    183
            }

        }.getOrDefault(false)
    }

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

            if (file.isFile) {
                return file.length()
            }

            return file.listFiles()
                ?.sumOf { child ->
                    dirSize(child)
                }
                ?: 0L
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
    // DATA
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

    data class CommandResult(
        val output: String,
        val exitCode: Int
    )
}
