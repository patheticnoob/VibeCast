package dev.vibecast.tv.cast

import android.content.res.AssetManager
import java.io.IOException
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
            else -> withCors(
                Response.newFixedLengthResponse(
                    Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Not found",
                ),
            )
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = CastSocket(handshake)

    fun broadcast(json: String) {
        clients.forEach { socket ->
            runCatching { socket.send(json) }
                .onFailure { LOGGER.log(Level.WARNING, "Failed to push state update", it) }
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

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin,accept,content-type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }

    private inner class CastSocket(handshakeRequest: IHTTPSession) : WebSocket(handshakeRequest) {
        override fun onOpen() {
            clients += this
            listener.onClientCountChanged(clients.size)
            runCatching { send(listener.provideStateJson()) }
                .onFailure { LOGGER.log(Level.WARNING, "Failed to send initial state", it) }
        }

        override fun onClose(code: CloseCode, reason: String, initiatedByRemote: Boolean) {
            clients -= this
            listener.onClientCountChanged(clients.size)
        }

        override fun onMessage(message: WebSocketFrame) {
            listener.onCommand(message.textPayload)
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            LOGGER.log(Level.WARNING, "Socket exception", exception)
        }

        override fun debugFrameReceived(frame: WebSocketFrame) = Unit

        override fun debugFrameSent(frame: WebSocketFrame) = Unit
    }

    companion object {
        private val LOGGER = Logger.getLogger(CastServer::class.java.name)
        const val SOCKET_READ_TIMEOUT: Int = 5_000
    }
}
