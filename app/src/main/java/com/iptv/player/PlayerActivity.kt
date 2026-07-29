package com.iptv.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.iptv.player.model.Channel
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.*
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var epgOverlay: TextView
    private lateinit var epgDesc: TextView

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val EXTRA_CHANNEL_URL = "channel_url"
        private const val EXTRA_EPG_NOW = "epg_now"
        private const val EXTRA_EPG_NEXT = "epg_next"
        private const val EXTRA_EPG_DESC = "epg_desc"

        @Volatile private var lastWarpState = false

        fun start(context: Context, channel: Channel, epgNow: String? = null, epgNext: String? = null, epgDesc: String? = null) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_NAME, channel.name)
                putExtra(EXTRA_CHANNEL_URL, channel.url)
                putExtra(EXTRA_EPG_NOW, epgNow)
                putExtra(EXTRA_EPG_NEXT, epgNext)
                putExtra(EXTRA_EPG_DESC, epgDesc)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        @Synchronized
        fun getOkHttpClient(prefs: android.content.SharedPreferences, forceWarp: Boolean, ctx: Context?): OkHttpClient {
            val dnsServer = prefs.getString("dns_server", "")?.takeIf { it.isNotBlank() }
            val builder = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true)

            if (forceWarp && ctx != null && AutoProxy.bestProxy != null) {
                builder.proxy(AutoProxy.buildOkHttpProxy(AutoProxy.bestProxy!!))
            }

            if (dnsServer != null) {
                try {
                    val doh = okhttp3.dnsoverhttps.DnsOverHttps.Builder()
                        .client(OkHttpClient.Builder().build())
                        .url(okhttp3.HttpUrl.Builder().scheme("https")
                            .host(if (dnsServer == "8.8.8.8") "dns.google" else "cloudflare-dns.com")
                            .addPathSegment("dns-query").build()).build()
                    builder.dns(doh)
                } catch (e: Exception) { /* senza DoH, DNS sistema */ }
            }
            return builder.build()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        playerView = findViewById(R.id.playerView)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)
        epgOverlay = findViewById(R.id.epgOverlay)

        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Canale"
        val url = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
        val epgNow = intent.getStringExtra(EXTRA_EPG_NOW)
        val epgNext = intent.getStringExtra(EXTRA_EPG_NEXT)

        if (url.isBlank()) { showError("URL non valido"); return }
        title = name

        // Mostra EPG overlay
        if (!epgNow.isNullOrBlank()) {
            val text = if (!epgNext.isNullOrBlank()) "● ${epgNow}  ▶ ${epgNext}" else "● ${epgNow}"
            epgOverlay.text = text
            epgOverlay.visibility = View.VISIBLE
            handler.postDelayed({ epgOverlay.visibility = View.GONE }, 8000)
        }

        resolveAndPlay(name, url)
    }

    private fun resolveAndPlay(name: String, url: String) {
        if (!url.contains("create_link")) { initializePlayer(name, url); return }
        Thread {
            try {
                val resolved = StalkerApi.resolveStreamUrl(url)
                handler.post {
                    if (resolved.contains("create_link") || resolved == url) {
                        showError("Risoluzione stream fallita - server stream non disponibile")
                    } else {
                        initializePlayer(name, resolved)
                    }
                }
            } catch (e: Exception) {
                handler.post { showError("Errore risoluzione stream: ${e.message}") }
            }
        }.start()
    }

    private fun initializePlayer(name: String, url: String) {
        val prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val ua = prefs.getString("user_agent", "VLC/3.0.20 LibVLC/3.0.20")?.takeIf { it.isNotBlank() } ?: "VLC/3.0.20 LibVLC/3.0.20"
        val ref = prefs.getString("referer", "")?.takeIf { it.isNotBlank() } ?: extractBaseUrl(url)
        val org = prefs.getString("origin", "")?.takeIf { it.isNotBlank() } ?: extractBaseUrl(url)
        val ck = prefs.getString("cookies", "")?.takeIf { it.isNotBlank() }
        val xff = prefs.getString("x_forwarded_for", "")?.takeIf { it.isNotBlank() }

        val forceWarp = prefs.getBoolean("force_warp", false)
        val okHttp = getOkHttpClient(prefs, forceWarp, this)
        val factory = OkHttpDataSource.Factory(okHttp).setUserAgent(ua).setDefaultRequestProperties(buildMap {
            put("User-Agent", ua); put("Accept", "*/*"); put("Accept-Language", "en-US,en;q=0.9")
            ref?.let { put("Referer", it) }; org?.let { put("Origin", it) }
            ck?.let { put("Cookie", it) }; xff?.let { put("X-Forwarded-For", it) }
        })

        val mediaFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(factory)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaFactory)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2500, 500, 500).build())
            .build().also { exoPlayer ->
                playerView.player = exoPlayer
                playerView.useController = true
                playerView.controllerShowTimeoutMs = 3000
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                exoPlayer.playWhenReady = true
                exoPlayer.prepare()
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> progressBar.visibility = View.VISIBLE
                            Player.STATE_READY -> { progressBar.visibility = View.GONE; errorText.visibility = View.GONE }
                            Player.STATE_ENDED -> finish()
                            Player.STATE_IDLE -> {}
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        progressBar.visibility = View.GONE
                        showError("Errore: ${error.localizedMessage ?: error.errorCodeName}")
                    }
                })
            }
    }

    private fun showError(msg: String) { errorText.text = msg; errorText.visibility = View.VISIBLE; progressBar.visibility = View.GONE }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                // Mostra di nuovo EPG al click
                if (epgOverlay.text.isNotBlank()) {
                    epgOverlay.visibility = View.VISIBLE
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ epgOverlay.visibility = View.GONE }, 8000)
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() { super.onStop(); if (Util.SDK_INT > 23) releasePlayer() }
    override fun onPause() { super.onPause(); if (Util.SDK_INT <= 23) releasePlayer() }
    override fun onDestroy() { super.onDestroy(); releasePlayer() }
    private fun releasePlayer() { player?.release(); player = null }

    private fun extractBaseUrl(url: String): String? {
        return try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}${if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""}/"
        } catch (e: Exception) { null }
    }
}
