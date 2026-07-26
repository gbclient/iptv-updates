package com.iptv.player

import android.content.Context
import java.io.File
import java.net.NetworkInterface

object DeviceInfo {
    fun getMacAddress(): String {
        return try {
            // Metodo 1: NetworkInterface
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.name.equals("wlan0", ignoreCase = true) || 
                    ni.name.equals("eth0", ignoreCase = true)) {
                    val mac = ni.hardwareAddress
                    if (mac != null && mac.isNotEmpty()) {
                        return mac.joinToString(":") { "%02x".format(it) }
                    }
                }
            }
            // Metodo 2: Legge file di sistema (per Android TV/Firestick)
            val file = File("/sys/class/net/wlan0/address")
            if (file.exists()) {
                file.readText().trim().ifBlank { "N/D" }
            } else {
                "N/D"
            }
        } catch (e: Exception) {
            "N/D"
        }
    }
}
