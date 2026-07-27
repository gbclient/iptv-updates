package com.iptv.player

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.model.Channel
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
import java.text.SimpleDateFormat
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object { const val VERSION = 19 }

    enum class ContentType { LIVE, VOD, SERIES }

    private lateinit var sidebarList: RecyclerView
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var codeText: TextView
    private lateinit var emptyText: TextView
    private lateinit var filterBar: LinearLayout
    private lateinit var filterLive: TextView
    private lateinit var filterVod: TextView
    private lateinit var filterSeries: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var playlistBtn: TextView
    private lateinit var addBtn: TextView
    private lateinit var splashOverlay: LinearLayout
    private lateinit var themeBtn: TextView
    private lateinit var searchBtn: TextView
    private lateinit var donateBtn: android.widget.Button
    private lateinit var rootLayout: androidx.constraintlayout.widget.ConstraintLayout

    private var mqttSync: MqttSync? = null
    private var webServer: WebServer? = null
    private var channelAdapter: ChannelAdapter? = null
    private var categoryAdapter: CategoryAdapter? = null
    private var playlistAdapter: PlaylistSelectAdapter? = null
    private val allChannels = mutableListOf<Channel>()
    private val categories = mutableListOf<Category>()
    private var currentCategory: Category? = null
    private var currentContentFilter: ContentType? = null
    private var showingPlaylists = false
    private var epgNow = emptyMap<String, EpgParser.Programme?>()
    private var epgNext = emptyMap<String, EpgParser.Programme?>()
    private var epgAllProgrammes = emptyList<EpgParser.Programme>()
    private val executor = Executors.newSingleThreadExecutor()
    private val pollExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backPressedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sidebarList = findViewById(R.id.sidebarList)
        recyclerView = findViewById(R.id.channelList)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        codeText = findViewById(R.id.ipText)
        emptyText = findViewById(R.id.emptyText)
        filterBar = findViewById(R.id.filterBar)
        filterLive = findViewById(R.id.filterLive)
        filterVod = findViewById(R.id.filterVod)
        filterSeries = findViewById(R.id.filterSeries)
        settingsBtn = findViewById(R.id.settingsBtn)
        playlistBtn = findViewById(R.id.playlistBtn)
        addBtn = findViewById(R.id.addBtn)
        splashOverlay = findViewById(R.id.splashOverlay)
        themeBtn = findViewById(R.id.themeBtn)
        searchBtn = findViewById(R.id.searchBtn)
        donateBtn = findViewById(R.id.donateBtn)
        rootLayout = findViewById(R.id.rootLayout)

        applyTheme()

        searchBtn.setOnClickListener { showSearchDialog() }
        themeBtn.setOnClickListener { showThemeDialog() }

        // Splash screen: mostra e nascondi dopo 1.5s
        splashOverlay.visibility = View.VISIBLE
        mainHandler.postDelayed({
            splashOverlay.animate().alpha(0f).setDuration(400).withEndAction {
                splashOverlay.visibility = View.GONE
            }.start()
        }, 1500)
        checkForUpdate()

        settingsBtn.setOnClickListener { showSettingsDialog() }
        playlistBtn.setOnClickListener { showPlaylists(PlaylistManager.getAll(this)) }
        addBtn.setOnClickListener { showAddPlaylistDialog() }

        filterLive.setOnClickListener { setContentFilter(ContentType.LIVE) }
        filterVod.setOnClickListener { setContentFilter(ContentType.VOD) }
        filterSeries.setOnClickListener { setContentFilter(ContentType.SERIES) }
        filterLive.setOnLongClickListener { setContentFilter(null); true }
        filterVod.setOnLongClickListener { setContentFilter(null); true }
        filterSeries.setOnLongClickListener { setContentFilter(null); true }

        codeText.setOnClickListener { switchPlaylist(1) }
        codeText.setOnLongClickListener {
            val all = PlaylistManager.getAll(this)
            if (all.isEmpty()) {
                showAddPlaylistDialog()
            } else if (showingPlaylists) {
                showAddPlaylistDialog()
            } else {
                showPlaylists(all)
            }
            true
        }

        startMqttSync()
        startWebServer()
        checkBroadcast()
        checkDonationRequest()
        showPlaylistsOrCategories()
    }

    private fun checkDonationRequest() {
        val dev = mqttSync?.deviceCode ?: return
        pollExecutor.execute {
            try {
                val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/messages/donation.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                conn.disconnect()
                if (body.contains("\"active\"") && body.contains("true")) {
                    val data = com.google.gson.Gson().fromJson(body, Map::class.java)
                    val btc = data["btc"] as? String ?: return@execute
                    // Controlla scadenza
                    val expires = (data["expires"] as? Double)?.toLong() ?: 0L
                    if (expires > 0L && System.currentTimeMillis() > expires) return@execute
                    // Controlla se ha già donato
                    val donUrl = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/donated/$dev.json")
                    val donConn = donUrl.openConnection() as HttpURLConnection
                    donConn.connectTimeout = 5000; donConn.readTimeout = 5000
                    val donBody = if (donConn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(donConn.inputStream)).readText() else ""
                    donConn.disconnect()
                    if (donBody.contains("\"donated\":true") || donBody.trim() == "true") {
                        getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putBoolean("donated", true).apply()
                        return@execute
                    }
                    val giorni = if (expires > 0L) " (${(expires - System.currentTimeMillis()) / 86400000}gg rimasti)" else ""
                    mainHandler.post {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Sostieni lo sviluppo$giorni")
                            .setMessage("Se ti piace l'app, considera una donazione Bitcoin:\n\n$btc\n\nClicca sotto per copiare l'indirizzo.")
                            .setPositiveButton("Copia e conferma") { _, _ ->
                                val clip = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("BTC", btc))
                                Toast.makeText(this@MainActivity, "Indirizzo copiato!", Toast.LENGTH_SHORT).show()
                                // Chiedi TXID
                                val txInput = android.widget.EditText(this@MainActivity).apply {
                                    hint = "Incolla il TXID della transazione"
                                    setTextColor(0xFFFFFFFF.toInt())
                                    setHintTextColor(0xFF667788.toInt())
                                }
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Conferma donazione")
                                    .setMessage("Incolla il TXID (codice transazione) per verificare:")
                                    .setView(txInput)
                                    .setPositiveButton("Conferma") { _, _ ->
                                        val txid = txInput.text.toString().trim()
                                        val data = com.google.gson.Gson().toJson(mapOf("txid" to txid, "pending" to true))
                                        pollExecutor.execute {
                                            try {
                                                val markUrl = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/donated/$dev.json")
                                                val markConn = markUrl.openConnection() as HttpURLConnection
                                                markConn.requestMethod = "PUT"; markConn.doOutput = true
                                                markConn.connectTimeout = 3000; markConn.readTimeout = 3000
                                                markConn.outputStream.write(data.toByteArray())
                                                markConn.responseCode; markConn.disconnect()
                                            } catch (_: Exception) {}
                                        }
                                        Toast.makeText(this@MainActivity, "TXID inviato! In attesa di verifica.", Toast.LENGTH_LONG).show()
                                    }
                                    .setNegativeButton("Salta", null)
                                    .show()
                            }
                            .setNegativeButton("Non ora", null)
                            .show()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkForUpdate() {
        pollExecutor.execute {
            try {
                val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/version.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                conn.disconnect()
                if (body.contains("\"version\"")) {
                    val data = com.google.gson.Gson().fromJson(body, Map::class.java)
                    val latestVer = (data["version"] as? Double)?.toInt() ?: VERSION
                    val apkUrl = data["url"] as? String ?: ""
                    if (latestVer > VERSION && apkUrl.isNotBlank()) {
                        mainHandler.post {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Aggiornamento disponibile!")
                                .setMessage("Nuova versione $latestVer disponibile. Scaricarla?")
                                .setPositiveButton("Scarica") { _, _ ->
                                    downloadAndInstallApk(apkUrl)
                                }
                                .setNegativeButton("Dopo", null)
                                .show()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        try {
            Toast.makeText(this, "Download...", Toast.LENGTH_LONG).show()
            val dm = getSystemService(DOWNLOAD_SERVICE) as? android.app.DownloadManager
            if (dm == null) { Toast.makeText(this, "DownloadManager non disponibile", Toast.LENGTH_LONG).show(); return }
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(apkUrl))
                .setTitle("IPTV Player v${VERSION}")
                .setDescription("Download in corso...")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, null, "IPTVPlayer-v${VERSION}.apk")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true).setAllowedOverRoaming(true)
            dm.enqueue(request)
            Toast.makeText(this, "Scaricamento iniziato. Clicca la notifica per installare.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkBroadcast() {
        val dev = mqttSync?.deviceCode ?: return
        pollExecutor.execute {
            try {
                val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/messages/broadcast.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                conn.disconnect()
                if (body.contains("\"text\"")) {
                    val data = com.google.gson.Gson().fromJson(body, Map::class.java)
                    val text = data["text"] as? String ?: return@execute
                    val shown = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).getString("last_msg", "")
                    if (text != shown) {
                        mainHandler.post {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Messaggio")
                                .setMessage(text)
                                .setPositiveButton("OK") { _, _ ->
                                    getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putString("last_msg", text).apply()
                                }
                                .show()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startMqttSync() {
        mqttSync = MqttSync(
            this,
            onConfigReceived = { config -> applyConfig(config) },
            onStatusChanged = { updateCodeText(it) }
        )
        mqttSync?.connect()
        updateCodeText("Connessione in corso...")
    }

    private fun registerOnFirebase() {
        val dev = mqttSync?.deviceCode ?: return
        if (dev.length < 6) return
        pollExecutor.execute {
            try {
                val playlists = PlaylistManager.getAll(this)
                val json = com.google.gson.Gson().toJson(playlists)
                val data = """{"lastSeen":"${System.currentTimeMillis()}","playlists":$json}"""
                val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/clients/$dev.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"; conn.doOutput = true
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.outputStream.write(data.toByteArray())
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun startWebServer() {
        try {
//            loadFirebaseOnce()
            registerOnFirebase()
            webServer = WebServer(this, 8080) { m3uUrl ->
                mainHandler.post { loadChannels(m3uUrl) }
            }
            webServer?.start()
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "WebServer: ${e.message}")
        }
    }

    private fun loadFirebaseOnce() {
        val dev = mqttSync?.deviceCode ?: return
        if (dev.length < 6) return
        val url = "https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/configs/$dev.json"
        mainHandler.postDelayed({
            pollExecutor.execute {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                    conn.disconnect()
                    if (body.isNotEmpty() && body != "null" && body.contains("m3uUrl")) {
                        val c = com.google.gson.Gson().fromJson(body, MqttSync.Config::class.java)
                        if (c.m3uUrl.isNotBlank()) {
                            mainHandler.post {
                                val deleteFirebase = {
                                    pollExecutor.execute {
                                        try {
                                            val delConn = URL(url).openConnection() as HttpURLConnection
                                            delConn.requestMethod = "DELETE"
                                            delConn.connectTimeout = 5000; delConn.readTimeout = 5000
                                            delConn.responseCode; delConn.disconnect()
                                        } catch (_: Exception) {}
                                    }
                                }
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Playlist da Cloud")
                                    .setMessage("Nuova playlist trovata: ${c.playlistName}. Caricarla?")
                                    .setPositiveButton("Si") { _, _ ->
                                        try {
                                            applyConfig(c)
                                        } catch (e: Exception) {
                                            android.util.Log.e("FB", "applyConfig error", e)
                                        }
                                        deleteFirebase()
                                    }
                                    .setNegativeButton("No") { _, _ -> deleteFirebase() }
                                    .show()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }, 2000)
    }

    private fun loadFirebaseOnceOnceOnly() {
        val dev = mqttSync?.deviceCode ?: return
        if (dev.length < 6) return
        val url = "https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/configs/$dev.json"
        pollExecutor.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                conn.disconnect()
                if (body.isNotEmpty() && body != "null" && body.contains("m3uUrl")) {
                    val c = com.google.gson.Gson().fromJson(body, MqttSync.Config::class.java)
                    if (c.m3uUrl.isNotBlank()) {
                        // Delete from cloud after reading
                        try {
                            val delConn = URL(url).openConnection() as HttpURLConnection
                            delConn.requestMethod = "DELETE"; delConn.connectTimeout = 3000
                            delConn.readTimeout = 3000; delConn.responseCode; delConn.disconnect()
                        } catch (_: Exception) {}
                        mainHandler.post {
                            try { applyConfig(c) } catch (e: Exception) {}
                            Toast.makeText(this@MainActivity, "Playlist caricata da cloud!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "Nessuna playlist nel cloud", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "Cloud non raggiungibile", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPlaylistsOrCategories() {
        val all = PlaylistManager.getAll(this)
        val active = PlaylistManager.getActive(this)
        checkActivation {
            if (all.size > 1) {
                showPlaylists(all)
                if (active != null && allChannels.isEmpty()) {
                    loadChannels(active.url)
                }
            } else {
                if (loadCachedChannels()) {
                    if (active != null) {
                        executor.execute {
                            M3UParser.parseFromUrl(active.url, this@MainActivity)
                        }
                    }
                } else {
                    loadActivePlaylist()
                }
            }
        }
    }

    private fun checkActivation(onActivated: () -> Unit) {
        val dev = mqttSync?.deviceCode ?: return
        if (dev.length < 6) return
        val prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val url = "https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/activated/$dev.json"
        pollExecutor.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                conn.disconnect()
                if (body.contains("expires") || body.trim() == "true") {
                    val activated = try {
                        val data = com.google.gson.Gson().fromJson(body, Map::class.java)
                        val expires = (data["expires"] as? Double)?.toLong() ?: 0L
                        expires == 0L || System.currentTimeMillis() < expires
                    } catch (e: Exception) { body.trim() == "true" }
                    prefs.edit().putBoolean("activated", activated).apply()
                    if (activated) { mainHandler.post { onActivated() } }
                    else { mainHandler.post { showBlocked(dev) } }
                } else {
                    prefs.edit().putBoolean("activated", false).apply()
                    mainHandler.post { showBlocked(dev) }
                }
            } catch (_: Exception) {
                // Solo se rete assente: permette l'uso
                mainHandler.post { onActivated() }
            }
        }
    }

    private fun loadCachedChannels(): Boolean {
        val prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("cached_channels", null) ?: return false
        val cachedUrl = prefs.getString("cached_url", null) ?: return false
        val active = PlaylistManager.getActive(this)
        if (active == null || active.url != cachedUrl) return false
        
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<Channel>>() {}.type
            val channels: List<Channel> = com.google.gson.Gson().fromJson(json, type) ?: return false
            if (channels.isEmpty()) return false
            
            allChannels.clear()
            allChannels.addAll(channels)
            showingPlaylists = false
            progressBar.visibility = View.GONE
            buildCategories()
            showCategories()
            updateCodeText("Playlist: ${active.name} (cache)")
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun saveChannelCache() {
        val active = PlaylistManager.getActive(this) ?: return
        // Limita a 3000 canali per evitare crash
        val toCache = if (allChannels.size > 3000) allChannels.take(3000) else allChannels
        try {
            val json = com.google.gson.Gson().toJson(toCache)
            getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit()
                .putString("cached_channels", json)
                .putString("cached_url", active.url)
                .apply()
        } catch (e: Exception) {
            // Troppo grande, salta cache
            android.util.Log.w("Cache", "Too large, skip", e)
        }
    }

    private fun showPlaylists(playlists: List<Playlist>) {
        showingPlaylists = true
        currentCategory = null
        currentContentFilter = null
        val active = PlaylistManager.getActive(this)
        statusText.text = "Playlist (${playlists.size}) - attiva: ${active?.name ?: "nessuna"}"
        statusText.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        updateFilterBar()

        statusText.setOnClickListener { showAddPlaylistDialog() }
        statusText.isClickable = true
        statusText.isFocusable = true
        statusText.isFocusableInTouchMode = true

        playlistAdapter = PlaylistSelectAdapter(playlists, active,
            onClick = { pl ->
                if (pl.id == active?.id) {
                    // Gia' attiva: non ricaricare se canali presenti
                    if (allChannels.isNotEmpty()) {
                        showingPlaylists = false
                        showCategories()
                        return@PlaylistSelectAdapter
                    }
                }
                PlaylistManager.setActive(this, pl.id)
                updateCodeText("Playlist: ${pl.name}")
                loadChannels(pl.url)
            },
            onDelete = { pl ->
                AlertDialog.Builder(this)
                    .setTitle("Elimina")
                    .setMessage("Eliminare \"${pl.name}\"?")
                    .setPositiveButton("Elimina") { _, _ ->
                        PlaylistManager.delete(this, pl.id)
                        registerOnFirebase()
                        val updated = PlaylistManager.getAll(this)
                        if (updated.isEmpty()) {
                            showEmpty("Nessuna playlist")
                        } else {
                            showPlaylists(updated)
                        }
                        Toast.makeText(this, "Eliminata", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        )
        recyclerView.adapter = playlistAdapter
    }

    private fun updateCodeText(status: String = "") {
        val code = mqttSync?.deviceCode ?: "----"
        val count = PlaylistManager.getAll(this).size
        val extra = if (status.isNotBlank()) "  |  $status" else ""
        codeText.text = "ID: $code$extra  |  $count PL"
        codeText.visibility = View.VISIBLE
        settingsBtn.visibility = View.VISIBLE
        themeBtn.visibility = View.VISIBLE
        playlistBtn.visibility = View.VISIBLE
        searchBtn.visibility = View.VISIBLE
        addBtn.visibility = View.VISIBLE
    }

    private fun applyTheme() {
        val theme = ThemeHelper.get(this)
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            colors = intArrayOf(theme.background, 0xFF000005.toInt())
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
        }
        rootLayout.background = bgDrawable
        splashOverlay.setBackgroundColor(theme.background)
        codeText.setTextColor(theme.accent)
        statusText.setTextColor(theme.accent)
        emptyText.setTextColor(theme.accent and 0x00FFFFFF or 0xBB000000.toInt())
    }

    private fun applyConfig(config: MqttSync.Config) {
        val prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keysToRemove = prefs.all.keys.filter { it.startsWith("hdr_") }
        keysToRemove.forEach { editor.remove(it) }
        editor.putString("m3u_url", config.m3uUrl)
            .putString("user_agent", config.userAgent)
            .putString("referer", config.referer)
            .putString("origin", config.origin)
            .putString("proxy_host", config.proxyHost)
            .putInt("proxy_port", config.proxyPort)
            .putString("proxy_type", config.proxyType)
            .putString("proxy_user", config.proxyUser)
            .putString("proxy_pass", config.proxyPass)
            .putString("dns_server", config.dnsServer)
            .putString("epg_url", config.epgUrl)
            .putString("cookies", config.cookies)
            .putString("x_forwarded_for", config.xForwardedFor)
        config.customHeaders.forEach { (k, v) -> editor.putString("hdr_$k", v) }
        editor.apply()
        val pl = Playlist(id = "cloud_${config.playlistName}", name = config.playlistName, url = config.m3uUrl,
            userAgent = config.userAgent, referer = config.referer)
        PlaylistManager.addOrUpdate(this, pl)
        registerOnFirebase()
        updateCodeText("Playlist: ${config.playlistName}")
        Toast.makeText(this, "Playlist salvata! Cliccala da ☰ per caricare", Toast.LENGTH_LONG).show()
    }

    private fun loadActivePlaylist() {
        showingPlaylists = false
        val active = PlaylistManager.getActive(this)
        if (active != null) {
            updateCodeText("Playlist: ${active.name}")
            loadChannels(active.url)
        } else {
            showEmpty("Nessuna playlist configurata\n\nVai su gbclient.github.io/iptv-client\nInserisci il codice sopra")
            val donato = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).getBoolean("donated", false)
            if (donato) {
                donateBtn.text = "❤️ Grazie per il supporto!"
                donateBtn.visibility = View.VISIBLE
                donateBtn.setOnClickListener {
                    Toast.makeText(this, "Grazie per aver supportato lo sviluppo! ❤️", Toast.LENGTH_LONG).show()
                }
            } else {
                val btc = "BC1QWGLY87FWFWWXNTWYDJSRFMQM9R3CPUK5SX30PJ"
                donateBtn.text = "⚡ Dona Bitcoin"
                donateBtn.visibility = View.VISIBLE
                donateBtn.setOnClickListener {
                    val clip = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("BTC", btc))
                    Toast.makeText(this, "Indirizzo Bitcoin copiato!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var loadingRunnable: Runnable? = null

    private fun loadChannels(m3uUrl: String) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = "Caricamento canali..."
        emptyText.visibility = View.GONE
        recyclerView.visibility = View.GONE

        executor.execute {
            try {
            if (m3uUrl.startsWith("stalker://")) {
                val parts = m3uUrl.removePrefix("stalker://").split("/")
                if (parts.size >= 2) {
                    try {
                        val server = String(Base64.decode(parts[0], Base64.DEFAULT))
                        val mac = parts[1]
                        val config = StalkerApi.StalkerConfig(server, mac)
                        val result = StalkerApi.loadChannels(config, this@MainActivity)
                        mainHandler.post {
                            progressBar.visibility = View.GONE
                            allChannels.clear()
                            allChannels.addAll(result.channels)
                            categories.clear()
                            categories.addAll(result.categories)
                            if (result.error != null) {
                                showEmpty("Errore Stalker: ${result.error}")
                            } else if (allChannels.isEmpty()) {
                                showEmpty("Nessun canale")
                            } else {
                                showingPlaylists = false
                                showCategories()
                                saveChannelCache()
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            progressBar.visibility = View.GONE
                            showEmpty("Errore Stalker: ${e.message}")
                        }
                    }
                }
                return@execute
            }
            if (m3uUrl.startsWith("xc://")) {
                val parts = m3uUrl.removePrefix("xc://").split("/")
                if (parts.size >= 3) {
                    try {
                        val server = String(Base64.decode(parts[0], Base64.DEFAULT))
                        val user = String(Base64.decode(parts[1], Base64.DEFAULT))
                        val pass = String(Base64.decode(parts[2], Base64.DEFAULT))
                        val config = XtreamApi.XCConfig(server, user, pass)
                        val epgUrl = "$server/xmltv.php?username=$user&password=$pass"
                        getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putString("epg_url", epgUrl).apply()

                        val liveResult = XtreamApi.loadChannels(config, "live", this@MainActivity)
                        val vodResult = XtreamApi.loadChannels(config, "vod", this@MainActivity)
                        val seriesResult = XtreamApi.loadChannels(config, "series", this@MainActivity)

                        val allChans = liveResult.channels + vodResult.channels + seriesResult.channels
                        val allCats = liveResult.categories + vodResult.categories + seriesResult.categories
                        val errors = listOfNotNull(liveResult.error, vodResult.error, seriesResult.error)
                        mainHandler.post {
                            progressBar.visibility = View.GONE
                            allChannels.clear()
                            allChannels.addAll(allChans)
                            categories.clear()
                            categories.addAll(allCats)

                            if (errors.isNotEmpty()) {
                                showEmpty("Errore XC: ${errors.first()}")
                            } else if (allChannels.isEmpty()) {
                                showEmpty("Nessun canale trovato")
                            } else {
                                showingPlaylists = false
                                loadEpgIfNeeded(epgUrl)
                                showCategories()
                                saveChannelCache()
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            progressBar.visibility = View.GONE
                            showEmpty("Errore XC: ${e.message}")
                        }
                    }
                }
                return@execute
            }

            val result = M3UParser.parseFromUrl(m3uUrl, this@MainActivity)
            mainHandler.post {
                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE

                if (result.error != null) {
                    showEmpty("Errore: ${result.error}\n\n$m3uUrl")
                    Toast.makeText(this@MainActivity, "Errore: ${result.error}", Toast.LENGTH_LONG).show()
                    return@post
                }
                allChannels.clear()
                allChannels.addAll(result.channels)

                if (!result.epgUrl.isNullOrBlank()) {
                    getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putString("epg_url", result.epgUrl).apply()
                    Toast.makeText(this@MainActivity, "EPG trovato", Toast.LENGTH_SHORT).show()
                    loadEpgIfNeeded(result.epgUrl)
                } else {
                    Toast.makeText(this@MainActivity, "Nessun EPG nella playlist. Aggiungilo in config.html", Toast.LENGTH_LONG).show()
                }

                if (allChannels.isEmpty()) {
                    showEmpty("0 canali trovati\nVerifica URL")
                    Toast.makeText(this@MainActivity, "Nessun canale", Toast.LENGTH_LONG).show()
                } else {
                    showingPlaylists = false
                    buildCategories()
                    showCategories()
                    saveChannelCache()
                    loadEpgIfNeeded()
                }
            }
            } catch (e: Exception) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    statusText.text = "Errore: ${e.message?.take(50)}"
                    statusText.visibility = View.VISIBLE
                    android.util.Log.e("LoadChannels", "crash", e)
                }
            }
        }
    }

    private fun buildCategories() {
        categories.clear()
        val filtered = if (currentContentFilter != null) {
            allChannels.filter { classifyChannel(it) == currentContentFilter }
        } else {
            allChannels.toList()
        }

        // Aggiungi Preferiti e Cronologia
        val favs = FavoritesManager.getFavorites(this)
        val hist = FavoritesManager.getHistory(this)
        if (favs.isNotEmpty()) categories.add(Category("⭐ Preferiti", favs.size))
        if (hist.isNotEmpty()) categories.add(Category("🕐 Cronologia", hist.size))

        val groups = filtered.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Altro" }
        val sorted = groups.entries.sortedByDescending { it.value.size }
        categories.add(Category("Tutti", filtered.size))
        for ((group, chans) in sorted) {
            categories.add(Category(group, chans.size))
        }

        val liveCount = allChannels.count { classifyChannel(it) == ContentType.LIVE }
        val vodCount = allChannels.count { classifyChannel(it) == ContentType.VOD }
        val seriesCount = allChannels.count { classifyChannel(it) == ContentType.SERIES }
        filterLive.text = "LIVE TV ($liveCount)"
        filterVod.text = "FILM ($vodCount)"
        filterSeries.text = "SERIE ($seriesCount)"
    }

    private fun classifyChannel(ch: com.iptv.player.model.Channel): ContentType {
        val url = ch.url.lowercase()
        val g = (ch.group ?: "").lowercase().trim()
        val name = ch.name.lowercase()

        if (g.startsWith("film:")) return ContentType.VOD
        if (g.startsWith("serie:")) return ContentType.SERIES
        if (url.contains("/movie/")) return ContentType.VOD
        if (url.contains("/series/")) return ContentType.SERIES

        if (g.contains("serie a") || g.contains("serie b") || g.contains("serie c") || g.contains("serie d") ||
            name.contains("serie a") || name.contains("serie b") ||
            g.contains("sport") || g.contains("calcio") || g.contains("bundesliga") ||
            g.contains("premier") || g.contains("liga") || g.contains("bambin") ||
            g.contains("kids") || g.contains("ragazzi") || g.contains("cartoon") ||
            g.contains("cartoni") || g.contains("sky"))
            return ContentType.LIVE

        if (Regex("s\\d{1,2}[e_]?\\d{1,2}", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
            Regex("s\\d{1,2}[e_]?\\d{1,2}", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            g.contains("series") || g.contains("serial") ||
            g.contains("season") || g.contains("episod") ||
            g.contains("fiction") || g.contains("telenovela") ||
            g.contains("soap") || g.contains("drama") || g.contains("sitcom") ||
            g.contains("anime"))
            return ContentType.SERIES

        if (url.endsWith(".ts") || url.contains("/live/") ||
            (url.contains(".m3u8") && (url.contains("live") || url.contains("token") || url.contains("playlist") || url.contains("username") || url.contains("password"))))
            return ContentType.LIVE

        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.endsWith(".avi") ||
            url.endsWith(".mov") || url.contains("/movie/") || url.contains("/vod/") ||
            url.contains("/films/") || url.contains("/film/"))
            return ContentType.VOD

        if (g.contains("vod") || g.contains("film") || g.contains("movie") ||
            g.contains("cinema") || g.contains("netflix") || g.contains("prime") ||
            g.contains("disney") || g.contains("hbo") || g.contains("hulu") ||
            g.contains("apple tv") || g.contains("plex") || g.contains("pelicula") ||
            g.contains("kino") || g.contains("成人") || g.contains("xxx") || g.contains("adult"))
            return ContentType.VOD

        if (g.contains("live") || g.contains("sport") || g.contains("calcio") ||
            g.contains("news") || g.contains("notizie") || g.contains("tv") ||
            g.contains("music") || g.contains("document") || g.contains("entertainment") ||
            g.contains("intrattenimento") || g.contains("italia") || g.contains("italy") ||
            g.contains("uk") || g.contains("de") || g.contains("fr") || g.contains("es") ||
            g.contains("usa") || g.contains("premium") || g.contains("sky") ||
            g.contains("dazn") || g.contains("rai") || g.contains("mediaset") ||
            g.contains("general") || g.contains("relax") || g.contains("natura") ||
            g.contains("cultura") || g.contains("region") || g.contains("local"))
            return ContentType.LIVE

        if (url.contains(".m3u8") || url.endsWith(".ts"))
            return ContentType.LIVE

        if (name.contains("hd") || name.contains("fhd") || name.contains("4k") || name.contains("hevc"))
            return ContentType.LIVE

        return ContentType.LIVE
    }

    private fun setContentFilter(type: ContentType?) {
        currentContentFilter = type
        filterLive.isSelected = type == ContentType.LIVE
        filterVod.isSelected = type == ContentType.VOD
        filterSeries.isSelected = type == ContentType.SERIES
        updateFilterBar()
        buildCategories()
        showCategories()
    }

    private fun updateFilterBar() {
        filterBar.visibility = if (showingPlaylists || currentCategory != null) View.GONE else View.VISIBLE
    }

    private fun showCategories() {
        currentCategory = null
        if (!statusText.text.toString().startsWith("EPG:")) {
            statusText.visibility = View.GONE
        }
        emptyText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        updateFilterBar()

        categoryAdapter = CategoryAdapter(categories) { category ->
            showChannelsFor(category)
        }
        recyclerView.adapter = categoryAdapter

        sidebarList.layoutManager = LinearLayoutManager(this)
        sidebarList.adapter = SidebarAdapter(categories) { cat -> showChannelsFor(cat) }
    }

    private fun showChannelsFor(category: Category) {
        currentCategory = category
        val filtered = when (category.name) {
            "⭐ Preferiti" -> {
                val favs = FavoritesManager.getFavorites(this)
                allChannels.filter { favs.contains(it.url) }
            }
            "🕐 Cronologia" -> {
                val hist = FavoritesManager.getHistory(this)
                hist.mapNotNull { url -> allChannels.find { it.url == url } }
            }
            "Tutti" -> {
                if (currentContentFilter != null) allChannels.filter { classifyChannel(it) == currentContentFilter }
                else allChannels.toList()
            }
            else -> {
                val allF = if (currentContentFilter != null) allChannels.filter { classifyChannel(it) == currentContentFilter } else allChannels
                allF.filter { (it.group?.takeIf { g -> g.isNotBlank() } ?: "Altro") == category.name }
            }
        }

        statusText.text = "${category.name} - ${filtered.size} canali"
        statusText.visibility = View.VISIBLE
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        updateFilterBar()

        channelAdapter = ChannelAdapter(filtered, { channel, now, next ->
            FavoritesManager.addToHistory(this, channel.url)
            PlayerActivity.start(this, channel, now, next)
        }, epgNow, epgNext,
            onLongClick = { ch ->
                val isFav = FavoritesManager.toggleFavorite(this, ch.url)
                Toast.makeText(this, if (isFav) "⭐ Aggiunto ai preferiti" else "Rimosso dai preferiti", Toast.LENGTH_SHORT).show()
            })
        recyclerView.adapter = channelAdapter
    }

    private fun loadEpgIfNeeded(epgUrlParam: String? = null) {
        val prefs = getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val epgUrl = epgUrlParam ?: prefs.getString("epg_url", "")?.takeIf { it.isNotBlank() } ?: return
        val ctx = this
        statusText.text = "EPG: scaricamento..."
        statusText.visibility = View.VISIBLE
        executor.execute {
            val xml = EpgParser.download(ctx, epgUrl)
            if (xml == null) {
                mainHandler.post { statusText.text = "EPG: download fallito" }
                return@execute
            }
            mainHandler.post { statusText.text = "EPG: parsing..." }
            val (programmes, channelNames) = EpgParser.parse(xml)
            epgAllProgrammes = programmes
            val mapped = programmes.map { p ->
                val displayName = channelNames[p.channel] ?: p.channel
                p.copy(channel = displayName)
            }
            val byChannel = mapped.groupBy { normalizeName(it.channel) }
            mainHandler.post { statusText.text = "EPG: associando..." }
            val now = mutableMapOf<String, EpgParser.Programme?>()
            val next = mutableMapOf<String, EpgParser.Programme?>()
            var matched = 0
            for (ch in allChannels) {
                val key = normalizeName(ch.name)
                val chProgs = (byChannel[key] ?: emptyList()).sortedBy { it.start }
                val nowT = System.currentTimeMillis()
                var n: EpgParser.Programme? = null
                var nx: EpgParser.Programme? = null
                for (p in chProgs) {
                    if (p.start <= nowT && p.stop > nowT) {
                        n = p
                        nx = chProgs.firstOrNull { it.start >= p.stop }
                        break
                    }
                }
                now[ch.name] = n
                next[ch.name] = nx
                if (n != null) matched++
            }
            mainHandler.post {
                epgNow = now
                epgNext = next
                channelAdapter?.notifyDataSetChanged()
                if (matched > 0) {
                    statusText.text = "EPG: $matched canali"
                } else {
                    statusText.text = "EPG: 0 match - nomi canali diversi?"
                }
                mainHandler.postDelayed({ statusText.visibility = View.GONE }, 5000)
            }
        }
    }

    private fun normalizeName(name: String): String {
        var n = name.lowercase()
        n = n.replace(Regex("\\b(hd|fhd|fullhd|4k|uhd|hevc|h\\.265|h\\.264)\\b"), "")
        n = n.replace(Regex("\\([fi]\\)"), "")
        n = n.replace(Regex("\\+[12]"), "")
        n = n.replace("\\s+".toRegex(), "")
        n = n.replace("\\[.*?\\]".toRegex(), "")
        return n.trim()
    }

    private fun showEmpty(msg: String) {
        emptyText.text = "$msg\n\nPer aggiungere playlist:\nvai su gbclient.github.io/iptv-client\nInserisci il codice sopra"
        emptyText.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        statusText.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun showBlocked(dev: String) {
        showEmpty("App non attivata o scaduta\n\nCodice: $dev\nContatta il tuo fornitore")
        progressBar.visibility = View.GONE
    }

    private fun switchPlaylist(direction: Int) {
        val pl = if (direction > 0) PlaylistManager.getNext(this) else PlaylistManager.getPrev(this)
        if (pl != null) {
            updateCodeText("Playlist: ${pl.name}")
            loadChannels(pl.url)
            Toast.makeText(this, "Playlist: ${pl.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processConfig(json: String) {
        try {
            val config = com.google.gson.Gson().fromJson(json, MqttSync.Config::class.java)
            if (config.m3uUrl.isNotBlank()) {
                Toast.makeText(this, "Firebase: carico ${config.m3uUrl.take(50)}...", Toast.LENGTH_LONG).show()
                applyConfig(config)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore JSON: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSearchDialog() {
        val savedCategory = currentCategory
        val wasShowingPlaylists = showingPlaylists

        fun restoreView() {
            when {
                savedCategory != null -> showChannelsFor(savedCategory)
                wasShowingPlaylists -> showPlaylists(PlaylistManager.getAll(this@MainActivity))
                else -> showCategories()
            }
        }

        val editText = EditText(this@MainActivity).apply {
            hint = "Cerca canale..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF667788.toInt())
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }

        val dialog = AlertDialog.Builder(this@MainActivity)
            .setTitle("Cerca")
            .setView(editText)
            .setNegativeButton("Chiudi") { _, _ -> restoreView() }
            .setOnCancelListener { restoreView() }
            .show()

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.isEmpty()) {
                    restoreView()
                } else {
                    val results = allChannels.filter { it.name.contains(query, ignoreCase = true) }
                    if (results.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyText.text = "Nessun canale trovato"
                        emptyText.visibility = View.VISIBLE
                        statusText.visibility = View.GONE
                        filterBar.visibility = View.GONE
                    } else {
                        emptyText.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.layoutManager = GridLayoutManager(this@MainActivity, 3)
                        statusText.text = "Ricerca: $query - ${results.size} risultati"
                        statusText.visibility = View.VISIBLE
                        filterBar.visibility = View.GONE
                        channelAdapter = ChannelAdapter(results,
                            { channel, now, next ->
                                FavoritesManager.addToHistory(this@MainActivity, channel.url)
                                PlayerActivity.start(this@MainActivity, channel, now, next)
                                dialog.dismiss()
                            },
                            epgNow, epgNext,
                            onLongClick = { ch ->
                                val isFav = FavoritesManager.toggleFavorite(this@MainActivity, ch.url)
                                Toast.makeText(this@MainActivity, if (isFav) "⭐ Aggiunto ai preferiti" else "Rimosso dai preferiti", Toast.LENGTH_SHORT).show()
                            })
                        recyclerView.adapter = channelAdapter
                    }
                }
            }
        })
    }

    private fun showThemeDialog() {
        val ctx = this@MainActivity
        val prefs = ctx.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val currentThemeIdx = prefs.getInt("theme", 0)
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        val colorNames = listOf("Rosso", "Blu", "Verde", "Viola", "Arancio")
        val colorVals = listOf(0xFFE94560.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFAB47BC.toInt(), 0xFFFF9800.toInt())
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<android.widget.Button>()
        colorNames.forEachIndexed { i, name ->
            val isSel = i == currentThemeIdx
            val btn = android.widget.Button(ctx).apply {
                text = name
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else 0xFFAABBCC.toInt())
                setBackgroundColor(if (isSel) colorVals[i] else 0xFF222244.toInt())
                textSize = 12f
                setPadding(8, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 4, 4, 4) }
                setOnClickListener {
                    ThemeHelper.set(ctx, i)
                    applyTheme()
                    buttons.forEachIndexed { j, b ->
                        b.setTextColor(if (j == i) 0xFFFFFFFF.toInt() else 0xFFAABBCC.toInt())
                        b.setBackgroundColor(if (j == i) colorVals[j] else 0xFF222244.toInt())
                    }
                }
            }
            buttons.add(btn)
            themeRow.addView(btn)
        }
        layout.addView(themeRow)
        AlertDialog.Builder(ctx)
            .setTitle("Seleziona Tema")
            .setView(layout)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAddPlaylistDialog() {
        val ctx = this@MainActivity
        val theme = ThemeHelper.get(ctx)
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }

        val webInfo = TextView(ctx).apply {
            text = "Invia playlist da smartphone/PC:\ngbclient.github.io/iptv-client\nCodice: ${mqttSync?.deviceCode ?: "----"}"
            setTextColor(theme.accent); textSize = 12f; gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 12)
            setOnClickListener {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://gbclient.github.io/iptv-client"))
                ctx.startActivity(intent)
            }
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        layout.addView(webInfo)

        var cloudDialog: AlertDialog? = null
        val cloudBtn = Button(ctx).apply {
            text = "Carica Playlist da Cloud"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            setBackgroundColor(theme.secondary)
            setOnClickListener {
                cloudDialog?.dismiss()
                Toast.makeText(ctx, "Verifica cloud in corso...", Toast.LENGTH_SHORT).show()
                loadFirebaseOnceOnceOnly()
            }
        }
        layout.addView(cloudBtn)

        val separator = TextView(ctx).apply {
            text = "── OPPURE manualmente ──"
            setTextColor(0xFF667788.toInt()); textSize = 11f; gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 12)
        }
        layout.addView(separator)
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }

        val btnM3u = TextView(ctx).apply {
            text = "M3U URL"; gravity = android.view.Gravity.CENTER; textSize = 14f
            setPadding(16, 12, 16, 12); setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(ctx, R.drawable.filter_btn_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isSelected = true
        }
        val btnXc = TextView(ctx).apply {
            text = "Xtream Codes"; gravity = android.view.Gravity.CENTER; textSize = 14f
            setPadding(16, 12, 16, 12); setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(ctx, R.drawable.filter_btn_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnStalker = TextView(ctx).apply {
            text = "Stalker MAC"; gravity = android.view.Gravity.CENTER; textSize = 14f
            setPadding(16, 12, 16, 12); setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(ctx, R.drawable.filter_btn_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tabRow.addView(btnM3u); tabRow.addView(btnXc); tabRow.addView(btnStalker)
        layout.addView(tabRow)

        val m3uInput = EditText(ctx).apply {
            hint = "http://server.com:8080/get.php?..."
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF667788.toInt()); textSize = 13f
            setSingleLine(false); minLines = 2
        }
        val xcLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val xcServer = EditText(ctx).apply { hint = "Server (es. http://server.com:8080)"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF667788.toInt()); textSize = 13f }
        val xcUser = EditText(ctx).apply { hint = "Username"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF667788.toInt()); textSize = 13f }
        val xcPass = EditText(ctx).apply { hint = "Password"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF667788.toInt()); textSize = 13f }
        xcLayout.addView(xcServer); xcLayout.addView(xcUser); xcLayout.addView(xcPass)

        layout.addView(m3uInput); layout.addView(xcLayout)

        val stalkerLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val stalkerServer = EditText(ctx).apply { hint = "http://server.com:8080/c/"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF667788.toInt()); textSize = 13f; isFocusable = true; isFocusableInTouchMode = true }
        val stalkerMac = EditText(ctx).apply { hint = "MAC (es. 00:1A:79:XX:XX:XX)"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF667788.toInt()); textSize = 13f; isFocusable = true; isFocusableInTouchMode = true }
        val stalkerInfo = TextView(ctx).apply {
            text = "Formati comuni: http://server.com:8080/c/  OPPURE  http://server.com:8080/stalker_portal/c/"
            setTextColor(0xFF667788.toInt()); textSize = 10f
        }
        stalkerLayout.addView(stalkerServer); stalkerLayout.addView(stalkerMac); stalkerLayout.addView(stalkerInfo)
        layout.addView(stalkerLayout)

        fun setTab(selected: TextView, vararg others: TextView) {
            selected.isSelected = true
            others.forEach { it.isSelected = false }
            m3uInput.visibility = if (selected == btnM3u) View.VISIBLE else View.GONE
            xcLayout.visibility = if (selected == btnXc) View.VISIBLE else View.GONE
            stalkerLayout.visibility = if (selected == btnStalker) View.VISIBLE else View.GONE
            when (selected) {
                btnM3u -> m3uInput.requestFocus()
                btnXc -> xcServer.requestFocus()
                btnStalker -> stalkerServer.requestFocus()
            }
        }
        btnM3u.setOnClickListener { setTab(btnM3u, btnXc, btnStalker) }
        btnXc.setOnClickListener { setTab(btnXc, btnM3u, btnStalker) }
        btnStalker.setOnClickListener { setTab(btnStalker, btnM3u, btnXc) }

        cloudDialog = AlertDialog.Builder(ctx)
            .setTitle("Aggiungi Playlist")
            .setView(layout)
            .setPositiveButton("Carica") { _, _ ->
                val url: String
                val name: String
                if (btnStalker.isSelected) {
                    val srv = stalkerServer.text.toString().trim()
                    val mac = stalkerMac.text.toString().trim()
                    if (srv.isBlank() || mac.isBlank()) return@setPositiveButton
                    url = "stalker://${android.util.Base64.encodeToString(srv.toByteArray(), android.util.Base64.NO_WRAP)}/${mac.replace(":", "").replace("-", "").uppercase()}"
                    name = "STB ${mac.takeLast(6)}"
                } else if (btnXc.isSelected) {
                    val s = xcServer.text.toString().trim()
                    val u = xcUser.text.toString().trim()
                    val p = xcPass.text.toString().trim()
                    if (s.isBlank() || u.isBlank() || p.isBlank()) return@setPositiveButton
                    url = "xc://${android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)}/${android.util.Base64.encodeToString(u.toByteArray(), android.util.Base64.NO_WRAP)}/${android.util.Base64.encodeToString(p.toByteArray(), android.util.Base64.NO_WRAP)}"
                    name = u
                } else {
                    url = m3uInput.text.toString().trim()
                    if (url.isBlank()) return@setPositiveButton
                    name = "Playlist ${PlaylistManager.getAll(ctx).size + 1}"
                }
                PlaylistManager.addOrUpdate(ctx, Playlist(name = name, url = url))
                registerOnFirebase()
                loadChannels(url)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showSettingsDialog() {
        val ctx = this@MainActivity
        val prefs = ctx.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val currentUA = prefs.getString("user_agent", "VLC/3.0.20 LibVLC/3.0.20") ?: "VLC/3.0.20 LibVLC/3.0.20"
        val currentDns = prefs.getString("dns_server", "") ?: ""
        val forceWarp = prefs.getBoolean("force_warp", false)
        val theme = ThemeHelper.get(ctx)

        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        val scroll = android.widget.ScrollView(ctx).apply { addView(layout) }

        val uaLabel = TextView(ctx).apply { text = "❯ User-Agent:"; setTextColor(theme.accent); textSize = 12f }
        layout.addView(uaLabel)
        val uaInput = EditText(ctx).apply { setText(currentUA); setTextColor(0xFFFFFFFF.toInt()); textSize = 13f }
        layout.addView(uaInput)

        val uaPresets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("VLC", "Chrome", "Android", "iPhone", "SmartTV", "OkHttp").forEach { label ->
            val btn = TextView(ctx).apply {
                text = label; setPadding(8, 6, 8, 6); setTextColor(0xFFAABBCC.toInt()); textSize = 10f
                background = ContextCompat.getDrawable(ctx, R.drawable.filter_btn_bg)
                setOnClickListener { uaInput.setText(when(label) {
                    "VLC" -> "VLC/3.0.20 LibVLC/3.0.20"
                    "Chrome" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    "Android" -> "Mozilla/5.0 (Linux; Android 14; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    "iPhone" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
                    "SmartTV" -> "SmartTV"
                    else -> "okhttp/4.12.0"
                }) }
            }
            uaPresets.addView(btn)
        }
        layout.addView(uaPresets)
        layout.addView(TextView(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        val dnsLabel = TextView(ctx).apply { text = "❯ DNS:"; setTextColor(theme.accent); textSize = 12f }
        layout.addView(dnsLabel)
        val dnsInput = EditText(ctx).apply { setText(currentDns); setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; hint = "1.1.1.1 (vuoto = default)" }
        layout.addView(dnsInput)

        val dnsPresets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("1.1.1.1", "8.8.8.8", "Auto").forEach { label ->
            val btn = TextView(ctx).apply {
                text = label; setPadding(8, 6, 8, 6); setTextColor(0xFFAABBCC.toInt()); textSize = 10f
                background = ContextCompat.getDrawable(ctx, R.drawable.filter_btn_bg)
                setOnClickListener { dnsInput.setText(if (label == "Auto") "" else label) }
            }
            dnsPresets.addView(btn)
        }
        layout.addView(dnsPresets)
        layout.addView(TextView(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        val warpRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val warpCheck = android.widget.CheckBox(ctx).apply { isChecked = forceWarp; setTextColor(0xFFAABBCC.toInt()) }
        val warpLabel = TextView(ctx).apply { text = "❯ Proxy anche su streaming (lento)"; setTextColor(theme.accent); textSize = 12f }
        warpRow.addView(warpCheck)
        warpRow.addView(warpLabel)
        layout.addView(warpRow)

        val autoProxyRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val autoProxyCheck = android.widget.CheckBox(ctx).apply { isChecked = prefs.getBoolean("auto_proxy", true); setTextColor(0xFFAABBCC.toInt()) }
        val autoProxyLabel = TextView(ctx).apply { text = "❯ Proxy automatico (gratis)"; setTextColor(theme.accent); textSize = 12f }
        autoProxyRow.addView(autoProxyCheck)
        autoProxyRow.addView(autoProxyLabel)
        layout.addView(autoProxyRow)

        val proxyStatus = TextView(ctx).apply {
            text = if (AutoProxy.bestProxy != null) "${AutoProxy.bestProxy!!.host}:${AutoProxy.bestProxy!!.port} (${AutoProxy.bestProxy!!.speed}ms)" else "Non testato"
            setTextColor(0xFF8899AA.toInt()); textSize = 10f; setPadding(40, 2, 0, 0)
        }
        layout.addView(proxyStatus)

        val scanBtn = android.widget.Button(ctx).apply {
            text = "Cerca Proxy Veloci"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 11f
            setBackgroundColor(theme.secondary)
            setOnClickListener {
                it.isEnabled = false
                (it as android.widget.Button).text = "Ricerca..."
                proxyStatus.text = "Ricerca in corso..."
                AutoProxy.startScan(ctx) { result ->
                    mainHandler.post {
                        it.isEnabled = true
                        it.text = "Cerca Proxy Veloci"
                        proxyStatus.text = if (result != null) "${result.host}:${result.port} (${result.speed}ms)" else "Nessuno trovato"
                    }
                }
            }
        }
        layout.addView(scanBtn)
        layout.addView(TextView(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })

        val macText = TextView(ctx).apply {
            text = "MAC: ${DeviceInfo.getMacAddress()}"
            setTextColor(0xFF667788.toInt()); textSize = 10f; gravity = android.view.Gravity.CENTER
        }
        layout.addView(macText)

        val clearCloudBtn = android.widget.Button(ctx).apply {
            text = "Cancella dati Cloud"
            setTextColor(0xFFEF5350.toInt()); textSize = 11f
            setBackgroundColor(0xFF1a0a0a.toInt())
            setOnClickListener {
                val dev = mqttSync?.deviceCode ?: return@setOnClickListener
                pollExecutor.execute {
                    try {
                        val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/configs/$dev.json")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "DELETE"
                        conn.connectTimeout = 3000; conn.readTimeout = 3000
                        conn.responseCode; conn.disconnect()
                    } catch (_: Exception) {}
                }
                Toast.makeText(ctx, "Cloud cancellato", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(clearCloudBtn)

        val backupRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        val backupBtn = android.widget.Button(ctx).apply {
            text = "Backup Playlist"; setTextColor(0xFFFFFFFF.toInt()); textSize = 10f
            setBackgroundColor(0xFF1a3a1a.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { backupPlaylists() }
        }
        val restoreBtn = android.widget.Button(ctx).apply {
            text = "Ripristina"; setTextColor(0xFFFFFFFF.toInt()); textSize = 10f
            setBackgroundColor(0xFF1a1a3a.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { restorePlaylists() }
        }
        backupRow.addView(backupBtn); backupRow.addView(restoreBtn)
        layout.addView(backupRow)

        val diagnosticaBtn = android.widget.Button(ctx).apply {
            text = "Diagnostica"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
            setBackgroundColor(0xFF2a2a4a.toInt())
            setOnClickListener {
                val info = StringBuilder()
                info.append("Versione App: ${VERSION}\n")
                info.append("Dispositivo: ${mqttSync?.deviceCode ?: "N/D"}\n")
                info.append("MAC: ${DeviceInfo.getMacAddress()}\n")
                info.append("Playlist: ${PlaylistManager.getAll(this@MainActivity).size}\n")
                info.append("Canali caricati: ${allChannels.size}\n")
                info.append("EPG: ${if (epgAllProgrammes.isNotEmpty()) epgAllProgrammes.size.toString() + " programmi" else "Non caricato"}\n")
                info.append("Proxy: ${AutoProxy.bestProxy?.let { "${it.host}:${it.port} (${it.speed}ms)" } ?: "Non testato"}\n")
                info.append("Attivato: ${if (prefs.getBoolean("activated", false)) "Si" else "Verifica in corso..."}\n")
                AlertDialog.Builder(ctx).setTitle("Diagnostica").setMessage(info.toString()).setPositiveButton("OK", null).show()
            }
        }
        layout.addView(diagnosticaBtn)

        val donateBtn = android.widget.Button(ctx).apply {
            text = "⚡ Fai una donazione"
            setTextColor(0xFFFFD700.toInt()); textSize = 12f
            setBackgroundColor(0xFF1a1a0a.toInt())
            setOnClickListener {
                val btc = "bitcoin:BC1QWGLY87FWFWWXNTWYDJSRFMQM9R3CPUK5SX30PJ"
                val displayAddr = btc.removePrefix("bitcoin:")
                AlertDialog.Builder(ctx)
                    .setTitle("Donazione Bitcoin")
                    .setMessage("Supporta lo sviluppo inviando BTC:\n\n$displayAddr\n\nScansiona o copia l'indirizzo.")
                    .setPositiveButton("Copia indirizzo") { _, _ ->
                        val clip = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("BTC", displayAddr))
                        Toast.makeText(ctx, "Indirizzo copiato!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
        }
        layout.addView(donateBtn)

        val themeLabel = TextView(ctx).apply { text = "❯ Tema:"; setTextColor(theme.accent); textSize = 12f }
        layout.addView(themeLabel)
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val currentThemeIdx = prefs.getInt("theme", 0)
        val colorNames = listOf("Rosso", "Blu", "Verde", "Viola", "Arancio")
        val colorVals = listOf(0xFFE94560.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFAB47BC.toInt(), 0xFFFF9800.toInt())
        colorNames.forEachIndexed { i, name ->
            val isSel = i == currentThemeIdx
            val btn = android.widget.Button(ctx).apply {
                text = name
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else 0xFFAABBCC.toInt())
                setBackgroundColor(if (isSel) colorVals[i] else 0xFF222244.toInt())
                textSize = 10f
                setPadding(8, 4, 8, 4)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    ThemeHelper.set(ctx, i)
                    applyTheme()
                    AlertDialog.Builder(ctx).setTitle("Tema cambiato").setMessage("Tema: $name").setPositiveButton("OK", null).show()
                }
            }
            themeRow.addView(btn)
        }
        layout.addView(themeRow)

        AlertDialog.Builder(ctx)
            .setTitle("Impostazioni")
            .setView(scroll)
            .setPositiveButton("Salva") { _, _ ->
                prefs.edit().putString("user_agent", uaInput.text.toString().trim())
                    .putString("dns_server", dnsInput.text.toString().trim())
                    .putBoolean("force_warp", warpCheck.isChecked)
                    .putBoolean("auto_proxy", autoProxyCheck.isChecked)
                    .apply()
                Toast.makeText(ctx, "Salvato", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun backupPlaylists() {
        val dev = mqttSync?.deviceCode ?: return
        val all = PlaylistManager.getAll(this)
        if (all.isEmpty()) { Toast.makeText(this, "Nessuna playlist", Toast.LENGTH_SHORT).show(); return }
        pollExecutor.execute {
            try {
                val json = com.google.gson.Gson().toJson(all)
                val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/backups/$dev.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"; conn.doOutput = true
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.outputStream.write(json.toByteArray())
                conn.responseCode; conn.disconnect()
                mainHandler.post { Toast.makeText(this@MainActivity, "Backup salvato!", Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) {
                mainHandler.post { Toast.makeText(this@MainActivity, "Backup fallito", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun restorePlaylists() {
        val dev = mqttSync?.deviceCode ?: return
        pollExecutor.execute {
            try {
                val url = URL("https://iptv-player-eac9e-default-rtdb.europe-west1.firebasedatabase.app/backups/$dev.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = if (conn.responseCode == 200) java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText() else ""
                conn.disconnect()
                if (body.isNotBlank() && body.startsWith("[")) {
                    val type = object : com.google.gson.reflect.TypeToken<List<Playlist>>() {}.type
                    val list: List<Playlist> = com.google.gson.Gson().fromJson(body, type)
                    for (pl in list) { PlaylistManager.addOrUpdate(this@MainActivity, pl) }
                    mainHandler.post {
                        updateCodeText("Ripristinate ${list.size}")
                        Toast.makeText(this@MainActivity, "${list.size} playlist ripristinate!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    mainHandler.post { Toast.makeText(this@MainActivity, "Nessun backup", Toast.LENGTH_SHORT).show() }
                }
            } catch (_: Exception) {
                mainHandler.post { Toast.makeText(this@MainActivity, "Ripristino fallito", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun loadFromBlob(blobId: String) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = "Scaricamento config..."
        emptyText.visibility = View.GONE
        executor.execute {
            val config = HttpSync.fetchConfig(blobId, this)
            mainHandler.post {
                progressBar.visibility = View.GONE
                if (config != null && config.m3uUrl.isNotBlank()) {
                    applyConfig(config)
                    Toast.makeText(this@MainActivity, "Configurazione caricata!", Toast.LENGTH_SHORT).show()
                } else {
                    statusText.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Codice non valido o scaduto", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when {
                    currentCategory != null -> {
                        showCategories()
                        true
                    }
                    showingPlaylists -> {
                        checkExit()
                        true
                    }
                    else -> {
                        val all = PlaylistManager.getAll(this)
                        if (all.size > 1) {
                            showPlaylists(all)
                            true
                        } else {
                            checkExit()
                            true
                        }
                    }
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun checkExit() {
        if (backPressedOnce) {
            finish()
        } else {
            backPressedOnce = true
            Toast.makeText(this, "Premi di nuovo INDIETRO per uscire", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ backPressedOnce = false }, 2000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttSync?.disconnect()
        webServer?.stop()
        executor.shutdown()
        pollExecutor.shutdown()
    }
}

class PlaylistSelectAdapter(
    private val playlists: List<Playlist>,
    private val active: Playlist?,
    private val onClick: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistSelectAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.categoryName)
        val url: TextView = view.findViewById(R.id.categoryCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = playlists[pos]
        val isActive = p.id == active?.id
        h.name.text = if (isActive) "✔ ${p.name}" else p.name
        h.url.text = p.url
        h.itemView.setOnClickListener { onClick(p) }
        h.itemView.setOnLongClickListener { onDelete(p); true }
        h.itemView.isFocusable = true
        h.itemView.isFocusableInTouchMode = true
    }

    override fun getItemCount() = playlists.size
}

class SidebarAdapter(
    private val categories: List<Category>,
    private val onSelect: (Category) -> Unit
) : RecyclerView.Adapter<SidebarAdapter.VH>() {
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.categoryName)
        val count: TextView = view.findViewById(R.id.categoryCount)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))
    }
    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = categories[pos]
        h.name.text = c.name
        h.count.text = "${c.count}"
        h.itemView.setOnClickListener { onSelect(c) }
        h.itemView.isFocusable = true
        h.itemView.isFocusableInTouchMode = true
    }
    override fun getItemCount() = categories.size
}
