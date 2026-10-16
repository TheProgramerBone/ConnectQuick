package dev.connectquick.controller.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A deliberately small JSON Schema validator covering exactly the keywords used by
 * `Protocol/schemas/session-messages.schema.json`: type, const, enum, pattern, minimum, maximum,
 * minLength, maxLength, required, properties, additionalProperties:false, items, minItems,
 * maxItems, uniqueItems. A keyword it does not know makes construction fail, so a schema edit can
 * never be silently ignored by this implementation. Same design as the Windows Host's validator;
 * `Protocol/test-vectors/inner-message-vectors.json` proves both agree with the TypeScript one.
 */
class JsonSchemaValidator(private val schema: JsonObject) {
    init {
        assertSupported(schema, "$")
    }

    fun isValid(value: JsonElement): Boolean = check(schema, value)

    private fun assertSupported(s: JsonElement, path: String) {
        if (s !is JsonObject) return
        for ((name, v) in s) {
            if (name in ANNOTATIONS) continue
            require(name in KNOWN) { "schema keyword '$name' at $path is not supported" }
            when {
                name == "properties" -> (v as JsonObject).forEach { (k, sub) -> assertSupported(sub, "$path.properties.$k") }
                name == "items" || (name == "additionalProperties" && v is JsonObject) -> assertSupported(v, "$path.$name")
            }
        }
    }

    private fun check(s: JsonObject, v: JsonElement): Boolean {
        for ((kw, arg) in s) {
            when (kw) {
                "type" -> if (!typeMatches((arg as JsonPrimitive).content, v)) return false
                "const" -> if (!jsonEquals(arg, v)) return false
                "enum" -> if ((arg as JsonArray).none { jsonEquals(it, v) }) return false
                "pattern" -> if (!(v is JsonPrimitive && v.isString && patternMatches((arg as JsonPrimitive).content, v.content))) return false
                "minimum" -> if (isNumber(v) && number(v) < number(arg)) return false
                "maximum" -> if (isNumber(v) && number(v) > number(arg)) return false
                "minLength" -> if (isString(v) && codePoints(v) < (arg as JsonPrimitive).content.toInt()) return false
                "maxLength" -> if (isString(v) && codePoints(v) > (arg as JsonPrimitive).content.toInt()) return false
                "required" -> if (v is JsonObject && (arg as JsonArray).any { (it as JsonPrimitive).content !in v }) return false
                "properties" -> if (v is JsonObject) {
                    for ((name, sub) in arg as JsonObject) {
                        val child = v[name] ?: continue
                        if (!check(sub as JsonObject, child)) return false
                    }
                }
                "additionalProperties" -> if (v is JsonObject && arg is JsonPrimitive && arg.booleanOrNull == false) {
                    val declared = (s["properties"] as? JsonObject)?.keys ?: emptySet()
                    if (v.keys.any { it !in declared }) return false
                }
                "items" -> if (v is JsonArray && v.any { !check(arg as JsonObject, it) }) return false
                "minItems" -> if (v is JsonArray && v.size < (arg as JsonPrimitive).content.toInt()) return false
                "maxItems" -> if (v is JsonArray && v.size > (arg as JsonPrimitive).content.toInt()) return false
                "uniqueItems" -> if (v is JsonArray && (arg as JsonPrimitive).booleanOrNull == true && v.toSet().size != v.size) return false
            }
        }
        return true
    }

    private fun isNumber(v: JsonElement) = v is JsonPrimitive && !v.isString && v.doubleOrNull != null && v.booleanOrNull == null
    private fun number(v: JsonElement) = (v as JsonPrimitive).content.toDouble()
    private fun isString(v: JsonElement) = v is JsonPrimitive && v.isString
    /** JSON Schema measures string length in Unicode code points, not UTF-16 units. */
    private fun codePoints(v: JsonElement): Int = (v as JsonPrimitive).content.let { it.codePointCount(0, it.length) }

    private fun typeMatches(type: String, v: JsonElement): Boolean = when (type) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> isString(v)
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "number" -> isNumber(v)
        "integer" -> isNumber(v) && ((v as JsonPrimitive).longOrNull != null || number(v).let { !it.isInfinite() && Math.floor(it) == it })
        else -> throw UnsupportedOperationException("type '$type'")
    }

    /**
     * ECMAScript's `$` only matches at the very end of the input; Java's also matches before a
     * trailing line terminator. Rewriting a final `$` to `\z` keeps "KeyA\n" invalid here exactly
     * as it is in the TypeScript validator.
     */
    private fun patternMatches(pattern: String, value: String): Boolean {
        val p = if (pattern.endsWith("$") && !pattern.endsWith("\\$")) pattern.dropLast(1) + "\\z" else pattern
        return regexCache.getOrPut(p) { Regex(p) }.containsMatchIn(value)
    }

    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (a.isString != b.isString) return false
            if (a.isString) return a.content == b.content
            val ad = a.doubleOrNull
            val bd = b.doubleOrNull
            if (ad != null && bd != null) return ad == bd
            return a.content == b.content
        }
        return a == b
    }

    private val regexCache = HashMap<String, Regex>()

    companion object {
        private val ANNOTATIONS = setOf("\$comment", "title", "description", "\$schema", "\$id")
        private val KNOWN = setOf(
            "type", "const", "enum", "pattern", "minimum", "maximum", "minLength", "maxLength",
            "required", "properties", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems",
        )
    }
}
