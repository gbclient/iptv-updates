package com.iptv.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamRelayServer(private val relayPort: Int = 8889) {

    private val relays = ConcurrentHashMap<String, String>()
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val threadPool = Executors.newCachedThreadPool()

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun start() {
        if (running) return
        running = true
        try {
            serverSocket = ServerSocket(relayPort)
            Log.i("RELAY", "server started on port $relayPort")
            threadPool.execute { acceptLoop() }
        } catch (e: Exception) {
            Log.e("RELAY", "start error", e)
            running = false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                threadPool.execute { handleSocket(socket) }
            } catch (e: Exception) {
                if (running) Log.e("RELAY", "accept error", e)
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            // Legge headers fino a riga vuota
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            if (!path.startsWith("/stream/")) {
                sendError(output, 404, "Not found")
                return
            }

            val reqId = path.removePrefix("/stream/")
            val streamUrl = relays[reqId]
            if (streamUrl == null) {
                Log.w("RELAY", "relay not found: $reqId")
                sendError(output, 404, "Relay not found")
                return
            }

            Log.i("RELAY", "proxying $reqId")
            val host = try { java.net.URL(streamUrl).host } catch (_: Exception) { "" }
            val request = Request.Builder().url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "http://$host/c/")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Connection", "keep-alive")
                .build()
            val response = okHttp.newCall(request).execute()
            if (!response.isSuccessful) {
                sendError(output, 502, "Upstream ${response.code}")
                response.close()
                return
            }
            val srcContentType = response.body?.contentType()?.toString() ?: "video/MP2T"
            val body = response.body ?: return
            val stream = body.byteStream()

            // Risposta HTTP senza chunked encoding, senza Content-Length
            val statusLine = "HTTP/1.1 200 OK\r\n"
            val hdrs = "Content-Type: $srcContentType\r\n" +
                       "Connection: close\r\n" +
                       "Access-Control-Allow-Origin: *\r\n\r\n"
            output.write((statusLine + hdrs).toByteArray())
            output.flush()

            // Pipa byte
            pipeStream(stream, output)
            stream.close()
            output.flush()
        } catch (e: Exception) {
            Log.e("RELAY", "handle error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun pipeStream(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(32768)
        try {
            while (running) {
                val read = src.read(buf)
                if (read < 0) break
                dst.write(buf, 0, read)
                dst.flush()
            }
        } catch (e: Exception) {
            // Client disconnesso o stream finito
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun sendError(output: OutputStream, code: Int, msg: String) {
        val body = "$code $msg"
        val response = "HTTP/1.1 $code $msg\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        output.write(response.toByteArray())
        output.flush()
    }

    fun addRelay(requestId: String, streamUrl: String) {
        relays[requestId] = streamUrl
        Log.i("RELAY", "add $requestId -> ${streamUrl.take(80)}")
    }

    fun removeRelay(requestId: String) {
        relays.remove(requestId)
    }

    fun clear() {
        relays.clear()
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip
                    }
                }
            }
            val allIfs = NetworkInterface.getNetworkInterfaces()
            while (allIfs.hasMoreElements()) {
                val ni = allIfs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RELAY", "IP detection error", e)
        }
        return "127.0.0.1"
    }
}
