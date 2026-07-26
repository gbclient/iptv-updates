package com.iptv.player

import android.content.Context
import com.iptv.player.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.*
import java.util.concurrent.TimeUnit

object M3UParser {

    data class ParseResult(val channels: List<Channel>, val error: String? = null, val epgUrl: String? = null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun parseFromUrl(m3uUrl: String, context: Context? = null): ParseResult {
        return try {
            val prefs = context?.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            val userAgent = prefs?.getString("user_agent", "VLC/3.0.20 LibVLC/3.0.20")
                ?.takeIf { it.isNotBlank() } ?: "VLC/3.0.20 LibVLC/3.0.20"
            val referer = prefs?.getString("referer", "")?.takeIf { it.isNotBlank() } ?: extractBaseUrl(m3uUrl)
            val origin = prefs?.getString("origin", "")?.takeIf { it.isNotBlank() } ?: extractBaseUrl(m3uUrl)
            val cookies = prefs?.getString("cookies", "")?.takeIf { it.isNotBlank() }
            val xff = prefs?.getString("x_forwarded_for", "")?.takeIf { it.isNotBlank() }

            val builder = Request.Builder().url(m3uUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")

            referer?.let { builder.header("Referer", it) }
            origin?.let { builder.header("Origin", it) }
            cookies?.let { builder.header("Cookie", it) }
            xff?.let { builder.header("X-Forwarded-For", it) }

            prefs?.all?.forEach { (key, value) ->
                if (key.startsWith("hdr_") && value is String && value.isNotBlank()) {
                    builder.header(key.removePrefix("hdr_"), value)
                }
            }

            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) {
                return ParseResult(emptyList(), "HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            if (body.isBlank()) return ParseResult(emptyList(), "Risposta vuota")
            val epgUrl = extractEpgFromM3u(body, m3uUrl)
            ParseResult(parseLines(body.lines()), epgUrl = epgUrl)
        } catch (e: java.net.SocketTimeoutException) {
            ParseResult(emptyList(), "Timeout - server non raggiungibile")
        } catch (e: java.net.UnknownHostException) {
            ParseResult(emptyList(), "DNS: host sconosciuto")
        } catch (e: javax.net.ssl.SSLException) {
            ParseResult(emptyList(), "Errore SSL: ${e.message}")
        } catch (e: Exception) {
            ParseResult(emptyList(), e.message ?: "Errore")
        }
    }

    private fun extractBaseUrl(url: String): String? {
        return try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}${if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""}/"
        } catch (e: Exception) { null }
    }

    private fun parseLines(lines: List<String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null

        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("#EXTINF:") -> {
                    currentName = extract(t, "tvg-name")
                        ?: t.substringAfterLast(",", "").trim().ifEmpty { null }
                        ?: "Canale sconosciuto"
                    currentLogo = extract(t, "tvg-logo")
                    currentGroup = extract(t, "group-title")
                }
                t.startsWith("#") || t.isEmpty() -> continue
                else -> {
                    channels.add(Channel(name = currentName ?: "Canale ${channels.size + 1}",
                        url = t, logo = currentLogo, group = currentGroup))
                    currentName = null; currentLogo = null; currentGroup = null
                }
            }
        }
        return channels
    }

    private fun extractEpgFromM3u(content: String, m3uUrl: String): String? {
        // 1. Cerca url-tvg nell'intestazione
        for (line in content.lines().take(5)) {
            if (line.startsWith("#EXTM3U")) {
                val m = Regex("url-tvg=\"([^\"]+)\"").find(line)
                val url = m?.groupValues?.getOrNull(1)
                if (!url.isNullOrBlank()) return url
                val m2 = Regex("url-tvg=([^\\s\"]+)").find(line)
                val url2 = m2?.groupValues?.getOrNull(1)
                if (!url2.isNullOrBlank()) return url2
            }
        }
        // 2. Deriva da Xtream Codes: .../get.php?username=X&password=Y → .../xmltv.php?username=X&password=Y
        if (m3uUrl.contains("get.php?")) {
            val params = m3uUrl.substringAfter("?").split("&")
                .filter { it.startsWith("username=") || it.startsWith("password=") }
                .joinToString("&")
            if (params.isNotBlank()) {
                val base = m3uUrl.substringBefore("/get.php?")
                return "$base/xmltv.php?$params"
            }
        }
        return null
    }

    private fun extract(line: String, attr: String): String? {
        val m = Regex("$attr=\"([^\"]*)\"").find(line) ?: return null
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
