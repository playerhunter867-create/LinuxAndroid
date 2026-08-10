package org.linox.mobile

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
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
            text = "LinOx 0.5"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(124, 255, 178))
            setPadding(0, 0, 0, 8)
        }

        val distroButton = Button(this).apply {
            text = "🐧 DISTRIBUTIONS"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, DistroManagerActivity::class.java))
            }
        }

        output = TextView(this).apply {
            text = """
                LinOx 0.5
                Linux environment for Android

                user@linox:~$ help
                Available:
                help, clear, uname, ls, pwd, whoami, distros

                user@linox:~$
            """.trimIndent()

            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 230, 225))
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
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(20, 25, 30))
        }

        val run = Button(this).apply {
            text = "RUN"
            setOnClickListener {
                execute(input.text.toString())
            }
        }

        row.addView(input, LinearLayout.LayoutParams(0, 58, 1f))
        row.addView(run, LinearLayout.LayoutParams(90, 58))

        root.addView(title)
        root.addView(distroButton, LinearLayout.LayoutParams(-1, 58))
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        root.addView(row)

        setContentView(root)
    }

    private fun execute(commandRaw: String) {
        val command = commandRaw.trim()
        if (command.isEmpty()) return

        val result = when (command) {
            "help" -> "help  clear  uname  ls  pwd  whoami  distros"
            "uname" -> "LinOx Android"
            "ls" -> "bin  dev  etc  home  tmp  usr  var"
            "pwd" -> "/home/user"
            "whoami" -> "user"
            "distros" -> {
                startActivity(
                    android.content.Intent(this, DistroManagerActivity::class.java)
                )
                "Opening distribution manager..."
            }
            "clear" -> ""
            else -> "command not found: $command"
        }

        if (command == "clear") {
            output.text = "user@linox:~$ "
        } else {
            output.append("$command\n$result\n\nuser@linox:~$ ")
        }

        input.text.clear()
    }
}
