package com.iptv.player.model

data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null
)
