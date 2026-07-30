package com.iptv.player

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iptv.player.model.Channel
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

object StalkerProxy {

    private const val FIREBASE_BASE = "https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app"
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private var polling = false

    data class ProxyProvider(
        val name: String = "",
        val channels: String = "[]",
        val alive: Long = 0L,
        val portal: String = ""
    )

    data class ProxyRequest(
        val requester: String = "",
        val channel: String = "",
        val channelName: String = "",
        val resolved: String? = null,
        val ts: Long = 0L
    )

    private var stalkerServer = ""
    private var stalkerMac = ""
    private var relayServer: StreamRelayServer? = null

    fun registerAsProvider(ctx: Context, deviceCode: String, portalName: String, portalUrl: String, channels: List<Channel>, stalkerMac: String = "") {
        this.stalkerServer = portalUrl; this.stalkerMac = stalkerMac
        // Avvia relay server
        try {
            val relay = StreamRelayServer(8889)
            relay.start()
            relayServer = relay
            Log.i("STALKER_PROXY", "relay server started on port 8889, IP: ${relay.getLocalIpAddress()}")
        } catch (e: Exception) {
            Log.e("STALKER_PROXY", "relay start error", e)
        }
        executor.execute {
            try {
                val chanJson = gson.toJson(channels)
                val provider = gson.toJson(mapOf(
                    "name" to portalName,
                    "channels" to chanJson,
                    "alive" to System.currentTimeMillis(),
                    "portal" to portalUrl
                ))
                val url = URL("$FIREBASE_BASE/proxy/providers/$deviceCode.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"; conn.doOutput = true
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.outputStream.write(provider.toByteArray())
                Log.i("STALKER_PROXY", "register: HTTP ${conn.responseCode}")
                conn.disconnect()

                polling = true
                startPolling(ctx, deviceCode, channels)
            } catch (e: Exception) {
                Log.e("STALKER_PROXY", "register error", e)
            }
        }
    }

    fun unregister(deviceCode: String) {
        polling = false
        relayServer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.clear()
        }
        relayServer = null
        executor.execute {
            try {
                val url = URL("$FIREBASE_BASE/proxy/providers/$deviceCode.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun startPolling(ctx: Context, deviceCode: String, channels: List<Channel>) {
        executor.execute {
            var lastCheck = ""
            var beatCount = 0
            while (polling) {
                try {
                    // Heartbeat ogni 9s
                    beatCount++
                    if (beatCount % 3 == 0) {
                        try {
                            val hbUrl = URL("$FIREBASE_BASE/proxy/providers/$deviceCode/alive.json")
                            val hbConn = hbUrl.openConnection() as HttpURLConnection
                            hbConn.requestMethod = "PUT"; hbConn.doOutput = true
                            hbConn.connectTimeout = 3000; hbConn.readTimeout = 3000
                            hbConn.outputStream.write(System.currentTimeMillis().toString().toByteArray())
                            hbConn.responseCode; hbConn.disconnect()
                        } catch (_: Exception) {}
                    }
                    val url = URL("$FIREBASE_BASE/proxy/requests.json?orderBy=\"\$key\"")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 10000
                    val body = if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else ""
                    conn.disconnect()

                    if (body.isNotBlank() && body != "null") {
                        try {
                            val mapType = object : TypeToken<Map<String, Any>>() {}.type
                            val requests: Map<String, Any> = gson.fromJson(body, mapType)
                            for ((reqId, reqAny) in requests) {
                                if (reqAny !is Map<*, *>) continue
                                val reqMap = reqAny as Map<String, Any>
                                if (reqMap["resolved"] != null) continue
                                val requester = reqMap["requester"] as? String ?: continue
                                val channelUrl = reqMap["channel"] as? String ?: continue
                                val channelName = reqMap["channelName"] as? String ?: ""
                                if (requester == deviceCode) continue
                                val resolved = if (stalkerServer.isNotBlank() && stalkerMac.isNotBlank())
                                    StalkerApi.freshResolveForProxy(stalkerServer, stalkerMac, channelUrl)
                                else
                                    StalkerApi.resolveStreamUrl(channelUrl)
                                val resolveErr = StalkerApi.getLastError()
                                val resolvedVal = if (resolved != null && resolved != channelUrl && resolved.isNotBlank())
                                    resolved
                                else
                                    "_FAIL_:${resolveErr ?: "unknown"}"
                                val updateUrl = URL("$FIREBASE_BASE/proxy/requests/$reqId.json")
                                val updateConn = updateUrl.openConnection() as HttpURLConnection
                                updateConn.requestMethod = "PATCH"; updateConn.doOutput = true
                                updateConn.connectTimeout = 3000; updateConn.readTimeout = 3000
                                updateConn.outputStream.write(gson.toJson(mapOf("resolved" to resolvedVal)).toByteArray())
                                Log.i("STALKER_PROXY", "resolved $channelName -> $resolvedVal (HTTP ${updateConn.responseCode})")
                                updateConn.disconnect()
                            }
                        } catch (e: Exception) {
                            Log.e("STALKER_PROXY", "parse requests error", e)
                        }
                    }

                    Thread.sleep(3000)
                } catch (e: Exception) {
                    Log.e("STALKER_PROXY", "poll error", e)
                }
            }
        }
    }

    fun getProviders(): List<Pair<String, ProxyProvider>> {
        return try {
            val url = URL("$FIREBASE_BASE/proxy/providers.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val body = if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()
            if (body.isBlank() || body == "null") return emptyList()
            val mapType = object : TypeToken<Map<String, Any>>() {}.type
            val providers: Map<String, Any> = gson.fromJson(body, mapType)
            providers.mapNotNull { (key, value) ->
                if (value !is Map<*, *>) return@mapNotNull null
                val m = value as Map<String, Any>
                val name = m["name"] as? String ?: key
                val channels = m["channels"] as? String ?: "[]"
                val alive = (m["alive"] as? Double)?.toLong() ?: 0L
                val portal = m["portal"] as? String ?: ""
                val isAlive = System.currentTimeMillis() - alive < 15000
                if (isAlive) key to ProxyProvider(name, channels, alive, portal) else null
            }
        } catch (e: Exception) {
            Log.e("STALKER_PROXY", "getProviders error", e)
            emptyList()
        }
    }

    fun loadProviderChannels(provider: ProxyProvider): List<Channel> {
        return try {
            val type = object : TypeToken<List<Channel>>() {}.type
            gson.fromJson(provider.channels, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun requestChannel(deviceCode: String, providerCode: String, channel: Channel, timeoutMs: Long = 20000): String? {
        // providerCode unused but allows different providers in future
        val reqId = UUID.randomUUID().toString()
        val request = mapOf(
            "requester" to deviceCode,
            "channel" to channel.url,
            "channelName" to channel.name,
            "resolved" to null as String?,
            "ts" to System.currentTimeMillis()
        )
        try {
            val url = URL("$FIREBASE_BASE/proxy/requests/$reqId.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"; conn.doOutput = true
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.outputStream.write(gson.toJson(request).toByteArray())
            conn.responseCode; conn.disconnect()
        } catch (e: Exception) {
            Log.e("STALKER_PROXY", "request error", e)
            return null
        }

        val pollStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - pollStart < timeoutMs) {
            try {
                Thread.sleep(500)
                val url = URL("$FIREBASE_BASE/proxy/requests/$reqId/resolved.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                val resolved = if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else ""
                conn.disconnect()
                if (resolved.isNotBlank() && resolved != "null") {
                    val clean = resolved.trim('"')
                    if (clean.startsWith("http")) return clean
                    if (clean.startsWith("_FAIL_:")) return clean // pass error to caller
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
