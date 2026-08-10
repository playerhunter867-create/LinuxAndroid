package org.linox.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class LinuxRuntime(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null

    fun start(
        distro: Distro,
        onOutput: (String) -> Unit,
        onStarted: () -> Unit,
        onExit: (Int) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        executor.execute {
            try {
                val rootfs = File(
                    context.getExternalFilesDir(null),
                    "linox/distros/${distro.id}/rootfs"
                )

                check(File(rootfs, "etc/os-release").isFile) {
                    "RootFS is not installed. Prepare the distribution first."
                }

                val shell = when {
                    File(rootfs, "bin/bash").isFile -> "/bin/bash"
                    File(rootfs, "bin/sh").isFile -> "/bin/sh"
                    File(rootfs, "usr/bin/bash").isFile -> "/usr/bin/bash"
                    File(rootfs, "usr/bin/sh").isFile -> "/usr/bin/sh"
                    else -> error("No shell found in the installed rootfs")
                }

                val proot = File(context.filesDir, "bin/proot-aarch64-static")
                installProot(proot)

                val command = mutableListOf(
                    proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "--sysvipc",
                    "-0",
                    "-r", rootfs.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "--cwd=/root",
                    shell,
                    "-l"
                )

                val pb = ProcessBuilder(command)
                    .redirectErrorStream(true)

                pb.environment()["HOME"] = "/root"
                pb.environment()["USER"] = "root"
                pb.environment()["LOGNAME"] = "root"
                pb.environment()["TERM"] = "xterm-256color"
                pb.environment()["LANG"] = "C.UTF-8"
                pb.environment()["LC_ALL"] = "C.UTF-8"
                pb.environment()["PATH"] =
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

                // Some Android kernels require PRoot's seccomp workaround.
                // It is harmless on kernels where seccomp works normally.
                pb.environment()["PROOT_NO_SECCOMP"] = "1"

                val p = pb.start()
                process = p

                onStarted()

                Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            onOutput(line)
                        }
                    } catch (t: Throwable) {
                        if (p.isAlive) onError(t)
                    }
                }.start()

                val exit = p.waitFor()
                onExit(exit)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun send(command: String) {
        val p = process ?: return
        if (!p.isAlive) return

        try {
            p.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
    }

    private fun installProot(destination: File) {
        if (destination.isFile && destination.length() > 100_000) {
            destination.setExecutable(true, false)
            return
        }

        destination.parentFile?.mkdirs()

        context.assets.open("proot-aarch64-static").use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }

        check(destination.setExecutable(true, false)) {
            "Unable to make PRoot executable"
        }
    }
}
