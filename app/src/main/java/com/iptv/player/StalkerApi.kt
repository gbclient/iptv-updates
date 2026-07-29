package com.iptv.player

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.iptv.player.model.Channel
import okhttp3.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object StalkerApi {

    data class StalkerConfig(val server: String, val mac: String)

    data class StalkerGenre(val id: String = "", val title: String = "")

    data class StalkerResult(
        val channels: List<Channel> = emptyList(),
        val categories: List<Category> = emptyList(),
        val error: String? = null
    )

    private class ApiResult(
        val error: String? = null,
        val token: String? = null,
        val array: JsonArray? = null,
        val rawBody: String? = null
    )

    private val cookieStore = mutableListOf<Cookie>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieStore.addAll(cookies) }
            override fun loadForRequest(url: HttpUrl) = cookieStore.filter { it.matches(url) }
        })
        .build()

    private fun extractHost(url: String): String {
        val cleaned = url.trimEnd('/')
        val afterProtocol = cleaned.substringAfter("://")
        return afterProtocol.substringBefore("/").substringBefore(":")
    }

    private fun geoLookup(host: String): String? {
        return try {
            val rb = Request.Builder().url("http://ip-api.com/json/$host?fields=country").header("User-Agent", "IPTVPlayer/1.0").build()
            val resp = client.newCall(rb).execute()
            if (resp.code != 200) return null
            val body = resp.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            json.get("country")?.asString
        } catch (_: Exception) { null }
    }

    fun loadChannels(config: StalkerConfig, context: Context?): StalkerResult {
        cookieStore.clear()
        return try {
            val userBase = config.server.trimEnd('/')
            val rawMac = config.mac.replace(":", "").replace("-", "").replace(" ", "").uppercase()
            val endpoints = generateEndpoints(userBase)

            val rawMacArr = arrayListOf(rawMac, config.mac.trim())
            if (rawMacArr.none { it.contains(":") }) {
                rawMacArr.add(rawMac.chunked(2).joinToString(":"))
            }
            var hs: ApiResult? = null
            var usedEp = endpoints.first()
            var usedMac = rawMac
            for (ep in endpoints) {
                for (macFmt in rawMacArr) {
                    for ((ua, type) in listOf(
                        "Mozilla/5.0 (QtEmbedded; U; Linux; C)" to "stb",
                        "Mozilla/5.0 (QtEmbedded; U; Linux; Android_1.0; en-us;)" to "mag"
                    )) {
                        if (hs != null) break
                        val url = "$ep?type=$type&action=handshake&JsHttpRequest=1&mac=$macFmt&stb_lang=en&timezone=UTC&token=&device_id=${md5(macFmt.replace(":", "").replace("-", ""))}"
                        android.util.Log.i("STALKER", "HS → $url")
                        hs = apiGet(url, ua)
                        if (hs != null) { usedEp = ep; usedMac = macFmt; break }
                    }
                }
                if (hs != null) break
            }

            if (hs == null || hs.token == null) {
                val err = lastError ?: "handshake fallito"
                val hint = when {
                    err.contains("403") || err.contains("blocco") -> {
                        val country = geoLookup(extractHost(userBase))
                        if (country != null) " - Server in $country blocca IP, prova VPN con $country o paesi vicini"
                        else " - Server blocca IP, prova VPN (paese del server)"
                    }
                    err.contains("512") -> " - MAC/handshake rifiutato, verifica MAC o prova VPN"
                    err.contains("timeout") || err.contains("Timeout") || err.contains("timed out") -> {
                        val country = geoLookup(extractHost(userBase))
                        if (country != null) " - Server in $country non raggiungibile, prova VPN con $country"
                        else " - Server non raggiungibile, prova VPN o verifica URL"
                    }
                    else -> ""
                }
                return StalkerResult(error = "Errore Stalker: $err$hint")
            }
            if (!hs.error.isNullOrBlank()) return StalkerResult(error = hs.error)
            val token = hs.token

            val apiStb = "$usedEp?type=stb&JsHttpRequest=1&mac=$usedMac"
            val apiItv = "$usedEp?type=itv&JsHttpRequest=1&mac=$usedMac"

            apiGet("$apiStb&action=get_profile&stb_lang=en&timezone=UTC&token=$token")

            val genResult = apiGet("$apiItv&action=get_genres&token=$token")
            android.util.Log.i("STALKER", "Genres JSON: ${genResult?.array} | raw: ${genResult?.rawBody}")
            val genres = parseGenres(genResult?.array)

            val rawChannels = fetchChannelsStreaming("$apiItv&action=get_all_channels&token=$token")
            android.util.Log.i("STALKER", "Channels caricati: ${rawChannels.size}")

            if (genResult?.array == null && genres.isEmpty()) {
                android.util.Log.w("STALKER", "genres raw: ${genResult?.rawBody}")
            }
            if (rawChannels.isEmpty()) {
                android.util.Log.w("STALKER", "no channels from streaming api")
            }

            val genreMap = genres.associate { it.id to it.title }

            val streamBase = "$usedEp?type=itv&mac=$usedMac"

            val channels = rawChannels.mapIndexed { i, it ->
                val chId = it.id.ifBlank { "$i" }
                val chCmd = (it.cmd ?: chId).trim().replace("\n", "").replace("\r", "")
                val genreKey = it.tvGenreId ?: it.cmd?.substringBefore("_") ?: chId
                Channel(
                    name = it.name.ifBlank { "Canale $i" },
                    url = "$streamBase&action=create_link&cmd=${URLEncoder.encode(chCmd, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}",
                    logo = it.logo,
                    group = genreMap[genreKey] ?: "Altro"
                )
            }

            val cats = genres.map { g ->
                Category(g.title, channels.count { c -> c.group == g.title })
            }.sortedByDescending { it.count }

            val emptyReason = if (channels.isEmpty()) {
                val country = geoLookup(extractHost(userBase))
                val geo = if (country != null) " [Server in $country]" else ""
                "Nessun canale$geo - Verifica MAC, abbonamento o prova VPN con $country"
            } else null
            StalkerResult(channels = channels, categories = cats, error = emptyReason)
        } catch (e: Exception) {
            StalkerResult(error = e.message ?: "Errore")
        }
    }

    private val API_FILES = listOf("server/load.php", "portal.php", "stalker_portal/server/load.php", "load.php")
    private val API_DIRS = listOf("", "c", "C", "stalker_portal", "stalker_portal/c", "stalker_portal/C")

    private fun generateEndpoints(userBase: String): List<String> {
        val eps = mutableListOf<String>()
        val base = userBase.trimEnd('/')

        val root = base.replace(Regex("^(https?://[^/]+).*", RegexOption.IGNORE_CASE), "$1")

        for (prefix in listOf(root, base)) {
            for (dir in API_DIRS) {
                val p = if (dir.isEmpty()) prefix else "$prefix/$dir"
                for (file in API_FILES) {
                    eps.add("$p/$file")
                }
            }
        }

        eps.add(base)
        if (root != base) eps.add(root)

        return eps.distinct()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun findArrayInObject(obj: JsonObject): JsonArray? {
        for (key in listOf("data", "itv", "channels", "items", "categories")) {
            obj.get(key)?.let { if (it.isJsonArray) return it.asJsonArray }
        }
        return null
    }

    private var lastError: String? = null

    private fun fetchChannelsStreaming(url: String): List<RawChannel> {
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)").header("Accept", "*/*")
            val resp = client.newCall(rb.build()).execute()
            if (resp.code != 200) { lastError = "HTTP ${resp.code}"; return emptyList() }
            val reader = JsonReader(resp.body!!.charStream())
            reader.beginObject()
            val list = mutableListOf<RawChannel>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key == "js") {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val k2 = reader.nextName()
                        if (k2 == "data") {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var id = ""; var chName = ""; var number = ""
                                var logo: String? = null; var cmd: String? = null; var tvGenreId: String? = null
                                while (reader.hasNext()) {
                                    val f = reader.nextName()
                                    when (f) {
                                        "id" -> id = reader.nextString()
                                        "name" -> chName = reader.nextString()
                                        "number" -> number = reader.nextString()
                                        "logo" -> logo = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                                        "cmd" -> cmd = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
                                        "tv_genre_id" -> {
                                            tvGenreId = when (reader.peek()) {
                                                JsonToken.STRING -> reader.nextString()
                                                JsonToken.NUMBER -> reader.nextDouble().toInt().toString()
                                                else -> { reader.nextNull(); null }
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                list.add(RawChannel(id = id, name = chName, number = number, logo = logo, cmd = cmd, tvGenreId = tvGenreId))
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            reader.close()
            resp.close()
            list
        } catch (e: Exception) {
            android.util.Log.e("STALKER", "streaming error: ${e.message}")
            emptyList()
        }
    }

    private fun apiGet(url: String, ua: String = "Mozilla/5.0 (QtEmbedded; U; Linux; C)"): ApiResult? {
        lastError = null
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "*/*")
            val resp = client.newCall(rb.build()).execute()
            val code = resp.code
            val body = resp.body?.string()
            android.util.Log.i("STALKER", "HTTP $code → ${body?.take(500)}")
            if (body.isNullOrBlank()) {
                lastError = when (code) {
                    403 -> "HTTP 403 - Accesso negato (blocco IP o server richiede VPN)"
                    512 -> "HTTP 512 - MAC/handshake rifiutato"
                    else -> "HTTP $code vuoto"
                }
                return null
            }
            if (code != 200) {
                android.util.Log.w("STALKER", "HTTP $code: ${body.take(500)}")
                if (code == 403) { lastError = "HTTP 403 - Accesso negato (blocco IP o server richiede VPN)"; return null }
            }

            val json = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { null }
            if (json == null) {
                android.util.Log.w("STALKER", "Non-JSON: ${body.take(500)}")
                return null
            }
            val js = json.get("js") ?: run {
                android.util.Log.w("STALKER", "No 'js' key: ${body.take(500)}")
                return null
            }

            if (js.isJsonArray) {
                ApiResult(array = js.asJsonArray, rawBody = body.take(1000))
            } else if (js.isJsonObject) {
                val obj = js.asJsonObject
                val token = obj.get("token")?.asString
                if (token != null) {
                    ApiResult(
                        error = obj.get("error")?.asString ?: "",
                        token = token,
                        rawBody = body.take(1000)
                    )
                } else {
                    val arr = findArrayInObject(obj)
                    if (arr != null) {
                        ApiResult(array = arr, rawBody = body.take(1000))
                    } else {
                        ApiResult(error = obj.get("error")?.asString ?: "", rawBody = body.take(1000))
                    }
                }
            } else null
        } catch (e: Exception) {
            val msg = e.message ?: ""
            lastError = when {
                msg.contains("Unable to resolve host") || msg.contains("UnknownHost") -> "Server DNS non risolvibile - verifica URL"
                msg.contains("timeout") || msg.contains("Timeout") || msg.contains("timed out") || msg.contains("connect") -> "Timeout connessione - server irraggiungibile, prova VPN"
                else -> "${e::class.simpleName}: ${e.message}"
            }
            android.util.Log.e("STALKER", "apiGet error: ${e.message}")
            null
        }
    }

    private fun parseGenres(arr: JsonArray?): List<StalkerGenre> {
        if (arr == null) return emptyList()
        return try {
            val limit = arr.size().coerceAtMost(500)
            (0 until limit).map { i ->
                val obj = arr.get(i).asJsonObject
                StalkerGenre(
                    id = obj.get("id")?.asString ?: "",
                    title = obj.get("title")?.asString ?: ""
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private data class RawChannel(
        val id: String = "", val name: String = "", val number: String = "",
        val logo: String? = null, val cmd: String? = null,
        val tvGenreId: String? = null
    )

    private fun parseChannels(arr: JsonArray?): List<RawChannel> {
        if (arr == null) return emptyList()
        return try {
            val limit = arr.size().coerceAtMost(2000)
            (0 until limit).map { i ->
                val obj = arr.get(i).asJsonObject
                RawChannel(
                    id = obj.get("id")?.asString ?: "",
                    name = obj.get("name")?.asString ?: "",
                    number = obj.get("number")?.asString ?: "",
                    logo = obj.get("logo")?.asString,
                    cmd = obj.get("cmd")?.asString,
                    tvGenreId = extractString(obj, "tv_genre_id")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun extractString(obj: JsonObject, key: String): String? {
        val el = obj.get(key) ?: return null
        return when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString
            el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asJsonPrimitive.asNumber.toString()
            else -> null
        }
    }

    private fun resolveCmdUrl(cmdUrl: String): String? {
        return try {
            val rb = Request.Builder().url(cmdUrl).header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)").header("Accept", "*/*")
            val resp = client.newCall(rb.build()).execute()
            if (resp.code != 200) return null
            val body = resp.body?.string() ?: return null
            val json = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { return null }
            val js = json.get("js")
            val cmd = when {
                js?.isJsonObject == true -> js.asJsonObject.get("cmd")?.asString
                js?.isJsonArray == true -> js.asJsonArray.firstOrNull()?.asJsonObject?.get("cmd")?.asString
                else -> null
            }
            if (cmd.isNullOrBlank()) {
                android.util.Log.w("STALKER", "resolved cmd is blank: ${body.take(300)}")
                return null
            }
            val cleaned = cmd.trim().replace("ffmpeg ", "").replace("fork ", "").trim()
            if (cleaned != cmd) android.util.Log.i("STALKER", "cmd cleaned: $cmd -> $cleaned")
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return null
            cleaned
        } catch (e: Exception) {
            android.util.Log.e("STALKER", "resolver error: ${e.message}")
            null
        }
    }

    fun resolveStreamUrl(channelUrl: String): String {
        if (!channelUrl.contains("create_link")) return channelUrl
        val streamUrl = resolveCmdUrl(channelUrl) ?: return channelUrl
        val portalHost = extractHost(channelUrl)
        val streamHost = extractHost(streamUrl)
        if (portalHost == streamHost) {
            val token = channelUrl.split("&").firstOrNull { it.startsWith("token=") }?.substringAfter("token=")?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            if (token != null) {
                val sep = if (streamUrl.contains('?')) "&" else "?"
                return "${streamUrl}${sep}token=${token}"
            }
        }
        return streamUrl
    }
}
