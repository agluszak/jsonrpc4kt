package com.jetbrains.bsp

import com.jetbrains.bsp.RemoteEndpoint.Companion.DEFAULT_EXCEPTION_HANDLER
import com.jetbrains.bsp.json.*
import com.jetbrains.bsp.messages.ResponseError
import com.jetbrains.bsp.services.ServiceEndpoints
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Function
import kotlin.reflect.KClass


/**
 * This is the entry point for applications that use LSP4J. A Launcher does all the wiring that is necessary to connect
 * your endpoint via an input stream and an output stream.
 *
 * @param <T> remote service interface type
</T> */
interface Launcher<Local, Remote> {
    //------------------------------ Builder Class ------------------------------//
    /**
     * The launcher builder wires up all components for JSON-RPC communication.
     *
     * @param <T> remote service interface type
    </T> */
    open class Builder<Local : Any, Remote :Any>(
        val input: InputStream,
        val output: OutputStream,
        val localService: Local,
        val remoteInterface: KClass<out Remote>,
        val json: Json = Json.Default,
        val executorService: ExecutorService = Executors.newCachedThreadPool(),
        val exceptionHandler: Function<Throwable, ResponseError> = DEFAULT_EXCEPTION_HANDLER
    ) {

        fun create(): Launcher<Local, Remote> {
            // Create the JSON handler, remote endpoint and remote proxy
            val jsonHandler: MessageJsonHandler = createJsonHandler()
            val remoteEndpoint = createRemoteEndpoint(jsonHandler)
            val remoteProxy = createProxy(remoteEndpoint)

            // Create the message processor
            val reader = StreamMessageProducer(input, jsonHandler, remoteEndpoint)
            val msgProcessor: ConcurrentMessageProcessor = createMessageProcessor(reader, remoteEndpoint, remoteProxy)
            return createLauncher(executorService, remoteProxy, remoteEndpoint, msgProcessor)
        }

        /**
         * Create the JSON handler for messages between the local and remote services.
         */
        protected fun createJsonHandler(): MessageJsonHandler {
            return MessageJsonHandler(
                json,
                supportedMethods)
        }

        /**
         * Create the remote endpoint that communicates with the local services.
         */
        protected fun createRemoteEndpoint(jsonHandler: MessageJsonHandler): RemoteEndpoint {
            val outgoingMessageStream: MessageConsumer = StreamMessageConsumer(output, jsonHandler)
            val localEndpoint: Endpoint = ServiceEndpoints.toEndpoint(localService)
            val remoteEndpoint = RemoteEndpoint(outgoingMessageStream, localEndpoint, exceptionHandler)
            jsonHandler.methodProvider = remoteEndpoint
            return remoteEndpoint
        }

        /**
         * Create the proxy for calling methods on the remote service.
         */
        protected fun createProxy(remoteEndpoint: RemoteEndpoint): Remote {
            return ServiceEndpoints.toServiceObject(remoteEndpoint, remoteInterface)
        }

        /**
         * Create the message processor that listens to the input stream.
         */
        protected open fun createMessageProcessor(
            reader: MessageProducer, messageConsumer: MessageConsumer, remoteProxy: Remote
        ): ConcurrentMessageProcessor {
            return ConcurrentMessageProcessor(reader, messageConsumer)
        }

        protected fun createLauncher(
            execService: ExecutorService,
            remoteProxy: Remote,
            remoteEndpoint: RemoteEndpoint,
            msgProcessor: ConcurrentMessageProcessor
        ): Launcher<Local, Remote> {
            return StandardLauncher(execService, remoteProxy, remoteEndpoint, msgProcessor)
        }

        /**
         * Gather all JSON-RPC methods from the local and remote services.
         */
        protected val supportedMethods: Map<String, JsonRpcMethod> = run {
            val supportedMethods: MutableMap<String, JsonRpcMethod> = LinkedHashMap()
            supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(remoteInterface))
            supportedMethods.putAll(ServiceEndpoints.getSupportedMethods(localService::class))
            supportedMethods
        }

    }
    //---------------------------- Interface Methods ----------------------------//
    /**
     * Start a thread that listens to the input stream. The thread terminates when the stream is closed.
     *
     * @return a future that returns `null` when the listener thread is terminated
     */
    fun startListening(): Future<Unit>

    /**
     * Returns the proxy instance that implements the remote service interfaces.
     */
    val remoteProxy: Remote

    /**
     * Returns the remote endpoint. Use this one to send generic `request` or `notify` methods
     * to the remote services.
     */
    val remoteEndpoint: RemoteEndpoint

    companion object {
        /**
         * Create a new Launcher for a given local service object, a given remote interface and an input and output stream.
         *
         * @param localService - the object that receives method calls from the remote service
         * @param remoteInterface - an interface on which RPC methods are looked up
         * @param in - input stream to listen for incoming messages
         * @param out - output stream to send outgoing messages
         */
        fun <Local: Any, Remote: Any> createLauncher(
            localService: Local, remoteInterface: KClass<Remote>, input: InputStream, output: OutputStream
        ): Launcher<Local, Remote> {
            return Builder<Local, Remote>(input, output, localService, remoteInterface).create()
        }
    }
}
