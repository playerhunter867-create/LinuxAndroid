package com.rudolinux.app

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
            text = "RudoLinux 0.1"
            textSize = 20f
            setTextColor(Color.rgb(124, 255, 178))
            setPadding(0, 0, 0, 14)
        }

        output = TextView(this).apply {
            text = """
                RudoLinux — Linux-like Android environment

                user@rudo:~$ help
                Available: help, clear, uname, ls, pwd, whoami

                user@rudo:~$ 
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

        row.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                58,
                1f
            )
        )

        row.addView(
            run,
            LinearLayout.LayoutParams(
                90,
                58
            )
        )

        root.addView(title)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        root.addView(row)

        setContentView(root)
    }

    private fun execute(commandRaw: String) {
        val command = commandRaw.trim()

        if (command.isEmpty()) {
            return
        }

        val result = when (command) {

            "help" ->
                "help  clear  uname  ls  pwd  whoami"

            "uname" ->
                "RudoLinux Android"

            "ls" ->
                "bin  dev  etc  home  tmp  usr  var"

            "pwd" ->
                "/home/user"

            "whoami" ->
                "user"

            "clear" ->
                ""

            else ->
                "command not found: $command"
        }

        if (command == "clear") {

            output.text = "user@rudo:~$ "

        } else {

            output.append(
                "$command\n$result\n\nuser@rudo:~$ "
            )
        }

        input.text.clear()
    }
}
