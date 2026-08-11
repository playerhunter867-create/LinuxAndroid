package org.linox.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LinuxTerminalActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var runtime: LinuxRuntime

    private var session: PtySession? = null

    private var distroId: String = "debian12"

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        distroId =
            intent.getStringExtra("distro_id")
                ?: "debian12"

        runtime =
            LinuxRuntime(this)

        buildTerminalUi()

        startLinux()
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun buildTerminalUi() {

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.rgb(
                        7,
                        10,
                        13
                    )
                )

                setPadding(
                    12,
                    12,
                    12,
                    8
                )
            }

        val header =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    4,
                    4,
                    4,
                    10
                )
            }

        val title =
            TextView(this).apply {

                text =
                    "LinOx Terminal"

                textSize =
                    19f

                typeface =
                    Typeface.DEFAULT_BOLD

                setTextColor(
                    Color.rgb(
                        124,
                        255,
                        178
                    )
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        val status =
            TextView(this).apply {

                text =
                    "● OFFLINE"

                textSize =
                    12f

                typeface =
                    Typeface.MONOSPACE

                setTextColor(
                    Color.rgb(
                        255,
                        100,
                        100
                    )
                )

                tag =
                    "terminal_status"
            }

        header.addView(title)
        header.addView(status)

        // ---------------------------------------------------------------------
        // Terminal output
        // ---------------------------------------------------------------------

        val terminalScroll =
            ScrollView(this).apply {

                isFillViewport =
                    true

                setBackgroundColor(
                    Color.rgb(
                        3,
                        5,
                        7
                    )
                )

                setPadding(
                    8,
                    8,
                    8,
                    8
                )
            }

        output =
            TextView(this).apply {

                textSize =
                    14f

                typeface =
                    Typeface.MONOSPACE

                setTextColor(
                    Color.rgb(
                        220,
                        230,
                        225
                    )
                )

                setTextIsSelectable(
                    true
                )

                text =
                    "LinOx Terminal\n" +
                    "-------------------------\n"

                setPadding(
                    4,
                    4,
                    4,
                    16
                )
            }

        terminalScroll.addView(
            output,
            ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            )
        )

        // ---------------------------------------------------------------------
        // Command input
        // ---------------------------------------------------------------------

        val commandRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    8,
                    0,
                    0
                )
            }

        input =
            EditText(this).apply {

                hint =
                    "Enter command..."

                setSingleLine(
                    true
                )

                textSize =
                    14f

                typeface =
                    Typeface.MONOSPACE

                setTextColor(
                    Color.WHITE
                )

                setHintTextColor(
                    Color.GRAY
                )

                setBackgroundColor(
                    Color.rgb(
                        20,
                        24,
                        28
                    )
                )

                setPadding(
                    12,
                    0,
                    12,
                    0

                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        56,
                        1f
                    )
            }

        val runButton =
            Button(this).apply {

                text =
                    "RUN"

                isAllCaps =
                    false

                layoutParams =
                    LinearLayout.LayoutParams(
                        90,
                        56
                    ).apply {

                        leftMargin =
                            8
                    }

                setOnClickListener {
                    sendCommand()
                }
            }

        input.setOnEditorActionListener { _, _, _ ->

            sendCommand()

            true
        }

        commandRow.addView(
            input
        )

        commandRow.addView(
            runButton
        )

        // ---------------------------------------------------------------------
        // Control buttons
        // ---------------------------------------------------------------------

        val controls =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    8,
                    0,
                    0
                )
            }

        val clearButton =
            Button(this).apply {

                text =
                    "CLEAR"

                isAllCaps =
                    false

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        52,
                        1f
                    ).apply {

                        rightMargin =
                            4
                    }

                setOnClickListener {
                    clearTerminal()
                }
            }

        val stopButton =
            Button(this).apply {

                text =
                    "STOP"

                isAllCaps =
                    false

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        52,
                        1f
                    ).apply {

                        leftMargin =
                            4

                        rightMargin =
                            4
                    }

                setOnClickListener {
                    stopLinux()
                }
            }

        val restartButton =
            Button(this).apply {

                text =
                    "RESTART"

                isAllCaps =
                    false

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        52,
                        1f
                    ).apply {

                        leftMargin =
                            4
                    }

                setOnClickListener {
                    restartLinux()
                }
            }

        controls.addView(
            clearButton
        )

        controls.addView(
            stopButton
        )

        controls.addView(
            restartButton
        )

        // ---------------------------------------------------------------------
        // Assemble UI
        // ---------------------------------------------------------------------

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            terminalScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            commandRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            controls,
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

        appendOutput(
            "\n[LinOx] Starting Linux...\n"
        )

        appendOutput(
            "[LinOx] Distribution: $distroId\n"
        )

        try {

            if (
                !runtime.activateInstalledDistro(
                    distroId
                )
            ) {

                throw IllegalStateException(
                    "Linux distribution '$distroId' " +
                    "is not installed or its RootFS is incomplete."
                )
            }

            appendOutput(
                "[OK] RootFS activated\n"
            )

            appendOutput(
                "[LinOx] Checking PRoot...\n"
            )

            if (
                !runtime.hasProot()
            ) {

                throw IllegalStateException(
                    "PRoot is not installed."
                )
            }

            appendOutput(
                "[OK] PRoot available\n"
            )

            appendOutput(
                "[LinOx] Starting shell...\n\n"
            )

            session =
                runtime.startInteractivePty { text ->

                    runOnUiThread {

                        appendOutput(
                            text
                        )
                    }
                }

            updateStatus(
                true
            )

            appendOutput(
                "\n[OK] Linux shell started.\n"
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
                "  linox-info\n\n"
            )

        } catch (error: Exception) {

            updateStatus(
                false
            )

            appendOutput(
                "\n[ERROR] Linux failed to start\n"
            )

            appendOutput(
                "${error.javaClass.simpleName}: " +
                "${error.message}\n"
            )

            Toast.makeText(
                this,
                error.message
                    ?: "Linux runtime error",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------------------------
    // SEND COMMAND
    // -------------------------------------------------------------------------

    private fun sendCommand() {

        val command =
            input.text
                .toString()
                .trim()

        if (
            command.isEmpty()
        ) {
            return
        }

        val currentSession =
            session

        if (
            currentSession == null
        ) {

            appendOutput(
                "\n[ERROR] Linux shell is not running.\n"
            )

            return
        }

        appendOutput(
            "\n$command\n"
        )

        try {

            currentSession.send(
                command
            )

            input.text.clear()

        } catch (error: Exception) {

            appendOutput(
                "\n[ERROR] " +
                "${error.message}\n"
            )
        }
    }

    // -------------------------------------------------------------------------
    // STOP
    // -------------------------------------------------------------------------

    private fun stopLinux() {

        val currentSession =
            session

        session = null

        if (
            currentSession != null
        ) {

            runCatching {
                currentSession.stop()
            }
        }

        updateStatus(
            false
        )

        appendOutput(
            "\n\n[LinOx] Shell stopped.\n"
        )
    }

    // -------------------------------------------------------------------------
    // RESTART
    // -------------------------------------------------------------------------

    private fun restartLinux() {

        stopLinux()

        appendOutput(
            "\n[LinOx] Restarting...\n"
        )

        startLinux()
    }

    // -------------------------------------------------------------------------
    // CLEAR
    // -------------------------------------------------------------------------

    private fun clearTerminal() {

        output.text =
            ""

        appendOutput(
            "LinOx Terminal\n" +
            "-------------------------\n"
        )
    }

    // -------------------------------------------------------------------------
    // OUTPUT
    // -------------------------------------------------------------------------

    private fun appendOutput(
        text: String
    ) {

        if (
            !::output.isInitialized
        ) {
            return
        }

        output.append(
            text
        )

        output.post {
            val parent =
                output.parent

            if (
                parent is ScrollView
            ) {

                parent.fullScroll(
                    ScrollView.FOCUS_DOWN
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // STATUS
    // -------------------------------------------------------------------------

    private fun updateStatus(
        running: Boolean
    ) {

        val root =
            window.decorView

        val status =
            root.findViewWithTag<TextView>(
                "terminal_status"
            )

        if (
            status != null
        ) {

            if (running) {

                status.text =
                    "● ONLINE"

                status.setTextColor(
                    Color.rgb(
                        124,
                        255,
                        178
                    )
                )

            } else {

                status.text =
                    "● OFFLINE"

                status.setTextColor(
                    Color.rgb(
                        255,
                        100,
                        100
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // ACTIVITY LIFECYCLE
    // -------------------------------------------------------------------------

    override fun onDestroy() {

        session?.let {
            runCatching {
                it.stop()
            }
        }

        session =
            null

        super.onDestroy()
    }
}
