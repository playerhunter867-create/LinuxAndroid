package com.rudolinux.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * LinOx interactive Linux terminal.
 *
 * Uses:
 *  - LinuxRuntime for PRoot/rootfs management
 *  - PtySession for interactive process input/output
 *
 * No Android root is required.
 */
class LinuxTerminalActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var runtime: LinuxRuntime

    private var session: PtySession? = null

    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime = LinuxRuntime(this)

        buildInterface()

        startLinux()
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun buildInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                Color.rgb(8, 11, 14)
            )
            setPadding(
                12,
                12,
                12,
                8
            )
        }

        val title = TextView(this).apply {
            text = "LinOx Linux Terminal"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                Color.rgb(124, 255, 178)
            )
            setPadding(
                4,
                4,
                4,
                12
            )
        }

        output = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE

            setTextColor(
                Color.rgb(220, 230, 225)
            )

            setBackgroundColor(
                Color.rgb(5, 7, 9)
            )

            text =
                "LinOx Linux Terminal\n" +
                "--------------------\n"
        }

        scroll = ScrollView(this).apply {
            isFillViewport = true

            addView(
                output,
                ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                0,
                8,
                0,
                0
            )
        }

        input = EditText(this).apply {
            hint = "command"
            setSingleLine(true)

            textSize = 14f
            typeface = Typeface.MONOSPACE

            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)

            setBackgroundColor(
                Color.rgb(18, 22, 25)
            )

            setPadding(
                12,
                0,
                12,
                0
            )

            setOnEditorActionListener { _, _, _ ->
                sendCommand()
                true
            }
        }

        val runButton = Button(this).apply {
            text = "RUN"

            setOnClickListener {
                sendCommand()
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP"

            setOnClickListener {
                stopSession()
            }
        }

        val inputParams =
            LinearLayout.LayoutParams(
                0,
                58,
                1f
            )

        val runParams =
            LinearLayout.LayoutParams(
                85,
                58
            )

        val stopParams =
            LinearLayout.LayoutParams(
                90,
                58
            )

        row.addView(
            input,
            inputParams
        )

        row.addView(
            runButton,
            runParams
        )

        row.addView(
            stopButton,
            stopParams
        )

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    // -------------------------------------------------------------------------
    // START LINUX
    // -------------------------------------------------------------------------

    private fun startLinux() {

        val distroId =
            intent.getStringExtra(
                "distro_id"
            ) ?: "debian12"

        appendOutput(
            "\n[LinOx] Selected distribution: $distroId\n"
        )

        try {

            /*
             * LinuxRuntime in the current implementation uses the active
             * rootfs directly. We therefore make sure the runtime is ready
             * before starting the interactive process.
             */

            if (!runtime.isLinuxReady()) {

                appendOutput(
                    "[ERROR] Linux userspace is not ready.\n"
                )

                appendOutput(
                    runtime.status() + "\n"
                )

                appendOutput(
                    "\nInstall an ARM64 PRoot and Linux ARM64 RootFS first.\n"
                )

                return
            }

            appendOutput(
                "[OK] Linux userspace detected.\n"
            )

            appendOutput(
                "[OK] PRoot: ${runtime.prootPath().absolutePath}\n"
            )

            appendOutput(
                "[OK] RootFS: ${runtime.rootfsPath().absolutePath}\n"
            )

            appendOutput(
                "[LinOx] Starting PRoot...\n\n"
            )

            session =
                runtime.startInteractivePty { text ->

                    runOnUiThread {

                        appendOutput(
                            text
                        )

                    }
                }

            appendOutput(
                "✓ PRoot started\n\n"
            )

            appendOutput(
                "Try:\n"
            )

            appendOutput(
                "  cat /etc/os-release\n"
            )

            appendOutput(
                "  uname -a\n"
            )

            appendOutput(
                "  linox-info\n"
            )

            appendOutput(
                "  linox-doctor\n\n"
            )

        } catch (error: Exception) {

            appendOutput(
                "\n[ERROR] ${error.message ?: error.javaClass.simpleName}\n"
            )

            Toast.makeText(
                this,
                error.message ?: "Linux runtime error",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------------------------
    // COMMAND INPUT
    // -------------------------------------------------------------------------

    private fun sendCommand() {

        val command =
            input.text
                .toString()
                .trim()

        if (command.isEmpty()) {
            return
        }

        val currentSession =
            session

        if (
            currentSession == null ||
            !currentSession.isAlive()
        ) {

            appendOutput(
                "\n[LinOx] Terminal session is not running.\n"
            )

            return
        }

        appendOutput(
            "$command\n"
        )

        currentSession.send(
            command
        )

        input.text.clear()
    }

    // -------------------------------------------------------------------------
    // OUTPUT
    // -------------------------------------------------------------------------

    private fun appendOutput(
        text: String
    ) {

        if (!::output.isInitialized) {
            return
        }

        runOnUiThread {

            output.append(
                text
            )

            if (::scroll.isInitialized) {

                scroll.post {
                    scroll.fullScroll(
                        ScrollView.FOCUS_DOWN
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // STOP
    // -------------------------------------------------------------------------

    private fun stopSession() {

        session?.stop()

        session = null

        appendOutput(
            "\n\n[LinOx] Terminal stopped.\n"
        )
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------

    override fun onDestroy() {

        session?.stop()

        session = null

        super.onDestroy()
    }
}
