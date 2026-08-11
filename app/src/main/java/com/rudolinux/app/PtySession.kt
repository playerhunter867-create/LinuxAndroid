package org.linox.mobile

import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight interactive process session for LinOx.
 *
 * Uses the standard Java Process API.
 * No external PTY library is required.
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

            val builder =
                ProcessBuilder(command)
                    .redirectErrorStream(true)

            if (!workingDirectory.isNullOrBlank()) {
                val directory =
                    File(workingDirectory)

                if (directory.isDirectory) {
                    builder.directory(directory)
                }
            }

            val env =
                builder.environment()

            env.putAll(environment)

            val process =
                builder.start()

            return PtySession(
                process = process,
                input = process.outputStream
            )
        }
    }

    /**
     * Sends one command to the running shell.
     */
    fun send(
        command: String
    ) {

        if (stopped.get()) {
            return
        }

        if (!process.isAlive) {
            return
        }

        try {

            input.write(
                command.toByteArray(
                    Charsets.UTF_8
                )
            )

            input.write(
                '\n'.code
            )

            input.flush()

        } catch (_: Exception) {
            // Process may have exited between the checks.
        }
    }

    /**
     * Starts a background thread that forwards process output.
     */
    fun readLoop(
        onText: (String) -> Unit,
        onExit: (Int) -> Unit = {}
    ) {

        Thread {

            var exitCode = -1

            try {

                process.inputStream.use { stream ->

                    val buffer =
                        ByteArray(8192)

                    while (
                        !stopped.get()
                    ) {

                        val count =
                            stream.read(buffer)

                        if (count < 0) {
                            break
                        }

                        if (count > 0) {

                            val text =
                                String(
                                    buffer,
                                    0,
                                    count,
                                    Charset.forName("UTF-8")
                                )

                            onText(text)
                        }
                    }
                }

                exitCode =
                    process.waitFor()

            } catch (error: Exception) {

                if (!stopped.get()) {

                    val message =
                        error.message
                            ?: error.javaClass.simpleName

                    onText(
                        "\n[LinOx] $message\n"
                    )
                }

            } finally {

                onExit(exitCode)
            }

        }.apply {

            name =
                "LinOx-pty-reader"

            isDaemon =
                true

            start()
        }
    }

    /**
     * Stops the process and closes stdin.
     */
    fun stop() {

        if (
            !stopped.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        runCatching {
            input.close()
        }

        if (process.isAlive) {

            runCatching {
                process.destroy()
            }

            try {

                if (
                    !process.waitFor(
                        500,
                        TimeUnit.MILLISECONDS
                    )
                ) {

                    process.destroyForcibly()

                    process.waitFor(
                        1,
                        TimeUnit.SECONDS
                    )
                }

            } catch (
                _: InterruptedException
            ) {

                process.destroyForcibly()

                Thread.currentThread().interrupt()
            }
        }
    }

    fun isAlive(): Boolean =
        process.isAlive
}
