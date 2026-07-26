package com.iptv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.MessageDigest
import java.util.concurrent.Executors

class MqttSync(
    private val context: Context,
    private val onConfigReceived: (Config) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    data class Config(
        val m3uUrl: String = "",
        val playlistName: String = "Cloud",
        val isXc: Boolean = false,
        val userAgent: String = "VLC/3.0.20 LibVLC/3.0.20",
        val referer: String = "",
        val origin: String = "",
        val proxyHost: String = "",
        val proxyPort: Int = 0,
        val proxyType: String = "",
        val proxyUser: String = "",
        val proxyPass: String = "",
        val dnsServer: String = "",
        val epgUrl: String = "",
        val customHeaders: Map<String, String> = emptyMap(),
        val cookies: String = "",
        val xForwardedFor: String = ""
    )

    companion object {
        private const val TAG = "MqttSync"
        private const val BROKER = "tcp://broker.hivemq.com:1883"
        private const val TOPIC_PREFIX = "iptvplayer"
    }

    private var mqttClient: MqttClient? = null
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connected = false
    private var reconnectAttempts = 0

    val deviceCode: String by lazy { generateDeviceCode() }

    fun connect() {
        executor.execute {
            try {
                val clientId = "$TOPIC_PREFIX-${deviceCode}-${System.currentTimeMillis()}"
                mqttClient = MqttClient(BROKER, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxInflight = 10
                }

                mqttClient?.connect(options)
                connected = true
                reconnectAttempts = 0

                val configTopic = "$TOPIC_PREFIX/$deviceCode/config"
                mqttClient?.subscribe(configTopic) { _, message ->
                    handleConfigMessage(message)
                }

                val getPlaylistsTopic = "$TOPIC_PREFIX/$deviceCode/getplaylists"
                mqttClient?.subscribe(getPlaylistsTopic) { _, _ ->
                    publishPlaylists()
                }

                val deleteTopic = "$TOPIC_PREFIX/$deviceCode/deleteplaylist"
                mqttClient?.subscribe(deleteTopic) { _, message ->
                    handleDeletePlaylist(message)
                }

                mainHandler.post {
                    onStatusChanged("Connesso! Codice: $deviceCode")
                }

                // Publish online status
                val statusTopic = "$TOPIC_PREFIX/$deviceCode/status"
                mqttClient?.publish(statusTopic, MqttMessage("online".toByteArray()))

                Log.i(TAG, "MQTT connesso, device: $deviceCode")
            } catch (e: Exception) {
                Log.e(TAG, "Errore connessione MQTT", e)
                connected = false
                mainHandler.post {
                    onStatusChanged("Offline - riconnessione...")
                }
                scheduleReconnect()
            }
        }
    }

    private fun handleConfigMessage(message: MqttMessage) {
        try {
            val json = String(message.payload)
            Log.i(TAG, "Config ricevuta: $json")
            val config = gson.fromJson(json, Config::class.java)

            if (config.m3uUrl.isNotBlank()) {
                val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("m3u_url", config.m3uUrl)
                    .putString("user_agent", config.userAgent)
                    .putString("referer", config.referer)
                    .putString("origin", config.origin)
                    .putString("proxy_host", config.proxyHost)
                    .putInt("proxy_port", config.proxyPort)
                    .putString("proxy_type", config.proxyType)
                    .putString("proxy_user", config.proxyUser)
                    .putString("proxy_pass", config.proxyPass)
                    .putString("dns_server", config.dnsServer)
                    .apply()

                mainHandler.post { onConfigReceived(config) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing config", e)
        }
    }

    private fun handleDeletePlaylist(message: MqttMessage) {
        try {
            val payload = String(message.payload).trim()
            if (payload.isNotBlank()) {
                if (payload == "ALL") {
                    val all = PlaylistManager.getAll(context)
                    for (pl in all) {
                        PlaylistManager.delete(context, pl.id)
                    }
                } else {
                    PlaylistManager.delete(context, payload)
                }
                publishPlaylists()
                val active = PlaylistManager.getActive(context)
                if (active != null) {
                    val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("m3u_url", active.url).apply()
                    mainHandler.post {
                        onConfigReceived(Config(m3uUrl = active.url, userAgent = active.userAgent, referer = active.referer))
                    }
                } else {
                    mainHandler.post { onStatusChanged("Playlist eliminate") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore delete playlist", e)
        }
    }

    private fun publishPlaylists() {
        try {
            val list = PlaylistManager.getAll(context)
            val topic = "$TOPIC_PREFIX/$deviceCode/playlists"
            val json = gson.toJson(mapOf("playlists" to list))
            mqttClient?.publish(topic, MqttMessage(json.toByteArray()))
        } catch (e: Exception) {
            Log.e(TAG, "Errore publish playlists", e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 10) return
        reconnectAttempts++
        val delay = (reconnectAttempts * 5_000L).coerceAtMost(60_000L)
        executor.execute {
            Thread.sleep(delay)
            connect()
        }
    }

    private fun generateDeviceCode(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val hash = MessageDigest.getInstance("MD5")
            .digest(androidId.toByteArray())
        return hash.take(4)
            .joinToString("") { "%02x".format(it) }
            .uppercase()
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {}
        connected = false
    }

    fun isConnected(): Boolean = connected
}
