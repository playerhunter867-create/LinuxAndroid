package org.linox.mobile

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DistroManagerActivity : Activity() {

    private lateinit var manager: DistroManager
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = DistroManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 14))
            setPadding(18, 18, 18, 18)
        }

        val title = TextView(this).apply {
            text = "LinOx 0.5 — Distributions"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(124, 255, 178))
            setPadding(0, 0, 0, 6)
        }

        val subtitle = TextView(this).apply {
            text = "Choose a Linux userspace for your device"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 18)
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            addView(list)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()

        for (distro in manager.available) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                setBackgroundColor(Color.rgb(18, 23, 27))
            }

            val name = TextView(this).apply {
                text = "${distro.name} ${distro.version}"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }

            val info = TextView(this).apply {
                text = "${distro.codename} • ${distro.architecture} • ${distro.size}"
                textSize = 13f
                setTextColor(Color.rgb(124, 255, 178))
                setPadding(0, 4, 0, 4)
            }

            val description = TextView(this).apply {
                text = distro.description
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(0, 2, 0, 10)
            }

            val action = Button(this).apply {
                text = if (manager.isInstalled(distro.id)) "INSTALLED" else "PREPARE"
                isEnabled = !manager.isInstalled(distro.id)
                setOnClickListener {
                    manager.setInstalled(distro.id, true)
                    Toast.makeText(
                        this@DistroManagerActivity,
                        "${distro.name} ${distro.version} selected. RootFS bootstrap is the next runtime step.",
                        Toast.LENGTH_LONG
                    ).show()
                    render()
                }
            }

            card.addView(name)
            card.addView(info)
            card.addView(description)
            card.addView(action)

            val params = LinearLayout.LayoutParams(-1, -2)
            params.setMargins(0, 0, 0, 14)
            list.addView(card, params)
        }

        val note = TextView(this).apply {
            text = "* Size is an estimate. LinOx 0.5 separates distro selection from rootfs installation so archives can be verified before extraction."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 16)
        }
        list.addView(note)
    }
}
