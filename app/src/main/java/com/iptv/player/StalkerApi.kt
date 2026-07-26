package com.iptv.player

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.iptv.player.model.Channel
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object StalkerApi {

    data class StalkerConfig(val server: String, val mac: String)

    data class StalkerGenre(val id: String = "", val title: String = "")

    data class StalkerResult(
        val channels: List<Channel> = emptyList(),
        val categories: List<Category> = emptyList(),
        val error: String? = null
    )

    private data class JsResponse(@SerializedName("js") val js: JsData? = null)
    private data class JsData(
        val error: String? = null,
        val token: String? = null,
        @SerializedName("data") val rawData: Any? = null
    )

    private val gson = Gson()
    private var cookies = ""

    fun loadChannels(config: StalkerConfig, context: Context?): StalkerResult {
        cookies = ""
        return try {
            val base = config.server.trimEnd('/')
            val mac = config.mac.replace(":", "").replace("-", "").replace(" ", "").uppercase()

            // 1. Handshake  
            val hsUrl = "$base/server/load.php?type=stb&action=handshake&JsHttpRequest=1&mac=$mac&stb_lang=en&timezone=UTC&token="
            val hs = apiGet(hsUrl)
            if (hs == null) return StalkerResult(error = "Server non raggiungibile. Verifica URL: ${base.take(50)}")
            if (hs.js?.error != null) return StalkerResult(error = hs.js.error)
            val token = hs.js?.token ?: return StalkerResult(error = "Token non ricevuto")

            // 2. Get profile (per attivare la sessione)
            apiGet("$base/server/load.php?type=stb&action=get_profile&JsHttpRequest=1&mac=$mac&stb_lang=en&timezone=UTC&token=$token")

            // 3. Get genres  
            val genUrl = "$base/server/load.php?type=itv&action=get_genres&JsHttpRequest=1&mac=$mac&token=$token"
            val genResp = apiGet(genUrl)
            val genres = parseGenres(genResp)

            // 4. Get all channels
            val chUrl = "$base/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1&mac=$mac&token=$token"
            val chResp = apiGet(chUrl)
            val rawChannels = parseChannels(chResp)

            val genreMap = genres.associate { it.id to it.title }

            val channels = rawChannels.mapIndexed { i, it ->
                val chId = it.id.ifBlank { "$i" }
                val linkUrl = "$base/server/load.php?type=itv&action=create_link&cmd=$chId&JsHttpRequest=1&mac=$mac&token=$token"
                Channel(
                    name = it.name.ifBlank { "Canale $i" },
                    url = linkUrl,
                    logo = it.logo,
                    group = genreMap[chId] ?: "Altro"
                )
            }

            val cats = genres.map { g ->
                Category(g.title, rawChannels.count { it.id == g.id })
            }.sortedByDescending { it.count }

            StalkerResult(channels = channels, categories = cats)
        } catch (e: Exception) {
            StalkerResult(error = e.message ?: "Errore")
        }
    }

    private fun parseGenres(resp: JsResponse?): List<StalkerGenre> {
        if (resp?.js?.rawData == null) return emptyList()
        return try {
            val json = gson.toJson(resp.js.rawData)
            val type = object : com.google.gson.reflect.TypeToken<List<StalkerGenre>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private data class RawChannel(
        val id: String = "", val name: String = "", val number: String = "",
        val logo: String? = null, val cmd: String? = null
    )

    private fun parseChannels(resp: JsResponse?): List<RawChannel> {
        if (resp?.js?.rawData == null) return emptyList()
        return try {
            val json = gson.toJson(resp.js.rawData)
            val type = object : com.google.gson.reflect.TypeToken<List<RawChannel>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun apiGet(url: String): JsResponse? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)")
                .header("Accept", "*/*")
                .header("Cookie", cookies.ifBlank { "" })
                .build()
            val resp = client.newCall(req).execute()
            // Salva cookie dalla risposta
            val setCookie = resp.headers("Set-Cookie")
            if (setCookie.isNotEmpty()) {
                cookies = setCookie.joinToString("; ") { it.substringBefore(";") }
            }
            val body = resp.body?.string() ?: return null
            gson.fromJson(body, JsResponse::class.java)
        } catch (e: Exception) { null }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
