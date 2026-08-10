package org.linox.mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class LinuxRuntime(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var writer: OutputStream? = null

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

                val proot = File(context.filesDir, "bin/proot-aarch64-static")
                installProot(proot)

                val command = listOf(
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
                    "/bin/sh", "-l"
                )

                val pb = ProcessBuilder(command)
                    .redirectErrorStream(true)

                pb.environment()["HOME"] = "/root"
                pb.environment()["USER"] = "root"
                pb.environment()["TERM"] = "xterm-256color"
                pb.environment()["LANG"] = "C.UTF-8"
                pb.environment()["PATH"] =
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

                val p = pb.start()
                process = p
                writer = p.outputStream

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
        val out = p.outputStream
        out.write((command + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (_: Throwable) {}
        process = null
    }

    private fun installProot(destination: File) {
        if (destination.isFile && destination.length() > 100_000) return

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
