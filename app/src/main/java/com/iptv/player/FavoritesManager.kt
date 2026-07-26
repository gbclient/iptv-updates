package com.iptv.player

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object FavoritesManager {
    private val gson = Gson()
    private const val FAVS_KEY = "favorites"
    private const val HISTORY_KEY = "history"

    fun getFavorites(context: Context): List<String> {
        val json = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).getString(FAVS_KEY, null) ?: return emptyList()
        return try { gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun toggleFavorite(context: Context, url: String): Boolean {
        val favs = getFavorites(context).toMutableList()
        val isFav = if (favs.contains(url)) { favs.remove(url); false } else { favs.add(url); true }
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putString(FAVS_KEY, gson.toJson(favs)).apply()
        return isFav
    }

    fun isFavorite(context: Context, url: String): Boolean = getFavorites(context).contains(url)

    fun getHistory(context: Context): List<String> {
        val json = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).getString(HISTORY_KEY, null) ?: return emptyList()
        return try { gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun addToHistory(context: Context, url: String) {
        val hist = getHistory(context).toMutableList()
        hist.remove(url)
        hist.add(0, url)
        if (hist.size > 20) hist.removeAt(hist.size - 1)
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putString(HISTORY_KEY, gson.toJson(hist)).apply()
    }
}
