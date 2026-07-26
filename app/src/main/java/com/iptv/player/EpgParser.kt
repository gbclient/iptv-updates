package com.iptv.player

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.*

object EpgParser {

    data class Programme(
        val channel: String,
        val title: String,
        val description: String = "",
        val start: Long = 0,
        val stop: Long = 0
    )

    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    fun parse(xml: String): Pair<List<Programme>, Map<String, String>> {
        val programmes = mutableListOf<Programme>()
        val channelNames = mutableMapOf<String, String>() // channel id -> display name
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var channelTag = ""
            var title = ""
            var desc = ""
            var start = 0L
            var stop = 0L
            var inChannel = false
            var currentChannelId = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tag) {
                            "channel" -> {
                                inChannel = true
                                currentChannelId = parser.getAttributeValue(null, "id") ?: ""
                            }
                            "display-name" -> {
                                if (inChannel && currentChannelId.isNotBlank()) {
                                    val name = parser.nextText()
                                    if (channelNames[currentChannelId] == null) {
                                        channelNames[currentChannelId] = name
                                    }
                                }
                            }
                            "programme" -> {
                                inChannel = false
                                channelTag = parser.getAttributeValue(null, "channel") ?: ""
                                start = parseTime(parser.getAttributeValue(null, "start"))
                                stop = parseTime(parser.getAttributeValue(null, "stop"))
                                title = ""; desc = ""
                            }
                            "title" -> title = parser.nextText()
                            "desc" -> desc = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag == "channel") inChannel = false
                        if (tag == "programme" && title.isNotBlank() && channelTag.isNotBlank()) {
                            programmes.add(Programme(channelTag, title, desc, start, stop))
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(programmes, channelNames)
    }

    private fun parseTime(time: String?): Long {
        if (time == null) return 0
        return try {
            // Formato: 20240101180000 +0100
            val clean = time.trim().replace("(?<=\\d)\\s+(?=[+-])".toRegex(), " ")
            dateFormat.parse(clean)?.time ?: 0
        } catch (e: Exception) { 0 }
    }

    fun getNowNext(programmes: List<Programme>, tvgId: String?, channelName: String): Pair<Programme?, Programme?> {
        val now = System.currentTimeMillis()
        val key = channelName.lowercase().replace("\\s+".toRegex(), "")
        
        // Filtra e ordina solo i programmi di questo canale
        val channelProgs = programmes.filter { prog ->
            val pk = prog.channel.lowercase().replace("\\s+".toRegex(), "")
            pk == key || prog.channel.equals(channelName, ignoreCase = true)
        }.sortedBy { it.start }

        var nowProg: Programme? = null
        for (p in channelProgs) {
            if (p.start <= now && p.stop > now) {
                nowProg = p
                val nextStart = p.stop
                return Pair(nowProg, channelProgs.firstOrNull { it.start >= nextStart })
            }
        }
        return Pair(null, null)
    }

    fun download(context: Context, epgUrl: String): String? {
        return try {
            val url = java.net.URL(epgUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            // Supporto gzip
            val input = if (conn.contentEncoding == "gzip") {
                java.util.zip.GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            val body = java.io.BufferedReader(java.io.InputStreamReader(input, "UTF-8")).readText()
            conn.disconnect()
            body
        } catch (e: Exception) {
            null
        }
    }
}
