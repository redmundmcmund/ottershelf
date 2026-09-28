package io.github.ottershelf.core.network

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A server on 127.0.0.1 for the Api tests (the JDK's own, so no test library is needed): every
 * request is recorded and answered by [answer]. Nothing here reaches the real server.
 */
class FakeServer : Closeable {

    class Received(val method: String, val path: String, val body: String, val authorization: String?)

    /**
     * An answer: [code], [body] and [headers]. [chunked] sends the body without a Content-Length;
     * [drop] closes the connection without answering at all.
     */
    class Answer(
        val code: Int = 200,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val chunked: Boolean = false,
        val drop: Boolean = false,
    )

    val received = CopyOnWriteArrayList<Received>()

    @Volatile var answer: (Received) -> Answer = { Answer(404) }

    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val request = Received(
                exchange.requestMethod,
                exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
                exchange.requestBody.readBytes().decodeToString(),
                exchange.requestHeaders.getFirst("Authorization"),
            )
            received += request
            val reply = answer(request)
            if (reply.drop) throw IOException("dropped on purpose")
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.encodeToByteArray()
            val length = when {
                bytes.isEmpty() -> -1L
                reply.chunked -> 0L
                else -> bytes.size.toLong()
            }
            exchange.sendResponseHeaders(reply.code, length)
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        executor = pool
        start()
    }

    /** No trailing slash, like a stored server address. */
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun paths(): List<String> = received.map { it.path }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
