import com.jetbrains.jsonrpc4kt.Endpoint
import com.jetbrains.jsonrpc4kt.JsonRpcException
import com.jetbrains.jsonrpc4kt.MessageConsumer
import com.jetbrains.jsonrpc4kt.RemoteEndpoint
import com.jetbrains.jsonrpc4kt.json.JsonRpcMethod
import com.jetbrains.jsonrpc4kt.json.MessageJsonHandler
import com.jetbrains.jsonrpc4kt.messages.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.SocketException
import java.util.logging.Level
import kotlin.reflect.typeOf
import kotlin.test.assertFailsWith

class RemoteEndpointTest {

    val jsonHandler = MessageJsonHandler(
        Json.Default, mapOf(
            "request" to JsonRpcMethod.request("request", typeOf<String>(), typeOf<String>()),
            "notification" to JsonRpcMethod.notification("notification", typeOf<String>())
        )
    )

    internal open class TestEndpoint(val jsonHandler: MessageJsonHandler) : Endpoint {
        var notifications: MutableList<NotificationMessage> = ArrayList()
        var requests: MutableMap<RequestMessage, CompletableDeferred<Any?>> = LinkedHashMap()

        override fun notify(method: String, params: List<Any?>) {
            val serializedParams = jsonHandler.serializeParams(method, params)
            notifications.add(NotificationMessage(method, serializedParams))
        }

        override suspend fun request(method: String, params: List<Any?>): Any? {
            val completableDeferred = CompletableDeferred<Any?>(
                currentCoroutineContext().job
            )
            val serializedParams = jsonHandler.serializeParams(method, params)
            requests[RequestMessage(MessageId.StringId("asd"), method, serializedParams)] = completableDeferred

            return completableDeferred.await()
        }
    }

    internal class TestMessageConsumer : MessageConsumer {
        var messages: MutableList<Message> = ArrayList<Message>()
        override fun consume(message: Message) {
            messages.add(message)
        }
    }

    @Test
    fun testNotification() {
        val endp = TestEndpoint(jsonHandler)
        val consumer = TestMessageConsumer()
        val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
        endpoint.consume(NotificationMessage("notification", JsonParams.array(JsonPrimitive("myparam"))))
        val notificationMessage: NotificationMessage = endp.notifications[0]
        assertEquals("notification", notificationMessage.method)
        assertEquals(JsonParams.array(JsonPrimitive("myparam")), notificationMessage.params)
        assertTrue(consumer.messages.isEmpty())
    }

    @Test
    fun testRequest1() = runTest {
        val endp = TestEndpoint(jsonHandler)
        val consumer = TestMessageConsumer()
        val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
        endpoint.consume(RequestMessage(MessageId.StringId("1"), "request", JsonParams.array(JsonPrimitive("myparam"))))
        val (key, value) = endp.requests.entries.iterator().next()
        value.complete("success")
        assertEquals("request", key.method)
        assertEquals(JsonParams.array(JsonPrimitive("myparam")), key.params)
        val responseMessage = consumer.messages[0] as ResponseMessage.Result
        assertEquals(JsonPrimitive("success"), responseMessage.result)
        assertEquals(MessageId.StringId("1"), responseMessage.id)
    }

    @Test
    fun testRequest2() {
        val endp = TestEndpoint(jsonHandler)
        val consumer = TestMessageConsumer()
        val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
        endpoint.consume(RequestMessage(MessageId.NumberId(1), "request", JsonParams.array(JsonPrimitive("myparam"))))
        val (key, value) = endp.requests.entries.iterator().next()
        value.complete("success")
        assertEquals("request", key.method)
        assertEquals(JsonParams.array(JsonPrimitive("myparam")), key.params)
        val responseMessage = consumer.messages[0] as ResponseMessage.Result
        assertEquals(JsonPrimitive("success"), responseMessage.result)
        assertEquals(MessageId.NumberId(1), responseMessage.id)
    }

    @Test
    fun testCompletion() = runTest {
        val endp = TestEndpoint(jsonHandler)
        val consumer = TestMessageConsumer()
        val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
        launch {
            delay(1000)
            endpoint.consume(ResponseMessage.Result(MessageId.NumberId(1), JsonPrimitive("success")))
        }
        val result = endpoint.request("request", listOf("myparam"))
        assertEquals("success", result)
    }

    @Test
    fun testCancellation() = runTest {
        val endp = TestEndpoint(jsonHandler)
        val consumer = TestMessageConsumer()
        val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
        endpoint.consume(RequestMessage(MessageId.StringId("1"), "request", JsonParams.array(JsonPrimitive("myparam"))))
        val (_, value) = endp.requests.entries.iterator().next()
        value.cancel()
        val message = consumer.messages[0] as ResponseMessage.Error
        val error = message.error
        assertEquals(error.code, ResponseErrorCode.RequestCancelled.code)
        assertEquals(error.message, "The request (id: \"1\", method: 'request') has been cancelled")
    }

    // TODO: test unknown method handling

    @Test
    fun testExceptionInEndpoint() {
        LogMessageAccumulator(RemoteEndpoint::class).use { logMessages ->
            val endp: TestEndpoint = object : TestEndpoint(jsonHandler) {
                override suspend fun request(method: String, params: List<Any?>): Any? {
                    throw RuntimeException("BAAZ")
                }
            }
            val consumer = TestMessageConsumer()
            val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
            endpoint.consume(
                RequestMessage(
                    MessageId.StringId("1"),
                    "request",
                    JsonParams.array(JsonPrimitive("myparam"))
                )
            )
            val response = consumer.messages[0] as ResponseMessage.Error
            val error = response.error
            assertEquals("Internal error.", error.message)
            assertEquals(ResponseErrorCode.InternalError.code, error.code)
            val exception = error.data
            assertTrue(exception.toString().contains("java.lang.RuntimeException: BAAZ"))
        }
    }

    @Test
    fun testExceptionInConsumer() = runTest {
        val endp = TestEndpoint(jsonHandler)
        val consumer = MessageConsumer { _ -> throw RuntimeException("BAAZ") }
        val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
        assertFailsWith<RuntimeException>("BAAZ") {
            endpoint.request("request", listOf("myparam"))
        }
    }

    @Test
    fun testExceptionInOutputStream() {
        LogMessageAccumulator(RemoteEndpoint::class).use { logMessages ->
            val endp = TestEndpoint(jsonHandler)
            val consumer: MessageConsumer =
                MessageConsumer { throw JsonRpcException(SocketException("Permission denied: connect")) }
            val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
            endpoint.notify("foo", listOf(null))
            logMessages.await(Level.WARNING, "Error while processing the message")
        }
    }

    @Test
    fun testOutputStreamClosed() {
        LogMessageAccumulator(RemoteEndpoint::class).use { logMessages ->
            val endp = TestEndpoint(jsonHandler)
            val consumer: MessageConsumer = MessageConsumer { throw JsonRpcException(SocketException("Socket closed")) }
            val endpoint = RemoteEndpoint(consumer, endp, jsonHandler)
            endpoint.notify("foo", listOf(null))
            logMessages.await(Level.WARNING, "Error while processing the message")
        }
    }
}