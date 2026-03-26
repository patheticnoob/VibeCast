package dev.vibecast.tv.cast

import android.content.res.AssetManager
import java.io.FilterInputStream
import java.io.IOException
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.logging.Level
import java.util.logging.Logger
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.request.Method
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status
import org.nanohttpd.protocols.websockets.CloseCode
import org.nanohttpd.protocols.websockets.NanoWSD
import org.nanohttpd.protocols.websockets.WebSocket
import org.nanohttpd.protocols.websockets.WebSocketFrame

class CastServer(
    port: Int,
    private val assetManager: AssetManager,
    private val listener: Listener,
) : NanoWSD(port) {

    interface Listener {
        fun onClientCountChanged(count: Int)
        fun onCommand(message: String)
        fun provideStateJson(): String
        fun resolveStreamProxy(proxyId: String): StreamProxyTarget?
    }

    private val clients = CopyOnWriteArraySet<CastSocket>()

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return withCors(
                Response.newFixedLengthResponse(Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, ""),
            )
        }

        return when (session.uri) {
            "/", "/index.html" -> assetResponse("controller/index.html", "text/html; charset=utf-8")
            "/app.js" -> assetResponse("controller/app.js", "application/javascript; charset=utf-8")
            "/styles.css" -> assetResponse("controller/styles.css", "text/css; charset=utf-8")
            "/state" -> jsonResponse(listener.provideStateJson())
            else -> if (session.uri.startsWith("/proxy/")) {
                proxyResponse(session)
            } else {
                withCors(
                    Response.newFixedLengthResponse(
                        Status.NOT_FOUND,
                        NanoHTTPD.MIME_PLAINTEXT,
                        "Not found",
                    ),
                )
            }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = CastSocket(handshake)

    fun broadcast(json: String) {
        clients.forEach { socket ->
            runCatching { socket.send(json) }
                .onFailure {
                    LOGGER.log(Level.WARNING, "Failed to push state update", it)
                    unregister(socket)
                }
        }
    }

    private fun assetResponse(assetPath: String, mimeType: String): Response {
        return runCatching {
            withCors(Response.newChunkedResponse(Status.OK, mimeType, assetManager.open(assetPath)))
        }.getOrElse {
            withCors(
                Response.newFixedLengthResponse(
                    Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing asset: $assetPath",
                ),
            )
        }
    }

    private fun jsonResponse(body: String): Response {
        return withCors(
            Response.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", body),
        )
    }

    private fun proxyResponse(session: IHTTPSession): Response {
        val proxyId = session.uri.substringAfter("/proxy/").substringBefore('?')
        val target = listener.resolveStreamProxy(proxyId)
            ?: return withCors(
                Response.newFixedLengthResponse(
                    Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Missing proxy target",
                ),
            )

        return runCatching {
            val upstreamUrl = resolveProxyTargetUrl(
                proxyRootUrl = target.url,
                requestUri = session.uri,
                rawQuery = session.queryParameterString,
                explicitUpstreamUrl = session.parameters["url"]?.firstOrNull(),
            )

            val connection = (URL(upstreamUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = if (session.method == Method.HEAD) "HEAD" else "GET"
                target.headers.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        setRequestProperty(key, value)
                    }
                }
                session.headers["range"]?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Range", it) }
                connect()
            }

            val status = Status.lookup(connection.responseCode) ?: Status.OK
            val mimeType = connection.contentType ?: "application/octet-stream"
            val resolvedUrl = connection.url.toString()
            val manifestType = detectManifestType(resolvedUrl, mimeType)

            if (session.method != Method.HEAD && manifestType != ManifestType.NONE) {
                val body = (connection.errorStream ?: connection.inputStream)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
                connection.disconnect()

                val rewrittenBody = when (manifestType) {
                    ManifestType.HLS -> rewriteHlsManifest(
                        body = body,
                        manifestUrl = resolvedUrl,
                        proxyId = proxyId,
                        proxyBaseUrl = buildProxyBaseUrl(session),
                        rootOrigin = target.rootOrigin,
                    )
                    ManifestType.XML -> rewriteXmlManifest(
                        body = body,
                        documentUrl = resolvedUrl,
                        proxyId = proxyId,
                        proxyBaseUrl = buildProxyBaseUrl(session),
                        rootOrigin = target.rootOrigin,
                    )
                    ManifestType.NONE -> body
                }

                return withCors(
                    Response.newFixedLengthResponse(status, mimeType, rewrittenBody).apply {
                        setRequestMethod(session.method)
                    },
                )
            }

            val inputStream = if (session.method == Method.HEAD) {
                null
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val response = if (inputStream != null) {
                Response.newChunkedResponse(
                    status,
                    mimeType,
                    object : FilterInputStream(inputStream) {
                        override fun close() {
                            super.close()
                            connection.disconnect()
                        }
                    },
                )
            } else {
                connection.disconnect()
                Response.newFixedLengthResponse(status, mimeType, "")
            }

            response.setRequestMethod(session.method)
            copyProxyHeader(connection, response, "Content-Length")
            copyProxyHeader(connection, response, "Content-Range")
            copyProxyHeader(connection, response, "Accept-Ranges")
            copyProxyHeader(connection, response, "Content-Disposition")
            copyProxyHeader(connection, response, "Cache-Control")
            copyProxyHeader(connection, response, "Content-Type")
            withCors(response)
        }.getOrElse { error ->
            LOGGER.log(Level.WARNING, "Proxy request failed for $proxyId", error)
            withCors(
                Response.newFixedLengthResponse(
                    Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Proxy request failed: ${error.localizedMessage}",
                ),
            )
        }
    }

    private fun detectManifestType(url: String, mimeType: String): ManifestType {
        val normalizedMimeType = mimeType.lowercase()
        val normalizedPath = url.substringBefore('?').substringBefore('#').lowercase()

        return when {
            normalizedMimeType.contains("mpegurl") || normalizedPath.endsWith(".m3u8") -> ManifestType.HLS
            normalizedMimeType.contains("dash+xml") ||
                normalizedMimeType.contains("smoothstreaming") ||
                normalizedPath.endsWith(".mpd") ||
                normalizedPath.endsWith(".ism") ||
                normalizedPath.endsWith(".isml") ||
                normalizedPath.endsWith("/manifest") -> ManifestType.XML
            else -> ManifestType.NONE
        }
    }

    private fun rewriteHlsManifest(
        body: String,
        manifestUrl: String,
        proxyId: String,
        proxyBaseUrl: String,
        rootOrigin: String?,
    ): String {
        return body.lineSequence()
            .joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith("#") -> rewriteHlsDirectiveLine(line, manifestUrl, proxyId, proxyBaseUrl, rootOrigin)
                    else -> proxifyReference(line.trim(), manifestUrl, proxyId, proxyBaseUrl, rootOrigin)
                }
            }
    }

    private fun rewriteHlsDirectiveLine(
        line: String,
        manifestUrl: String,
        proxyId: String,
        proxyBaseUrl: String,
        rootOrigin: String?,
    ): String {
        return HLS_URI_ATTRIBUTE.replace(line) { match ->
            val original = match.groupValues[1]
            val rewritten = proxifyReference(original, manifestUrl, proxyId, proxyBaseUrl, rootOrigin)
            "URI=\"$rewritten\""
        }
    }

    private fun rewriteXmlManifest(
        body: String,
        documentUrl: String,
        proxyId: String,
        proxyBaseUrl: String,
        rootOrigin: String?,
    ): String {
        var rewritten = XML_URL_ATTRIBUTE.replace(body) { match ->
            val prefix = match.groupValues[1]
            val original = match.groupValues[2]
            val suffix = match.groupValues[3]
            val updated = proxifyXmlReference(original, documentUrl, proxyId, proxyBaseUrl, rootOrigin)
            "$prefix$updated$suffix"
        }

        rewritten = XML_BASE_URL.replace(rewritten) { match ->
            val original = match.groupValues[1].trim()
            val updated = proxifyXmlReference(original, documentUrl, proxyId, proxyBaseUrl, rootOrigin)
            "<BaseURL>$updated</BaseURL>"
        }

        return rewritten
    }

    private fun proxifyXmlReference(
        value: String,
        documentUrl: String,
        proxyId: String,
        proxyBaseUrl: String,
        rootOrigin: String?,
    ): String {
        if (!looksLikeProxyCandidate(value)) {
            return value
        }
        return proxifyReference(value, documentUrl, proxyId, proxyBaseUrl, rootOrigin)
    }

    private fun proxifyReference(
        value: String,
        baseUrl: String,
        proxyId: String,
        proxyBaseUrl: String,
        rootOrigin: String?,
    ): String {
        if (!looksLikeProxyCandidate(value)) {
            return value
        }

        val resolvedUrl = resolveReference(baseUrl, value)
        return buildLocalProxyUrl(
            baseUrl = proxyBaseUrl,
            proxyId = proxyId,
            upstreamUrl = resolvedUrl,
            rootOrigin = rootOrigin,
        )
    }

    private fun resolveReference(baseUrl: String, value: String): String {
        return when {
            value.startsWith("//") -> {
                val base = URI(baseUrl)
                "${base.scheme}:$value"
            }
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> {
                val base = URI(baseUrl)
                "${base.scheme}://${base.authority}$value"
            }
            else -> URI(baseUrl).resolve(value).toString()
        }
    }

    private fun buildProxyBaseUrl(session: IHTTPSession): String {
        val host = session.headers["host"] ?: throw IllegalStateException("Missing host header")
        return "http://$host"
    }

    private fun looksLikeProxyCandidate(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return false
        }

        return trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("/") ||
            trimmed.startsWith("./") ||
            trimmed.startsWith("../") ||
            (!trimmed.startsWith("#") && !trimmed.contains("://"))
    }

    private fun copyProxyHeader(connection: HttpURLConnection, response: Response, headerName: String) {
        connection.getHeaderField(headerName)?.takeIf { it.isNotBlank() }?.let { response.addHeader(headerName, it) }
    }

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin,accept,content-type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }

    private fun unregister(socket: CastSocket) {
        if (clients.remove(socket)) {
            listener.onClientCountChanged(clients.size)
        }
    }

    private inner class CastSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {
        override fun onOpen() {
            clients += this
            runCatching { send(listener.provideStateJson()) }
                .onFailure {
                    LOGGER.log(Level.WARNING, "Failed to send initial state", it)
                    unregister(this)
                }
            listener.onClientCountChanged(clients.size)
        }

        override fun onClose(code: CloseCode, reason: String, initiatedByRemote: Boolean) {
            unregister(this)
        }

        override fun onMessage(message: WebSocketFrame) {
            listener.onCommand(message.textPayload)
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            LOGGER.log(Level.WARNING, "Socket exception", exception)
            unregister(this)
        }

        override fun debugFrameReceived(frame: WebSocketFrame) = Unit

        override fun debugFrameSent(frame: WebSocketFrame) = Unit
    }

    companion object {
        private val LOGGER = Logger.getLogger(CastServer::class.java.name)
        const val SOCKET_READ_TIMEOUT: Int = 5_000
        private val HLS_URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
        private val XML_URL_ATTRIBUTE = Regex("""((?:media|Media|initialization|Initialization|sourceURL|SourceURL|Url|url|src|Src|href|Href)=")([^"]+)(")""")
        private val XML_BASE_URL = Regex("""<BaseURL>([^<]+)</BaseURL>""")
    }
}

private enum class ManifestType {
    NONE,
    HLS,
    XML,
}
