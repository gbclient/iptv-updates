package com.iptv.player

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.iptv.player.model.Channel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object XtreamApi {

    data class XCConfig(val server: String, val username: String, val password: String)

    data class XCCategory(
        @SerializedName("category_id") val id: String,
        @SerializedName("category_name") val name: String
    )

    data class XCStream(
        @SerializedName("stream_id") val streamId: Int = 0,
        @SerializedName("series_id") val seriesId: Int = 0,
        val name: String = "",
        @SerializedName("stream_icon") val icon: String? = null,
        @SerializedName("cover") val cover: String? = null,
        @SerializedName("category_id") val categoryId: String = "",
        @SerializedName("container_extension") val containerExt: String = "mp4"
    ) {
        val id: Int get() = if (streamId > 0) streamId else seriesId
        val logo: String? get() = icon ?: cover
    }

    data class ApiResult(
        val channels: List<Channel> = emptyList(),
        val categories: List<Category> = emptyList(),
        val error: String? = null
    )

    private val gson = Gson()

    fun loadChannels(config: XCConfig, type: String, context: Context?): ApiResult {
        return try {
            val base = "${config.server}/player_api.php?username=${config.username}&password=${config.password}"
            val catsJson = httpGet("$base&action=get_${type}_categories", context)
            val streamsAction = if (type == "series") "get_series" else "get_${type}_streams"
            val streamsJson = httpGet("$base&action=$streamsAction", context)

            val catType = object : com.google.gson.reflect.TypeToken<List<XCCategory>>() {}.type
            val streamType = object : com.google.gson.reflect.TypeToken<List<XCStream>>() {}.type

            val cats: List<XCCategory> = gson.fromJson(catsJson, catType) ?: emptyList()
            val streams: List<XCStream> = gson.fromJson(streamsJson, streamType) ?: emptyList()
            val catMap = cats.associate { it.id to it.name }

            val channels = streams.map { s ->
                val ext = s.containerExt.ifBlank { "mp4" }
                val groupName = when (type) {
                    "vod" -> "FILM: ${catMap[s.categoryId] ?: ""}"
                    "series" -> "SERIE: ${catMap[s.categoryId] ?: ""}"
                    else -> catMap[s.categoryId] ?: s.categoryId
                }
                Channel(
                    name = s.name,
                    url = buildStreamUrl(config, type, s.id, ext),
                    logo = s.logo,
                    group = groupName
                )
            }

            val categories = cats.map { c ->
                val count = streams.count { it.categoryId == c.id }
                Category(c.name, count)
            }.sortedByDescending { it.count }

            ApiResult(channels = channels, categories = categories)
        } catch (e: Exception) {
            ApiResult(error = e.message ?: "Errore API")
        }
    }

    private fun buildStreamUrl(config: XCConfig, type: String, id: Int, ext: String): String {
        return when (type) {
            "vod" -> "${config.server}/movie/${config.username}/${config.password}/$id.$ext"
            "series" -> "${config.server}/series/${config.username}/${config.password}/$id.$ext"
            else -> "${config.server}/${type}/${config.username}/${config.password}/$id.ts"
        }
    }

    private fun httpGet(urlStr: String, context: Context?): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val prefs = context?.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val ua = prefs?.getString("user_agent", "VLC/3.0.20 LibVLC/3.0.20")
            ?.takeIf { it.isNotBlank() } ?: "VLC/3.0.20 LibVLC/3.0.20"
        conn.setRequestProperty("User-Agent", ua)
        return BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readText()
    }
}
