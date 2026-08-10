package org.linox.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 14))
            setPadding(18, 18, 18, 12)
        }

        val title = TextView(this).apply {
            text = "LinOx 0.5.1"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(124, 255, 178))
            setPadding(0, 0, 0, 8)
        }

        val distroButton = Button(this).apply {
            text = "🐧 DISTRIBUTIONS"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DistroManagerActivity::class.java))
            }
        }

        output = TextView(this).apply {
            text = """
                LinOx 0.5.1
                Linux environment for Android

                user@linox:~$ help
                Available:
                help, clear, uname, ls, pwd, whoami, distros

                Use DISTRIBUTIONS to install a real ARM64 Linux userspace.
            """.trimIndent()

            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 230, 225))
        }

        val scroll = ScrollView(this).apply { addView(output) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        input = EditText(this).apply {
            hint = "command"
            setSingleLine(true)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(20, 25, 30))
        }

        val run = Button(this).apply {
            text = "RUN"
            setOnClickListener { execute(input.text.toString()) }
        }

        row.addView(input, LinearLayout.LayoutParams(0, 58, 1f))
        row.addView(run, LinearLayout.LayoutParams(90, 58))

        root.addView(title)
        root.addView(distroButton, LinearLayout.LayoutParams(-1, 58))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row)

        setContentView(root)
    }

    private fun execute(commandRaw: String) {
        val command = commandRaw.trim()
        if (command.isEmpty()) return

        when (command) {
            "help" -> output.append("\nhelp clear uname ls pwd whoami distros\n")
            "uname" -> output.append("\nLinOx Android / rootless Linux runtime\n")
            "ls" -> output.append("\nbin dev etc home tmp usr var\n")
            "pwd" -> output.append("\n/home/user\n")
            "whoami" -> output.append("\nuser\n")
            "distros" -> {
                output.append("\nOpening distribution manager...\n")
                startActivity(Intent(this, DistroManagerActivity::class.java))
            }
            "clear" -> output.text = ""
            else -> output.append("\ncommand not found: $command\n")
        }

        output.append("\nuser@linox:~$ ")
        input.text.clear()
    }
}
