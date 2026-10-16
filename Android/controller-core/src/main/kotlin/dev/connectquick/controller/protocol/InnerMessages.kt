package dev.connectquick.controller.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

enum class InnerRejection { NONE, TOO_LARGE, MALFORMED_JSON, NOT_AN_OBJECT, MISSING_TYPE, UNKNOWN_TYPE, SCHEMA }

class InnerParseResult(val rejection: InnerRejection, val type: String? = null, val message: JsonObject? = null) {
    val ok: Boolean get() = rejection == InnerRejection.NONE
}

/**
 * Validates decrypted end-to-end messages against the shared schema. Every inbound inner message
 * goes through [parse] before anything acts on it; every outbound one goes through [serialize]
 * so an oversized document is caught before it is encrypted and sent.
 */
object InnerMessages {
    const val MAX_INNER_BYTES = 40_000
    private const val MAX_DEPTH = 16

    private val validators: Map<String, JsonSchemaValidator> by lazy {
        val stream = InnerMessages::class.java.getResourceAsStream("/session-messages.schema.json")
            ?: error("session-messages.schema.json is missing from the classpath")
        val root = Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
        root.getValue("\$defs").jsonObject.entries.associate { (name, def) ->
            // "input_mouse_move" -> "input.mouse_move": only the FIRST underscore stands for the dot.
            val i = name.indexOf('_')
            (name.substring(0, i) + "." + name.substring(i + 1)) to JsonSchemaValidator(def.jsonObject)
        }
    }

    val knownTypes: Set<String> get() = validators.keys

    /** Nesting depth of the document, scanning outside string literals. Guards recursive parsers. */
    private fun depthExceeds(text: String, limit: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > limit) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    fun parse(utf8: ByteArray): InnerParseResult {
        if (utf8.size > MAX_INNER_BYTES) return InnerParseResult(InnerRejection.TOO_LARGE)
        val text = try {
            // Strict decoding: invalid UTF-8 is malformed input, not something to repair silently.
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(utf8)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return InnerParseResult(InnerRejection.MALFORMED_JSON)
        }
        if (depthExceeds(text, MAX_DEPTH)) return InnerParseResult(InnerRejection.MALFORMED_JSON)
        val root: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return InnerParseResult(InnerRejection.MALFORMED_JSON)
        }
        if (root !is JsonObject) return InnerParseResult(InnerRejection.NOT_AN_OBJECT)
        val t = root["t"]
        if (t !is JsonPrimitive || !t.isString) return InnerParseResult(InnerRejection.MISSING_TYPE)
        val validator = validators[t.content] ?: return InnerParseResult(InnerRejection.UNKNOWN_TYPE, t.content)
        return if (validator.isValid(root)) InnerParseResult(InnerRejection.NONE, t.content, root)
        else InnerParseResult(InnerRejection.SCHEMA, t.content)
    }

    fun parse(json: String): InnerParseResult = parse(json.toByteArray(Charsets.UTF_8))

    /** Builds `{"t": type, ...fields}`; fields set to null are omitted. Throws if the result exceeds the size limit. */
    fun serialize(type: String, fields: JsonObjectBuilder.() -> Unit = {}): ByteArray {
        val obj = buildJsonObject {
            put("t", JsonPrimitive(type))
            fields()
        }
        val clean = JsonObject(obj.filterValues { it !is JsonNull })
        val bytes = clean.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_INNER_BYTES) { "$type is ${bytes.size} bytes, over the $MAX_INNER_BYTES limit" }
        return bytes
    }
}
