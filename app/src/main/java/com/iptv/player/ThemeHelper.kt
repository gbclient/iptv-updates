package com.iptv.player

import android.content.Context

object ThemeHelper {
    val themes = listOf(
        Theme("Rosso", 0xFFE94560.toInt(), 0xFF0F3460.toInt(), 0xFF0a0a1a.toInt()),
        Theme("Blu", 0xFF42A5F5.toInt(), 0xFF0d47a1.toInt(), 0xFF0a0e1a.toInt()),
        Theme("Verde", 0xFF66BB6A.toInt(), 0xFF1b5e20.toInt(), 0xFF0a0f0a.toInt()),
        Theme("Viola", 0xFFAB47BC.toInt(), 0xFF4a148c.toInt(), 0xFF0e0a12.toInt()),
        Theme("Arancio", 0xFFFF9800.toInt(), 0xFFe65100.toInt(), 0xFF120e0a.toInt())
    )

    fun get(ctx: Context): Theme {
        val idx = ctx.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).getInt("theme", 0)
        return themes.getOrElse(idx) { themes[0] }
    }

    fun set(ctx: Context, index: Int) {
        ctx.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).edit().putInt("theme", index).apply()
    }
}

data class Theme(val name: String, val accent: Int, val secondary: Int, val background: Int)