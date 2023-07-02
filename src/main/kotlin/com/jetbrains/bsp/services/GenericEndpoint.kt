package com.jetbrains.bsp.services

import com.jetbrains.bsp.Endpoint
import com.jetbrains.bsp.ResponseErrorException
import com.jetbrains.bsp.messages.ResponseError
import com.jetbrains.bsp.messages.ResponseErrorCode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

/**
 * An endpoint that reflectively delegates to [JsonNotification] and
 * [JsonRequest] methods of one or more given delegate objects.
 */
class GenericEndpoint<T>(delegate: T) : Endpoint {
    private val methodHandlers = LinkedHashMap<String, Function<List<Any?>, CompletableFuture<*>?>>()

    init {
        recursiveFindRpcMethods(delegate, HashSet())
    }

     private fun recursiveFindRpcMethods(current: T, visited: MutableSet<KClass<*>>) {
        AnnotationUtil.findRpcMethods(current!!::class, visited) { methodInfo ->
            val handler =
                Function { args: List<Any?> ->
                    try {
                        val method: KFunction<*> = methodInfo.method
                        val argumentCount = args.size
                        val parameterCount = method.parameters.size
                        val arguments = if (argumentCount == parameterCount) {
                            args
                        } else if (argumentCount < parameterCount){
                            // Take as many as there are and fill the rest with nulls
                            val missing = parameterCount - argumentCount
                            args + List(missing) { null }
                        } else {
                            // Take as many as there are parameters and log a warning for the rest
                            args.take(parameterCount).also {
                                args.drop(parameterCount).forEach {
                                    LOG.warning("Unexpected param '$it' for '$method' is ignored")
                                }
                            }
                        }
                        return@Function method.call(current, *arguments.toTypedArray()) as CompletableFuture<*>?
                    } catch (e: InvocationTargetException) {
                        throw RuntimeException(e)
                    } catch (e: IllegalAccessException) {
                        throw RuntimeException(e)
                    }
                }
            check(
                methodHandlers.put(
                    methodInfo.name,
                    handler
                ) == null
            ) { "Multiple methods for name " + methodInfo.name }
        }
    }

    override fun request(method: String, params: List<Any?>): CompletableFuture<*> {
        // Check the registered method handlers
        val handler = methodHandlers[method]
        if (handler != null) {
            return handler.apply(params)!!
        }

        // Create a log message about the unsupported method
        val message = "Unsupported request method: $method"
        if (isOptionalMethod(method)) {
            LOG.log(Level.INFO, message)
            return CompletableFuture.completedFuture<Any?>(null)
        }
        LOG.log(Level.WARNING, message)
        val exceptionalResult: CompletableFuture<*> = CompletableFuture<Any>()
        val error = ResponseError(ResponseErrorCode.MethodNotFound.value, message, null)
        exceptionalResult.completeExceptionally(ResponseErrorException(error))
        return exceptionalResult
    }

    override fun notify(method: String, parameter: List<Any?>) {
        // Check the registered method handlers
        val handler = methodHandlers[method]
        if (handler != null) {
            handler.apply(parameter)
            return
        }

        // Create a log message about the unsupported method
        val message = "Unsupported notification method: $method"
        if (isOptionalMethod(method)) {
            LOG.log(Level.INFO, message)
        } else {
            LOG.log(Level.WARNING, message)
        }
    }

     private fun isOptionalMethod(method: String?): Boolean {
        return method != null && method.startsWith("$/")
    }

    companion object {
        private val LOG = Logger.getLogger(GenericEndpoint::class.java.name)
    }
}
