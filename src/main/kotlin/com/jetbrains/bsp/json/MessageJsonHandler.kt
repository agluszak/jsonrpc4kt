package com.jetbrains.bsp.json

import com.jetbrains.bsp.MessageIssueException
import com.jetbrains.bsp.json.serializers.WrappingListSerializer
import com.jetbrains.bsp.messages.CancelParams
import com.jetbrains.bsp.messages.JsonParams
import com.jetbrains.bsp.messages.Message
import com.jetbrains.bsp.messages.MessageIssue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf


class MessageJsonHandler(val json: Json, val supportedMethods: Map<String, JsonRpcMethod>) {
    var methodProvider: MethodProvider? = null

    /**
     * Resolve an RPC method by name.
     */
    fun getJsonRpcMethod(name: String): JsonRpcMethod? {
        val result: JsonRpcMethod? = supportedMethods[name]
        if (result != null) return result else if (CANCEL_METHOD.methodName == name) return CANCEL_METHOD
        return null
    }

    fun serializeResult(method: String, result: Any?): JsonElement {
        val jsonRpcMethod = getJsonRpcMethod(method) ?: throw IllegalArgumentException("Unknown method: $method")
        val resultType = jsonRpcMethod.resultType
        return json.encodeToJsonElement(json.serializersModule.serializer(resultType), result)
    }

    fun deserializeResult(method: String, result: JsonElement): Any? {
        val jsonRpcMethod = getJsonRpcMethod(method) ?: throw IllegalArgumentException("Unknown method: $method")
        val resultType = jsonRpcMethod.resultType
        return json.decodeFromJsonElement(json.serializersModule.serializer(resultType), result)
    }

    fun serializeParams(method: String, params: List<Any?>): JsonParams {
        val jsonRpcMethod = getJsonRpcMethod(method) ?: throw IllegalArgumentException("Unknown method: $method")
        if (params.size != jsonRpcMethod.parameterTypes.size) {
            throw IllegalArgumentException(
                "Wrong number of parameters for method $method: expected ${jsonRpcMethod.parameterTypes.size}, got ${params.size}"
            )
        }
        return when (params.size) {
            0 -> JsonParams.ObjectParams(buildJsonObject { })
            1 -> {
                when (val jsonElement = json.encodeToJsonElement(json.serializersModule.serializer(jsonRpcMethod.parameterTypes[0]), params[0])) {
                    is JsonObject -> JsonParams.ObjectParams(jsonElement)
                    else -> JsonParams.ArrayParams(JsonArray(listOf(jsonElement)))
                }
            }

            else -> {
                // TODO use correct serializers for each parameter
                val jsonElement = json.encodeToJsonElement(json.serializersModule.serializer(), params)
                JsonParams.ArrayParams(jsonElement.jsonArray)
            }
        }
    }

    fun deserializeParams(method: String, params: JsonParams?): List<Any?> {
        val size = params?.size ?: 0
        val jsonRpcMethod = getJsonRpcMethod(method) ?: throw IllegalArgumentException("Unknown method: $method")

        return when (params) {
            null -> emptyList()
            is JsonParams.ObjectParams -> {
                val jsonObject = params.params
                if (jsonObject.isEmpty()) {
                    listOf(null)
                } else {
                    val type = jsonRpcMethod.parameterTypes[0]
                    val result = json.decodeFromJsonElement(json.serializersModule.serializer(type), jsonObject)
                    listOf(result)
                }
            }

            is JsonParams.ArrayParams -> {
                // If the method has a single parameter of type List, we deserialize the whole array as that parameter
                if (jsonRpcMethod.parameterTypes.size == 1 && jsonRpcMethod.parameterTypes[0].isSubtypeOf(typeOf<List<*>>())) {
                    val elementType = json.serializersModule.serializer(jsonRpcMethod.parameterTypes[0])
                    println("elementType: $elementType")
                    val serializer = ListSerializer(elementType)
                    return json.decodeFromJsonElement(serializer, params.params)
                }
                // Otherwise, we treat the array as a list of parameters and add nulls if the array is too short
                val jsonArray = if (size < jsonRpcMethod.parameterTypes.size) {
                    params.params + List(jsonRpcMethod.parameterTypes.size - size) { JsonNull }
                } else {
                    params.params
                }
                jsonArray.zip(jsonRpcMethod.parameterTypes).map { (jsonElement, type) ->
                    json.decodeFromJsonElement(json.serializersModule.serializer(type), jsonElement)
                }
            }
        }
    }

    fun parseMessage(input: String): Message {
        return try {
            json.decodeFromString(input)
        } catch (e: SerializationException) {
            throw MessageIssueException(null, listOf(MessageIssue("Failed to parse message", cause = e)))
        }
    }

    fun serialize(message: Message): String {
        return try {
            json.encodeToString(message)
        } catch (e: SerializationException) {
            throw MessageIssueException(null, listOf(MessageIssue("Failed to serialize message", cause = e)))
        }
    }


    inline fun <reified T> serialize(value: T): JsonElement =
        json.encodeToJsonElement(json.serializersModule.serializer(), value)


    companion object {
        val CANCEL_METHOD: JsonRpcMethod = JsonRpcMethod.notification("$/cancelRequest", typeOf<CancelParams>())
//        private var toStringInstance: MessageJsonHandler? = null
//
//        /**
//         * Perform JSON serialization of the given object using the default configuration of JSON-RPC messages
//         * enhanced with the pretty printing option.
//         */
//        fun toString(`object`: Any?): String {
//            if (toStringInstance == null) {
//                toStringInstance = MessageJsonHandler(
//                    emptyMap<String, JsonRpcMethod>(),
//                    Consumer<GsonBuilder> { gsonBuilder: GsonBuilder -> gsonBuilder.setPrettyPrinting() })
//            }
//            return toStringInstance!!.gson.toJson(`object`)
//        }
    }
}
