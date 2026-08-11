package org.linox.mobile

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

class LinuxTerminalActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var runtime: LinuxRuntime
    private var session: PtySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra("distro_id") ?: "debian12"
        val manager = DistroManager(this)
        val distro = manager.available.firstOrNull { it.id == id } ?: manager.getDefault()
        runtime = LinuxRuntime(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 14))
            setPadding(12, 12, 12, 8)
        }

        val title = TextView(this).apply {
            text = "${distro.name} ${distro.version} — LinOx"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(124, 255, 178))
        }

        output = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 230, 225))
            text = "Starting Linux...\n"
            setPadding(4, 12, 4, 12)
        }

        val scroll = ScrollView(this).apply {
            addView(output)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        input = EditText(this).apply {
            hint = "command"
            setSingleLine(true)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val run = Button(this).apply {
            text = "RUN"
            setOnClickListener {
                val cmd = input.text.toString()
                if (cmd.isNotBlank()) {
                    session?.send(cmd)
                    input.text.clear()
                }
            }
        }

        row.addView(input, LinearLayout.LayoutParams(0, 58, 1f))
        row.addView(run, LinearLayout.LayoutParams(90, 58))

        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row)

        setContentView(root)

        try {
            session = runtime.startInteractivePty { text ->
                runOnUiThread {
                    output.append(text)
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }

            output.append("✓ PRoot started\n\n")
            output.append("Type: cat /etc/os-release\n\n")
        } catch (e: Exception) {
            output.append("\n[ERROR] ${e.message}\n")
            Toast.makeText(
                this,
                e.message ?: "Runtime error",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }
}
