// app/src/main/java/com/fileserver/app/NanoHTTPD.kt
package com.fileserver.app

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Small, dependency-free multi-threaded HTTP server.
 *
 * Notes on behaviour (compared with the first version):
 *  - The request URI is passed on RAW (still percent-encoded). Decoding is done exactly once by the
 *    code that parses query parameters. Previously the whole URI was decoded here and decoded again
 *    later, which corrupted names that contained %, + or &.
 *  - Header sizes are bounded, exceptions in serve() become a 500 response, HEAD is supported,
 *    the listening socket uses SO_REUSEADDR so the server can be restarted immediately.
 */
abstract class NanoHTTPD(private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    private var running = false

    companion object {
        private const val MAX_LINE = 16 * 1024
        private const val MAX_HEADERS = 100
        private const val MAX_IN_MEMORY_BODY = 50L * 1024 * 1024
        private const val HEADER_TIMEOUT_MS = 30_000
        private const val BODY_TIMEOUT_MS = 30 * 60 * 1000
    }

    /** Starts listening. Throws (e.g. BindException) if the port cannot be used. */
    fun start() {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            try { ss.close() } catch (_: Exception) {}
            throw e
        }
        serverSocket = ss
        executor = Executors.newCachedThreadPool()
        running = true

        Thread({ acceptLoop(ss) }, "http-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val socket = ss.accept()
                val ex = executor
                if (ex == null) {
                    socket.close()
                    continue
                }
                try {
                    ex.execute { handleClient(socket) }
                } catch (e: RejectedExecutionException) {
                    try { socket.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 512 * 1024
                socket.receiveBufferSize = 512 * 1024
            } catch (_: Exception) {
            }

            val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
            val output = socket.getOutputStream()
            val remote = socket.inetAddress?.hostAddress ?: "?"

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1] // RAW, still percent-encoded

            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (++count > MAX_HEADERS) throw IOException("Too many headers")
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            socket.soTimeout = BODY_TIMEOUT_MS

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            val contentType = headers["content-type"] ?: ""
            val isMultipart = contentType.contains("multipart/form-data", ignoreCase = true)

            if (contentLength > 0 && isMultipart) {
                // Direct streaming, nothing is buffered in memory or in a temp file.
                val limited = LimitedInputStream(input, contentLength)
                val request = HTTPRequest(method, uri, headers, "", ByteArray(0), contentLength, limited, remote)
                val response = safeServe(request)
                limited.drain()
                sendResponse(output, response, method == "HEAD")
            } else if (contentLength > MAX_IN_MEMORY_BODY) {
                val response = HTTPResponse.newFixedLengthResponse(
                    "413 Payload Too Large", "application/json",
                    "{\"error\":\"Request body too large\"}"
                )
                sendResponse(output, response, false)
            } else if (contentLength > 0) {
                val bodyBytes = readBytes(input, contentLength)
                val body = String(bodyBytes, Charsets.UTF_8)
                val request = HTTPRequest(method, uri, headers, body, bodyBytes, contentLength, null, remote)
                sendResponse(output, safeServe(request), method == "HEAD")
            } else {
                val request = HTTPRequest(method, uri, headers, "", ByteArray(0), 0, null, remote)
                sendResponse(output, safeServe(request), method == "HEAD")
            }
        } catch (_: IOException) {
            // client went away / timed out - normal for a file server
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun safeServe(request: HTTPRequest): HTTPResponse {
        return try {
            serve(request)
        } catch (e: Throwable) {
            val msg = (e.message ?: e.javaClass.simpleName).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ")
            HTTPResponse.newFixedLengthResponse(
                "500 Internal Server Error", "application/json", "{\"error\":\"$msg\"}"
            )
        }
    }

    /** Limits reads to the request's Content-Length so the next request's bytes are never consumed. */
    class LimitedInputStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            if (len == 0) return 0
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()

        fun drain() {
            try {
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = read(buffer, 0, buffer.size)
                    if (n <= 0) break
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            }
            if (c == '\n'.code) {
                val bytes = buffer.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            buffer.write(c)
            if (buffer.size() > MAX_LINE) throw IOException("Header line too long")
        }
    }

    private fun readBytes(input: InputStream, length: Long): ByteArray {
        val size = length.toInt()
        val buffer = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = input.read(buffer, total, size - total)
            if (n == -1) break
            total += n
        }
        return if (total == size) buffer else buffer.copyOf(total)
    }

    private fun sendResponse(output: OutputStream, response: HTTPResponse, headOnly: Boolean) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append("\r\n")
        response.headers.forEach { (key, value) ->
            // never allow header injection through file names etc.
            head.append(key).append(": ").append(value.replace("\r", " ").replace("\n", " ")).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        output.flush()

        val data = response.data
        try {
            if (data != null && !headOnly) {
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = data.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                }
            }
            output.flush()
        } finally {
            try { data?.close() } catch (_: Exception) {}
        }
    }

    abstract fun serve(request: HTTPRequest): HTTPResponse

    class HTTPRequest(
        val method: String,
        /** Raw request target, still percent-encoded, including the query string. */
        val uri: String,
        val headers: Map<String, String>,
        val body: String,
        val bodyBytes: ByteArray,
        val contentLength: Long = 0,
        val inputStream: InputStream? = null,
        val remoteAddr: String = ""
    )

    class HTTPResponse(
        var status: String,
        val mimeType: String,
        val headers: MutableMap<String, String> = linkedMapOf(),
        var data: InputStream? = null
    ) {
        init {
            headers["Content-Type"] = mimeType
            headers["Connection"] = "close"
            headers["X-Content-Type-Options"] = "nosniff"
        }

        companion object {
            fun newFixedLengthResponse(status: String, mimeType: String, data: String): HTTPResponse {
                val bytes = data.toByteArray(Charsets.UTF_8)
                return HTTPResponse(status, mimeType).apply {
                    this.data = ByteArrayInputStream(bytes)
                    headers["Content-Length"] = bytes.size.toString()
                }
            }

            fun newFixedLengthResponse(status: String, mimeType: String, data: InputStream, length: Long): HTTPResponse {
                return HTTPResponse(status, mimeType).apply {
                    this.data = data
                    headers["Content-Length"] = length.toString()
                }
            }
        }
    }
}
