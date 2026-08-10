package org.linox.mobile

import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight interactive process session used by LinOx.
 *
 * This intentionally uses Android/Java ProcessBuilder only, so LinOx does not
 * need an additional PTY library just to build the APK.
 */
class PtySession private constructor(
    private val process: Process,
    private val input: OutputStream
) {
    private val stopped = AtomicBoolean(false)

    companion object {
        fun start(
            executable: String,
            args: List<String>,
            environment: Map<String, String> = emptyMap(),
            workingDirectory: String? = null
        ): PtySession {
            val command = ArrayList<String>(args.size + 1)
            command.add(executable)
            command.addAll(args)

            val builder = ProcessBuilder(command)
                .redirectErrorStream(true)

            if (workingDirectory != null) {
                val dir = File(workingDirectory)
                if (dir.isDirectory) {
                    builder.directory(dir)
                }
            }

            val env = builder.environment()
            env.putAll(environment)

            val process = builder.start()

            return PtySession(
                process = process,
                input = process.outputStream
            )
        }
    }

    fun send(command: String) {
        if (stopped.get() || !process.isAlive) return

        try {
            input.write(command.toByteArray(Charsets.UTF_8))
            input.write('\n'.code)
            input.flush()
        } catch (_: Exception) {
            // The process may have exited between isAlive and write().
        }
    }

    fun readLoop(
        onText: (String) -> Unit,
        onExit: (Int) -> Unit = {}
    ) {
        Thread {
            var exitCode = -1

            try {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)

                    while (!stopped.get()) {
                        val count = stream.read(buffer)
                        if (count < 0) break

                        if (count > 0) {
                            onText(
                                String(
                                    buffer,
                                    0,
                                    count,
                                    Charset.forName("UTF-8")
                                )
                            )
                        }
                    }
                }

                exitCode = process.waitFor()
            } catch (e: Exception) {
                if (!stopped.get()) {
                    onText("\n[LinOx] ${e.message ?: e.javaClass.simpleName}\n")
                }
            } finally {
                onExit(exitCode)
            }
        }.apply {
            name = "LinOx-pty-reader"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        runCatching { input.close() }

        if (process.isAlive) {
            process.destroy()

            try {
                if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }

    fun isAlive(): Boolean = process.isAlive
}
