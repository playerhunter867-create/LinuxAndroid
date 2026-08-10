package org.linox.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DistroManagerActivity : Activity() {

    private lateinit var manager: DistroManager
    private lateinit var rootfs: RootfsManager
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = DistroManager(this)
        rootfs = RootfsManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 14))
            setPadding(18, 18, 18, 18)
        }

        val title = TextView(this).apply {
            text = "LinOx 0.5.3 — Distributions"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(124, 255, 178))
        }

        val subtitle = TextView(this).apply {
            text = "Download and run real ARM64 Linux userspaces"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 6, 0, 16)
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply { addView(list) }

        root.addView(title)
        root.addView(subtitle)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        render()
    }

    private fun render() {
        list.removeAllViews()

        for (distro in manager.available) {
            val installed = rootfs.isInstalled(distro.id)

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
                text = "${distro.codename} • ${distro.architecture}"
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

            val status = TextView(this).apply {
                text = if (installed) "✓ ROOTFS INSTALLED" else "Not installed"
                textSize = 12f
                setTextColor(if (installed) Color.rgb(124, 255, 178) else Color.GRAY)
            }

            val progress = ProgressBar(this).apply {
                visibility = View.GONE
                isIndeterminate = true
            }

            val action = Button(this).apply {
                text = if (installed) "START LINUX" else "PREPARE / DOWNLOAD"
                setOnClickListener {
                    if (rootfs.isInstalled(distro.id)) {
                        startActivity(
                            android.content.Intent(
                                this@DistroManagerActivity,
                                LinuxTerminalActivity::class.java
                            ).putExtra("distro_id", distro.id)
                        )
                    } else {
                        isEnabled = false
                        progress.visibility = View.VISIBLE
                        status.text = "Downloading / verifying / extracting..."
                        rootfs.installAsync(
                            distro,
                            onProgress = { message ->
                                runOnUiThread {
                                    status.text = message
                                }
                            },
                            onDone = { result ->
                                runOnUiThread {
                                    progress.visibility = View.GONE
                                    isEnabled = true
                                    if (result.ok) {
                                        Toast.makeText(
                                            this@DistroManagerActivity,
                                            result.message,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        render()
                                    } else {
                                        status.text = "INSTALL FAILED"
                                        Toast.makeText(
                                            this@DistroManagerActivity,
                                            result.message,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            card.addView(name)
            card.addView(info)
            card.addView(description)
            card.addView(status)
            card.addView(progress)
            card.addView(action)

            val params = LinearLayout.LayoutParams(-1, -2)
            params.setMargins(0, 0, 0, 14)
            list.addView(card, params)
        }

        val note = TextView(this).apply {
            text = "RootFS images are pulled from Docker Hub's public Linux images. Each OCI layer is SHA-256 verified before extraction. No root access is required."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 16)
        }
        list.addView(note)
    }
}
