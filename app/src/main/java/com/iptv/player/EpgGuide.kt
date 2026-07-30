package com.iptv.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.iptv.player.model.Channel
import java.text.SimpleDateFormat
import java.util.*

object EpgGuide {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE dd MMM", Locale.ITALIAN)

    fun show(context: Context, channels: List<Channel>, programmes: List<EpgParser.Programme>) {
        try {
            if (programmes.isEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("📺 Guida TV")
                    .setMessage("Nessun dato EPG disponibile.\nAssicurati che la playlist abbia un EPG configurato (url-tvg).")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val now = System.currentTimeMillis()
            val theme = ThemeHelper.get(context)

            val scroll = NestedScrollView(context)
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 8, 12, 8)
            }

            val title = TextView(context).apply {
                text = "📺 ${dayFmt.format(Date(now))}  ${timeFmt.format(Date(now))}"
                textSize = 14f
                setTextColor(theme.accent)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            layout.addView(title)

            var found = false
            for (ch in channels) {
                val progs = programmes.filter {
                    normalize(it.channel) == normalize(ch.name)
                }.sortedBy { it.start }

                val current = progs.find { it.start <= now && it.stop > now }
                val next = progs.firstOrNull { it.start >= now && it != current }

                if (current != null) {
                    found = true
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(8, 6, 8, 6)
                        setBackgroundResource(android.R.color.transparent)
                        val bg = GradientDrawable().apply {
                            setColor(0x081A237E.toInt())
                            cornerRadius = 8f
                            setStroke(1, 0x223344AA.toInt())
                        }
                        background = bg
                        val lp = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.bottomMargin = 6
                        layoutParams = lp
                    }

                    val chName = TextView(context).apply {
                        text = ch.name
                        textSize = 12f
                        setTextColor(theme.accent)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        maxLines = 1
                    }
                    row.addView(chName)

                    val elapsed = (now - current.start).toFloat()
                    val total = (current.stop - current.start).toFloat()
                    val progPct = if (total > 0) (elapsed / total * 100).toInt().coerceIn(0, 100) else 0

                    val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = progPct
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            6
                        )
                        val scale = context.resources.displayMetrics.density
                        val h = (6 * scale).toInt()
                        layoutParams.height = h
                        progressDrawable = ContextCompat.getDrawable(context, R.drawable.epg_progress)
                    }
                    row.addView(progressBar)

                    val nowText = TextView(context).apply {
                        text = "● ${timeFmt.format(Date(current.start))}  ${current.title}"
                        textSize = 12f
                        setTextColor(0xFFFFFFFF.toInt())
                        maxLines = 2
                    }
                    row.addView(nowText)

                    if (current.description.isNotBlank()) {
                        val descText = TextView(context).apply {
                            text = current.description.take(120)
                            textSize = 10f
                            setTextColor(0xFF8899AA.toInt())
                            maxLines = 2
                        }
                        row.addView(descText)
                    }

                    if (next != null) {
                        val nextText = TextView(context).apply {
                            text = "▶ ${timeFmt.format(Date(next.start))}  ${next.title}"
                            textSize = 11f
                            setTextColor(0xFF90CAF9.toInt())
                            maxLines = 1
                        }
                        row.addView(nextText)
                    }

                    layout.addView(row)
                }
            }

            if (!found) {
                layout.addView(TextView(context).apply {
                    text = "Nessun programma corrisponde ai canali.\nVerifica che i nomi dei canali nell'EPG corrispondano a quelli della playlist."
                    setTextColor(0xFF667788.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(16, 24, 16, 24)
                })
            }

            scroll.addView(layout)
            AlertDialog.Builder(context)
                .setTitle("📺 Guida TV")
                .setView(scroll)
                .setPositiveButton("Chiudi", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("EpgGuide", "Error", e)
        }
    }

    private fun normalize(name: String) = name.lowercase().replace("\\s+".toRegex(), "").replace(Regex("\\b(hd|fhd|4k|uhd|hevc)\\b"), "")
}
