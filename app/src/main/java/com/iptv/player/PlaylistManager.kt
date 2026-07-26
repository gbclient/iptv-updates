package com.iptv.player

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val userAgent: String = "VLC/3.0.20 LibVLC/3.0.20",
    val referer: String = ""
)

object PlaylistManager {

    private const val PREFS_KEY = "playlists"
    private const val ACTIVE_KEY = "active_playlist_id"

    private val gson = Gson()

    fun getAll(context: Context): List<Playlist> {
        val json = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString(PREFS_KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<Playlist>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getActive(context: Context): Playlist? {
        val activeId = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .getString(ACTIVE_KEY, null) ?: return null
        return getAll(context).find { it.id == activeId }
    }

    fun addOrUpdate(context: Context, playlist: Playlist) {
        val list = getAll(context).toMutableList()
        val existing = list.indexOfFirst { it.id == playlist.id }
        if (existing >= 0) {
            list[existing] = playlist
        } else {
            list.add(playlist)
        }
        save(context, list)
        if (getActive(context) == null) {
            setActive(context, playlist.id)
        }
    }

    fun delete(context: Context, id: String) {
        val list = getAll(context).filter { it.id != id }
        save(context, list)
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        if (prefs.getString(ACTIVE_KEY, null) == id) {
            prefs.edit().putString(ACTIVE_KEY, list.firstOrNull()?.id).apply()
        }
    }

    fun setActive(context: Context, id: String) {
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_KEY, id).apply()
    }

    fun getNext(context: Context): Playlist? {
        val list = getAll(context)
        if (list.isEmpty()) return null
        val active = getActive(context)
        val idx = list.indexOfFirst { it.id == active?.id }
        val next = list[(idx + 1) % list.size]
        setActive(context, next.id)
        return next
    }

    fun getPrev(context: Context): Playlist? {
        val list = getAll(context)
        if (list.isEmpty()) return null
        val active = getActive(context)
        val idx = list.indexOfFirst { it.id == active?.id }
        val prev = list[(idx - 1 + list.size) % list.size]
        setActive(context, prev.id)
        return prev
    }

    private fun save(context: Context, list: List<Playlist>) {
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, gson.toJson(list)).apply()
    }
}
