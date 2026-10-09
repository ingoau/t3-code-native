package codes.t3.android.data

import codes.t3.android.data.rpc.RpcClient
import codes.t3.android.data.rpc.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Drives [RpcClient] against a scripted Effect-RPC-style WebSocket server. */
class RpcClientTest {
    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun start() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val msg = Json.parseToJsonElement(text).jsonObject
                when (msg["_tag"]!!.jsonPrimitive.content) {
                    "Ping" -> webSocket.send("""{"_tag":"Pong"}""")
                    "Request" -> {
                        val id = msg["id"]!!.jsonPrimitive.content
                        when (msg["tag"]!!.jsonPrimitive.content) {
                            "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"ok":true}}}""")
                            "fail" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationV2DispatchCommandError","message":"Run r1 is not active."}}]}}""")
                            "stream" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[1,2]}""")
                        }
                    }
                    "Ack" -> {
                        val id = msg["requestId"]!!.jsonPrimitive.content
                        // Only after the first Ack do we send more, mirroring server back-pressure.
                        if (received.count { it.contains("\"Ack\"") } == 1) {
                            webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[3]}""")
                            webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":null}}""")
                        }
                    }
                }
            }
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
        server.start()
    }

    @After fun stop() { scope.cancel(); server.close() }

    private fun client(): RpcClient = RpcClient(OkHttpClient(), scope).also {
        it.connect(server.url("/ws").toString().replaceFirst("http", "ws"))
    }

    @Test fun unaryStreamAndFailure() = runBlocking {
        val rpc = client()
        withTimeout(5_000) { rpc.awaitOpen() }
        val value = withTimeout(5_000) { rpc.call("server.getConfig") }
        assertEquals("true", value.jsonObject["ok"]!!.jsonPrimitive.content)

        val items = withTimeout(5_000) { rpc.stream("stream").toList() }
        assertEquals(listOf("1", "2", "3"), items.map { it.jsonPrimitive.content })
        assertTrue("client must Ack chunks", received.any { it.contains("\"_tag\":\"Ack\"") })

        val error = runCatching { withTimeout(5_000) { rpc.call("fail") } }.exceptionOrNull() as RpcException
        assertEquals("OrchestrationV2DispatchCommandError", error.tag)
        assertEquals("Run r1 is not active.", error.message)

        assertTrue(rpc.ping())
        withTimeout(5_000) { while (!rpc.pongSeen) kotlinx.coroutines.delay(20) }
        rpc.close()
    }

}
