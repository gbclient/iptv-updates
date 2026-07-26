package com.iptv.player

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.iptv.player.model.Channel
import java.text.SimpleDateFormat
import java.util.*

object EpgGuide {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun show(context: Context, channels: List<Channel>, programmes: List<EpgParser.Programme>) {
        try {
            val now = System.currentTimeMillis()
            val theme = ThemeHelper.get(context)
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }

            val title = TextView(context).apply {
                text = "Guida TV - ${timeFmt.format(Date(now))}"
                textSize = 14f; setTextColor(theme.accent); gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            }
            layout.addView(title)

            var found = false
            for (ch in channels) {
                val progs = programmes.filter {
                    normalize(it.channel) == normalize(ch.name)
                }.sortedBy { it.start }

                val current = progs.find { it.start <= now && it.stop > now }
                val next = progs.find { it.start >= now && it != current }

                if (current != null) {
                    found = true
                    val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6, 0, 6) }
                    val chName = TextView(context).apply {
                        text = ch.name; textSize = 12f; setTextColor(theme.accent)
                    }
                    row.addView(chName)
                    val nowText = TextView(context).apply {
                        text = "● ${timeFmt.format(Date(current.start))}-${timeFmt.format(Date(current.stop))} ${current.title}"
                        textSize = 11f; setTextColor(0xFFFFFFFF.toInt())
                    }
                    row.addView(nowText)
                    if (next != null) {
                        val nextText = TextView(context).apply {
                            text = "▶ ${timeFmt.format(Date(next.start))} ${next.title}"
                            textSize = 11f; setTextColor(0xFF8899AA.toInt())
                        }
                        row.addView(nextText)
                    }
                    layout.addView(row)
                }
            }

            if (!found) {
                layout.addView(TextView(context).apply {
                    text = "Nessun programma disponibile"
                    setTextColor(0xFF667788.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(16, 24, 16, 24)
                })
            }

            AlertDialog.Builder(context)
                .setTitle("Guida TV")
                .setView(layout)
                .setPositiveButton("Chiudi", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("EpgGuide", "Error", e)
        }
    }

    private fun normalize(name: String) = name.lowercase().replace("\\s+".toRegex(), "").replace(Regex("\\b(hd|fhd|4k|uhd|hevc)\\b"), "")
}
