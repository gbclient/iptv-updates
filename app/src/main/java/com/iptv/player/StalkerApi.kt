package com.iptv.player

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.iptv.player.model.Channel
import okhttp3.*
import java.net.URL
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

    class ApiResult(
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
                        val did = md5(macFmt.replace(":", "").replace("-", ""))
                        for (urlSuffix in listOf(
                            "?type=$type&action=handshake&JsHttpRequest=1&mac=$macFmt&stb_lang=en&timezone=UTC&token=&device_id=$did",
                            "?type=$type&action=handshake&JsHttpRequest=1&mac=$macFmt&stb_lang=en&timezone=UTC&token=",
                            "?type=$type&action=handshake&mac=$macFmt&stb_lang=en&timezone=UTC&token="
                        )) {
                            if (hs != null) break
                            val url = "$ep$urlSuffix"
                            android.util.Log.i("STALKER", "HS → $url")
                            hs = apiGet(url, ua)
                            if (hs != null) { usedEp = ep; usedMac = macFmt; break }
                        }
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

        // Prova l'URL esatto prima
        eps.add(0, base)
        if (root != base && !eps.contains(root)) eps.add(1, root)

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
    private var lastUsedEp = ""
    private var lastUsedMac = ""
    private var lastUsedType = "stb"
    private var lastToken: String? = null

    private fun buildReq(url: String, ua: String): Request {
        val host = try { URL(url).host } catch (_: Exception) { "" }
        return Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Referer", "http://$host/c/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Connection", "keep-alive")
            .build()
    }

    private fun isCloudflare(body: String): Boolean {
        return body.contains("cf-browser-verification", ignoreCase = true) ||
               body.contains("__cf_chl_opt", ignoreCase = true) ||
               body.contains("Just a moment", ignoreCase = true) ||
               body.contains("checking your browser", ignoreCase = true) ||
               body.contains("Cloudflare", ignoreCase = true) && body.contains("challenge", ignoreCase = true)
    }

    private fun fetchChannelsStreaming(url: String): List<RawChannel> {
        return try {
            val resp = client.newCall(buildReq(url, "Mozilla/5.0 (QtEmbedded; U; Linux; C)")).execute()
            if (resp.code != 200) { lastError = "HTTP ${resp.code}"; return emptyList() }
            val body = resp.body ?: run { resp.close(); return emptyList() }
            val raw = body.string()
            resp.close()
            if (isCloudflare(raw)) { lastError = "Cloudflare blocca il server - prova VPN (Paesi Bassi/Germania)"; return emptyList() }

            val json = try { JsonParser.parseString(raw) } catch (_: Exception) { return emptyList() }

            if (json.isJsonArray) return parseChannels(json.asJsonArray)

            val obj = json.asJsonObject
            val js = obj.get("js")
            if (js != null) {
                if (js.isJsonArray) return parseChannels(js.asJsonArray)
                if (js.isJsonObject) {
                    val data = js.asJsonObject.get("data")
                    if (data != null && data.isJsonArray) return parseChannels(data.asJsonArray)
                    val arr = findArrayInObject(js.asJsonObject)
                    if (arr != null) return parseChannels(arr)
                }
            }
            val direct = findArrayInObject(obj)
            if (direct != null) return parseChannels(direct)
            val itv = obj.get("itv")
            if (itv != null && itv.isJsonArray) return parseChannels(itv.asJsonArray)

            emptyList()
        } catch (e: Exception) {
            android.util.Log.e("STALKER", "channels error: ${e.message}")
            emptyList()
        }
    }

    private fun apiGet(url: String, ua: String = "Mozilla/5.0 (QtEmbedded; U; Linux; C)"): ApiResult? {
        lastError = null
        return try {
            val resp = client.newCall(buildReq(url, ua)).execute()
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
            if (isCloudflare(body)) {
                lastError = "Cloudflare blocca il server - prova VPN (Paesi Bassi/Germania)"
                android.util.Log.w("STALKER", "Cloudflare detected")
                return null
            }
            if (code != 200) {
                android.util.Log.w("STALKER", "HTTP $code: ${body.take(500)}")
                if (code == 403) { lastError = "HTTP 403 - Accesso negato (blocco IP o server richiede VPN)"; return null }
            }

            val json = try { JsonParser.parseString(body) } catch (_: Exception) { return null }
            if (json.isJsonArray) return ApiResult(array = json.asJsonArray, rawBody = body.take(1000))
            if (!json.isJsonObject) return null
            val obj = json.asJsonObject

            val js = obj.get("js")
            if (js != null) {
                if (js.isJsonArray) return ApiResult(array = js.asJsonArray, rawBody = body.take(1000))
                if (js.isJsonObject) {
                    val jso = js.asJsonObject
                    val token = jso.get("token")?.asString
                    if (token != null) return ApiResult(error = jso.get("error")?.asString ?: "", token = token, rawBody = body.take(1000))
                    val arr = findArrayInObject(jso)
                    if (arr != null) return ApiResult(array = arr, rawBody = body.take(1000))
                    return ApiResult(error = jso.get("error")?.asString ?: "", rawBody = body.take(1000))
                }
            }

            val token = obj.get("token")?.asString
            if (token != null) return ApiResult(token = token, rawBody = body.take(1000))

            val arr = findArrayInObject(obj)
            if (arr != null) return ApiResult(array = arr, rawBody = body.take(1000))

            ApiResult(rawBody = body.take(1000))
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
            android.util.Log.i("STALKER", "resolve: $cmdUrl")
            val resp = client.newCall(buildReq(cmdUrl, "Mozilla/5.0 (QtEmbedded; U; Linux; C)")).execute()
            val code = resp.code
            val body = resp.body?.string()
            android.util.Log.i("STALKER", "resolve HTTP $code → ${body?.take(500)}")
            if (code != 200) { lastError = "resolve HTTP $code"; return null }
            if (body.isNullOrBlank()) { lastError = "resolve body vuoto"; return null }

            val json = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { return null }
            val js = json.get("js")
            var cmd: String? = when {
                js?.isJsonObject == true -> js.asJsonObject.get("cmd")?.asString
                js?.isJsonArray == true -> js.asJsonArray.firstOrNull()?.asJsonObject?.get("cmd")?.asString
                else -> null
            }
            if (cmd.isNullOrBlank()) cmd = json.get("cmd")?.asString
            if (cmd.isNullOrBlank()) cmd = json.get("url")?.asString
            if (cmd.isNullOrBlank()) {
                android.util.Log.w("STALKER", "no cmd in response: ${body.take(500)}")
                lastError = "risposta senza cmd"
                return null
            }
            val cleaned = cmd.trim().replace("ffmpeg ", "").replace("fork ", "").trim()
            if (cleaned != cmd) android.util.Log.i("STALKER", "cmd cleaned: $cmd -> $cleaned")
            if (!cleaned.contains("://")) { lastError = "cmd non e' URL: $cleaned"; return null }
            cleaned
        } catch (e: Exception) {
            val msg = e.message ?: ""
            lastError = msg
            android.util.Log.e("STALKER", "resolver error: $msg")
            null
        }
    }

    fun getLastError(): String? = lastError

    fun handshakeOnly(config: StalkerConfig): ApiResult? {
        cookieStore.clear()
        lastUsedEp = ""
        lastUsedMac = ""
        val rawMac = config.mac.replace(":", "").replace("-", "").replace(" ", "").uppercase()
        val endpoints = generateEndpoints(config.server.trimEnd('/'))
        val rawMacArr = arrayListOf(rawMac, config.mac.trim())
        if (rawMacArr.none { it.contains(":") }) {
            rawMacArr.add(rawMac.chunked(2).joinToString(":"))
        }
        for (ep in endpoints) {
            for (macFmt in rawMacArr) {
                for ((ua, type) in listOf(
                    "Mozilla/5.0 (QtEmbedded; U; Linux; C)" to "stb",
                    "Mozilla/5.0 (QtEmbedded; U; Linux; Android_1.0; en-us;)" to "mag"
                )) {
                    val did = md5(macFmt.replace(":", "").replace("-", ""))
                    for (urlSuffix in listOf(
                        "?type=$type&action=handshake&JsHttpRequest=1&mac=$macFmt&stb_lang=en&timezone=UTC&token=&device_id=$did",
                        "?type=$type&action=handshake&JsHttpRequest=1&mac=$macFmt&stb_lang=en&timezone=UTC&token=",
                        "?type=$type&action=handshake&mac=$macFmt&stb_lang=en&timezone=UTC&token="
                    )) {
                        val url = "$ep$urlSuffix"
                        val res = apiGet(url, ua)
                        if (res != null && res.token != null) {
                            lastUsedEp = ep
                            lastUsedMac = macFmt
                            lastUsedType = type
                            lastToken = res.token
                            return res
                        }
                    }
                }
            }
        }
        return null
    }

    private fun getApiBase(): String {
        if (lastUsedEp.isNotBlank()) return lastUsedEp
        return generateEndpoints("").firstOrNull() ?: ""
    }

    private fun getApiMac(): String {
        if (lastUsedMac.isNotBlank()) return lastUsedMac
        return ""
    }

    fun fetchGenres(token: String): List<StalkerGenre> {
        val ep = getApiBase()
        val mac = getApiMac()
        if (ep.isBlank() || mac.isBlank()) return emptyList()
        apiGet("$ep?type=stb&JsHttpRequest=1&mac=$mac&action=get_profile&stb_lang=en&timezone=UTC&token=$token")
        val genResult = apiGet("$ep?type=itv&JsHttpRequest=1&mac=$mac&action=get_genres&token=$token")
        return parseGenres(genResult?.array)
    }

    fun fetchChannelsOnly(token: String, genres: List<StalkerGenre>): StalkerResult {
        val ep = getApiBase()
        val mac = getApiMac()
        if (ep.isBlank() || mac.isBlank()) return StalkerResult(error = "Nessun endpoint valido")
        val rawChannels = fetchChannelsStreaming("$ep?type=itv&JsHttpRequest=1&mac=$mac&action=get_all_channels&token=$token")
        val genreMap = genres.associate { it.id to it.title }
        val streamBase = "$ep?type=itv&mac=$mac"
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
        return StalkerResult(channels = channels, categories = cats)
    }

    fun fetchGenres(config: StalkerConfig, token: String): List<StalkerGenre> {
        val rawMac = config.mac.replace(":", "").replace("-", "").replace(" ", "").uppercase()
        val usedEp = generateEndpoints(config.server.trimEnd('/')).firstOrNull() ?: config.server.trimEnd('/')
        val apiItv = "$usedEp?type=itv&JsHttpRequest=1&mac=$rawMac"
        apiGet("$usedEp?type=stb&JsHttpRequest=1&mac=$rawMac&action=get_profile&stb_lang=en&timezone=UTC&token=$token")
        val genResult = apiGet("$apiItv&action=get_genres&token=$token")
        return parseGenres(genResult?.array)
    }

    fun fetchChannelsOnly(config: StalkerConfig, token: String, genres: List<StalkerGenre>): StalkerResult {
        val rawMac = config.mac.replace(":", "").replace("-", "").replace(" ", "").uppercase()
        val usedEp = generateEndpoints(config.server.trimEnd('/')).firstOrNull() ?: config.server.trimEnd('/')
        val apiItv = "$usedEp?type=itv&JsHttpRequest=1&mac=$rawMac"
        val rawChannels = fetchChannelsStreaming("$apiItv&action=get_all_channels&token=$token")
        val genreMap = genres.associate { it.id to it.title }
        val streamBase = "$usedEp?type=itv&mac=$rawMac"
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
        return StalkerResult(channels = channels, categories = cats)
    }

    fun resolveStreamUrl(channelUrl: String): String {
        if (!channelUrl.contains("create_link")) return channelUrl
        val streamUrl = resolveCmdUrl(channelUrl) ?: return channelUrl
        if (streamUrl.contains("token=") || streamUrl.contains("ticket=") || streamUrl.contains("auth=")) return streamUrl
        val portalHost = extractHost(channelUrl)
        val streamHost = extractHost(streamUrl)
        if (portalHost == streamHost) {
            val token = channelUrl.split("&").firstOrNull { it.startsWith("token=") }?.substringAfter("token=")?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            if (token != null) {
                val sep = if (streamUrl.contains('?')) "&" else "?"
                return "${streamUrl}${sep}token=${URLEncoder.encode(token, "UTF-8")}"
            }
        }
        return streamUrl
    }

    // Per proxy: handshake fresco per ogni richiesta, cosi ogni client ha sessione indipendente
    fun freshResolveForProxy(server: String, mac: String, channelUrl: String): String? {
        val hs = handshakeOnly(StalkerConfig(server, mac)) ?: run { lastError = "handshake fresco fallito"; return null }
        val newToken = hs.token ?: return null
        // Estrae cmd dal channelUrl originale
        val cmd = channelUrl.split("&").firstOrNull { it.startsWith("cmd=") }?.substringAfter("cmd=")?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } } ?: return null
        val usedMac = lastUsedMac.ifBlank { mac.replace(":", "").replace("-", "").toCharArray().mapIndexed { i, c -> if (i > 0 && i % 2 == 0) ":$c" else "$c" }.joinToString("") }
        val createUrl = "${lastUsedEp}?type=itv&mac=$usedMac&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}&token=${URLEncoder.encode(newToken, "UTF-8")}"
        val streamUrl = resolveCmdUrl(createUrl) ?: return null
        if (!streamUrl.contains("token=") && !streamUrl.contains("ticket=") && !streamUrl.contains("auth=")) {
            val sep = if (streamUrl.contains('?')) "&" else "?"
            return "${streamUrl}${sep}token=${URLEncoder.encode(newToken, "UTF-8")}"
        }
        return streamUrl
    }

    // Usa la sessione esistente (ultimo handshake) per risolvere un create_link
    // Ogni chiamata genera un nuovo stream URL senza creare una nuova sessione
    fun resolveCreateLink(cmd: String): String? {
        val ep = getApiBase()
        val mac = getApiMac()
        val token = lastToken
        if (ep.isBlank() || mac.isBlank() || token == null) {
            lastError = "nessuna sessione attiva"
            return null
        }
        val usedMac = mac.replace(":", "").replace("-", "").toCharArray().mapIndexed { i, c -> if (i > 0 && i % 2 == 0) ":$c" else "$c" }.joinToString("")
        val createUrl = "$ep?type=itv&mac=$usedMac&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"
        val streamUrl = resolveCmdUrl(createUrl) ?: run {
            // Se fallisce, prova con handshake fresco
            lastError = "create_link fallito con sessione esistente"
            return null
        }
        if (!streamUrl.contains("token=") && !streamUrl.contains("ticket=") && !streamUrl.contains("auth=")) {
            val sep = if (streamUrl.contains('?')) "&" else "?"
            return "${streamUrl}${sep}token=${URLEncoder.encode(token, "UTF-8")}"
        }
        return streamUrl
    }

    // Mantiene viva la sessione con get_profile
    fun keepSessionAlive(config: StalkerConfig): Boolean {
        val ep = getApiBase()
        val mac = getApiMac()
        val token = lastToken
        if (ep.isBlank() || mac.isBlank() || token == null) return false
        val url = "$ep?type=stb&JsHttpRequest=1&mac=$mac&action=get_profile&stb_lang=en&timezone=UTC&token=${URLEncoder.encode(token, "UTF-8")}"
        val res = apiGet(url)
        if (res?.error != null) {
            // Sessione scaduta, rifai handshake
            val hs = handshakeOnly(config)
            return hs?.token != null
        }
        return res != null
    }
}
