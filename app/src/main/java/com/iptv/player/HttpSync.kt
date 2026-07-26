package com.iptv.player

import android.content.Context
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object HttpSync {

    private val gson = Gson()

    fun fetchConfig(blobId: String, context: Context): MqttSync.Config? {
        return try {
            val url = URL("https://jsonblob.com/api/jsonBlob/$blobId")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val json = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            gson.fromJson(json, MqttSync.Config::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
