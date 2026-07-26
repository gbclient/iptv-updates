package com.iptv.player

data class NetworkConfig(
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyType: String = "", // "socks" or "http"
    val proxyUser: String = "",
    val proxyPass: String = "",
    val dnsServer: String = "", // custom DNS IP e.g. "1.1.1.1"
    val extraHeaders: Map<String, String> = emptyMap()
)
