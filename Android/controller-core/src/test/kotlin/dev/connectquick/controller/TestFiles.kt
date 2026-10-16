package dev.connectquick.controller

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Loads the shared files in Protocol/test-vectors — never a platform-local copy. */
object TestFiles {
    val repoRoot: File = File(System.getProperty("connectquick.repoRoot") ?: error("connectquick.repoRoot not set"))

    fun vectors(name: String): JsonObject =
        Json.parseToJsonElement(File(repoRoot, "Protocol/test-vectors/$name").readText()).jsonObject
}

fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
