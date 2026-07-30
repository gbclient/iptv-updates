package com.iptv.player

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
                    .setTitle("Guida TV")
                    .setMessage("Nessun dato EPG disponibile.\nAssicurati che la playlist abbia un EPG configurato (url-tvg).")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val now = System.currentTimeMillis()
            val theme = ThemeHelper.get(context)
            val dp = context.resources.displayMetrics.density

            val scroll = ScrollView(context)
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            }

            val title = TextView(context).apply {
                text = "${dayFmt.format(Date(now))}  ${timeFmt.format(Date(now))}"
                textSize = 14f
                setTextColor(theme.accent)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (12 * dp).toInt())
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
                    val row = LinearLayout(context)
                    row.orientation = LinearLayout.VERTICAL
                    row.setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                    val bg = GradientDrawable().apply {
                        setColor(0x081A237E.toInt())
                        cornerRadius = 8f
                        setStroke(1, 0x223344AA.toInt())
                    }
                    row.background = bg
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = (6 * dp).toInt()
                    layout.addView(row, lp)

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

                    val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                    progressBar.max = 100
                    progressBar.progress = progPct
                    progressBar.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (3 * dp).toInt()
                    )
                    progressBar.progressDrawable = ContextCompat.getDrawable(context, R.drawable.epg_progress)
                    row.addView(progressBar)

                    val nowText = TextView(context).apply {
                        text = "\u25CF ${timeFmt.format(Date(current.start))}  ${current.title}"
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
                            text = "\u25B6 ${timeFmt.format(Date(next.start))}  ${next.title}"
                            textSize = 11f
                            setTextColor(0xFF90CAF9.toInt())
                            maxLines = 1
                        }
                        row.addView(nextText)
                    }
                }
            }

            if (!found) {
                val msg = TextView(context).apply {
                    text = "Nessun programma corrisponde ai canali.\nVerifica che i nomi dei canali nell'EPG corrispondano a quelli della playlist."
                    setTextColor(0xFF667788.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
                }
                layout.addView(msg)
            }

            scroll.addView(layout)
            AlertDialog.Builder(context)
                .setTitle("Guida TV")
                .setView(scroll)
                .setPositiveButton("Chiudi", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("EpgGuide", "Error", e)
        }
    }

    private fun normalize(name: String) = name.lowercase().replace("\\s+".toRegex(), "").replace(Regex("\\b(hd|fhd|4k|uhd|hevc)\\b"), "")
}
