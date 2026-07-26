package com.iptv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.model.Channel
import java.text.SimpleDateFormat
import java.util.*

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onChannelClick: (Channel, String?, String?) -> Unit,
    private val epgData: Map<String, EpgParser.Programme?> = emptyMap(),
    private val epgNext: Map<String, EpgParser.Programme?> = emptyMap(),
    private val onLongClick: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.channelName)
        val groupText: TextView = view.findViewById(R.id.channelGroup)
        val logoImage: ImageView = view.findViewById(R.id.channelLogo)
        val epgText: TextView = view.findViewById(R.id.channelEpg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = channels[position]
        holder.nameText.text = ch.name
        holder.groupText.text = ch.group ?: ""
        holder.groupText.visibility = if (ch.group.isNullOrBlank()) View.GONE else View.VISIBLE

        if (!ch.logo.isNullOrBlank()) {
            holder.logoImage.load(ch.logo) {
                crossfade(true)
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }
        } else {
            holder.logoImage.setImageResource(R.drawable.ic_channel_placeholder)
        }

        // EPG con orari
        val now = epgData[ch.name] ?: epgData[ch.url]
        val next = epgNext[ch.name] ?: epgNext[ch.url]
        when {
            now != null && next != null -> {
                val nowTime = if (now.stop > 0) "fino ${timeFmt.format(Date(now.stop))}" else ""
                val nextTime = if (next.start > 0) "${timeFmt.format(Date(next.start))}" else ""
                holder.epgText.text = "● ${now.title} ($nowTime)\n$nextTime ▶ ${next.title}"
                holder.epgText.visibility = View.VISIBLE
            }
            now != null -> {
                val nowTime = if (now.stop > 0) "fino ${timeFmt.format(Date(now.stop))}" else ""
                holder.epgText.text = "● ${now.title} ($nowTime)"
                holder.epgText.visibility = View.VISIBLE
            }
            else -> holder.epgText.visibility = View.GONE
        }

        val nowTitle = now?.let { prog ->
            val t = if (prog.stop > 0) "fino ${timeFmt.format(Date(prog.stop))}" else ""
            val d = if (prog.description.isNotBlank()) "\n${prog.description.take(150)}" else ""
            "● ${prog.title} ($t)$d"
        }
        val nextTitle = next?.let { prog ->
            val t = if (prog.start > 0) "${timeFmt.format(Date(prog.start))} " else ""
            "$t▶ ${prog.title}"
        }
        holder.itemView.setOnClickListener { onChannelClick(ch, nowTitle, nextTitle) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(ch); true }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
    }

    override fun getItemCount(): Int = channels.size
}
