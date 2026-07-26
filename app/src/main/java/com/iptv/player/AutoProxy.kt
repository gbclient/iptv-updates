package com.iptv.player

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.atomic.AtomicReference

object AutoProxy {

    data class ProxyInfo(
        val host: String,
        val port: Int,
        val type: String = "socks",
        val speed: Long = 0,
        val working: Boolean = false
    )

    @Volatile var bestProxy: ProxyInfo? = null
    @Volatile var isScanning = false
    @Volatile var scanProgress = ""

    private const val TAG = "AutoProxy"

    fun startScan(context: Context, onResult: (ProxyInfo?) -> Unit) {
        if (isScanning) return
        isScanning = true
        bestProxy = null
        scanProgress = "Ricerca proxy..."

        Thread {
            try {
                // 1. Prova proxy locali (VPN)
                checkLocal()?.let {
                    bestProxy = it
                    scanProgress = "VPN locale: ${it.host}:${it.port} (${it.speed}ms)"
                    onResult(it)
                    isScanning = false
                    return@Thread
                }

                // 2. Fetch da API
                val proxies = fetchProxies()
                if (proxies.isEmpty()) {
                    scanProgress = "Nessun proxy trovato"
                    onResult(null)
                    isScanning = false
                    return@Thread
                }

                // 3. Speed test (fino a 20 proxy)
                val working = mutableListOf<ProxyInfo>()
                for ((i, p) in proxies.take(20).withIndex()) {
                    scanProgress = "Test ${i + 1}/${minOf(20, proxies.size)}: ${p.host}"
                    val speed = testSpeed(p)
                    if (speed > 0) {
                        working.add(p.copy(speed = speed, working = true))
                    }
                }

                if (working.isNotEmpty()) {
                    bestProxy = working.minByOrNull { it.speed }
                    scanProgress = "OK: ${bestProxy!!.host}:${bestProxy!!.port} (${bestProxy!!.speed}ms)"
                    onResult(bestProxy)
                } else {
                    scanProgress = "Nessun proxy funzionante"
                    onResult(null)
                }
            } catch (e: Exception) {
                scanProgress = "Errore: ${e.message}"
                onResult(null)
            }
            isScanning = false
        }.start()
    }

    private fun checkLocal(): ProxyInfo? {
        val local = listOf(
            ProxyInfo("127.0.0.1", 9050, "socks"),
            ProxyInfo("127.0.0.1", 1080, "socks"),
            ProxyInfo("127.0.0.1", 10808, "socks"),
            ProxyInfo("127.0.0.1", 7890, "http"),
        )
        for (p in local) {
            val speed = testSpeed(p)
            if (speed > 0) return p.copy(speed = speed, working = true)
        }
        return null
    }

    private fun fetchProxies(): List<ProxyInfo> {
        val proxies = mutableListOf<ProxyInfo>()
        val apis = listOf(
            "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=5000&country=all&limit=50",
            "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all&limit=20",
        )
        for (api in apis) {
            try {
                val url = URL(api)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "IPTVPlayer/1.0")
                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                conn.disconnect()
                for (line in text.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.contains(":")) {
                        val parts = trimmed.split(":")
                        if (parts.size >= 2) {
                            val host = parts[0]
                            val port = parts[1].toIntOrNull() ?: continue
                            val type = if (api.contains("http")) "http" else "socks"
                            proxies.add(ProxyInfo(host, port, type))
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return proxies.distinctBy { "${it.host}:${it.port}" }
    }

    private fun testSpeed(info: ProxyInfo): Long {
        return try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(info.host, info.port), 3000)
            val time = System.currentTimeMillis() - start
            socket.close()
            time
        } catch (_: Exception) {
            -1
        }
    }

    fun getProxyForM3U(context: Context, enabled: Boolean): Proxy? {
        if (!enabled) return null
        val proxy = bestProxy ?: return null
        if (!proxy.working) return null
        val type = if (proxy.type == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress(proxy.host, proxy.port))
    }

    fun buildOkHttpProxy(info: ProxyInfo): java.net.Proxy {
        val type = if (info.type == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return java.net.Proxy(type, InetSocketAddress(info.host, info.port))
    }
}
