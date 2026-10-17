package com.samge.pocketsite

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal embedded static HTTP server.
 * - SPA fallback: extension-less routes -> /index.html
 * - HTTP Range support (video/audio seeking)
 * - Correct MIME for html/css/js/images/fonts/media
 */
class StaticSiteServer(
    val port: Int,
    private val rootDir: File,
    private val onLog: (String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = ServerSocket(port)
            running.set(true)
            Thread({
                while (running.get()) {
                    try {
                        val sock = serverSocket!!.accept()
                        pool.execute { handle(sock) }
                    } catch (e: Exception) {
                        if (running.get()) onLog("accept: ${e.message}")
                    }
                }
            }, "site-server-accept").start()
            onLog("started on :$port serving ${rootDir.path}")
            true
        } catch (e: Exception) {
            onLog("start failed: ${e.message}")
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        onLog("stopped")
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val out = sock.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1]
            var rangeStart: Long = -1
            var rangeEnd: Long = -1
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (name == "range" && value.startsWith("bytes=")) {
                    val spec = value.removePrefix("bytes=").split("-")
                    if (spec.isNotEmpty() && spec[0].isNotEmpty()) rangeStart = spec[0].toLongOrNull() ?: -1
                    if (spec.size > 1 && spec[1].isNotEmpty()) rangeEnd = spec[1].toLongOrNull() ?: -1
                }
            }

            val pathOnly = rawPath.substringBefore('?')
            val decoded = URLDecoder.decode(pathOnly, "UTF-8")
            val rel = decoded.trimStart('/')
            var file = File(rootDir, rel)

            // path-traversal guard: resolved path must stay inside rootDir
            // (double-decoded ../ or absolute escapes are rejected)
            if (!file.canonicalPath.startsWith(rootDir.canonicalPath + File.separator) &&
                file.canonicalPath != rootDir.canonicalPath
            ) {
                sendText(out, 403, "text/html; charset=utf-8", notFoundPage(rel))
                return
            }

            if (file.isDirectory) {
                val idx = File(file, "index.html")
                if (idx.isFile) file = idx else {
                    sendText(out, 404, "text/html; charset=utf-8", notFoundPage(rel))
                    return
                }
            } else if (!file.isFile) {
                // SPA fallback: no extension -> index.html at site root
                if (!rel.substringAfterLast('/').contains('.')) {
                    val spa = File(rootDir, "index.html")
                    if (spa.isFile) file = spa else {
                        sendText(out, 404, "text/html; charset=utf-8", notFoundPage(rel))
                        return
                    }
                } else {
                    sendText(out, 404, "text/html; charset=utf-8", notFoundPage(rel))
                    return
                }
            }

            serveFile(out, file, rangeStart, rangeEnd)
        } catch (e: Exception) {
            onLog("handle: ${e.message}")
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {}
        }
    }

    private fun serveFile(out: java.io.OutputStream, file: File, rangeStart: Long, rangeEnd: Long) {
        val len = file.length()
        val mime = Mime.of(file.name)
        if (rangeStart >= 0 && rangeStart < len) {
            val end = if (rangeEnd in rangeStart until len) rangeEnd else len - 1
            val chunk = end - rangeStart + 1
            out.write(
                (
                    "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $mime\r\n" +
                        "Content-Length: $chunk\r\n" +
                        "Content-Range: bytes $rangeStart-$end/$len\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            java.io.FileInputStream(file).use { input ->
                input.skip(rangeStart)
                copy(input, out, chunk)
            }
        } else {
            val head =
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mime\r\n" +
                        "Content-Length: $len\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1)
            out.write(head)
            java.io.FileInputStream(file).use { input -> copy(input, out, len) }
        }
        out.flush()
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = max
        while (remaining > 0) {
            val read = input.read(buf, 0, if (remaining > buf.size) buf.size else remaining.toInt())
            if (read < 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun sendText(out: java.io.OutputStream, code: Int, mime: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head =
            (
                "HTTP/1.1 $code X\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1)
        out.write(head)
        out.write(bytes)
        out.flush()
    }

    private fun notFoundPage(path: String): String =
        "<html><body style='font-family:sans-serif;background:#102233;color:#eee;display:grid;place-items:center;height:90vh'>" +
            "<div style='text-align:center'><h1>404</h1><p>$path</p></div></body></html>"
}

object Mime {
    private val map =
        mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "mjs" to "application/javascript; charset=utf-8",
            "json" to "application/json",
            "txt" to "text/plain; charset=utf-8",
            "xml" to "application/xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mov" to "video/quicktime",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "pdf" to "application/pdf",
        )

    fun of(name: String): String = map[name.substringAfterLast('.').lowercase()] ?: "application/octet-stream"
}
