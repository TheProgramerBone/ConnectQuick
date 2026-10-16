package dev.connectquick.controller.stream

import dev.connectquick.controller.net.BackendClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Asks the backend for ICE servers (`ice.request` / `ice.config`) and caches the answer only for as
 * long as the TURN credential is valid. If the backend cannot be reached the caller gets
 * [IceConfig.NONE] — direct paths only — instead of an exception, so a stream attempt still
 * works on a LAN and the UI can explain the limitation.
 */
class IceConfigProvider(private val client: BackendClient) {
    private val lock = Any()
    @Volatile private var cached: IceConfig? = null
    @Volatile private var cachedUntilMs = 0L
    @Volatile private var waiting: CompletableDeferred<IceConfig>? = null

    init {
        client.on("ice.config") { p ->
            val servers = p.getValue("iceServers").jsonArray.map {
                val o = it.jsonObject
                IceServer(
                    o.getValue("urls").jsonArray.map { u -> u.jsonPrimitive.content },
                    o["username"]?.jsonPrimitive?.content,
                    o["credential"]?.jsonPrimitive?.content,
                )
            }
            val ttl = p.getValue("ttlSeconds").jsonPrimitive.intOrNull ?: 0
            val cfg = IceConfig(
                servers,
                p.getValue("stun").jsonPrimitive.booleanOrNull == true,
                p.getValue("turn").jsonPrimitive.booleanOrNull == true,
                ttl,
            )
            synchronized(lock) {
                cached = cfg
                cachedUntilMs = System.currentTimeMillis() + if (ttl > 0) maxOf(30, ttl - 60) * 1000L else 10 * 60_000L
                waiting?.complete(cfg)
            }
        }
    }

    suspend fun get(timeoutMs: Long = 5_000): IceConfig {
        cached?.let { if (System.currentTimeMillis() < cachedUntilMs) return it }
        val d = synchronized(lock) { waiting ?: CompletableDeferred<IceConfig>().also { waiting = it } }
        return try {
            if (!client.trySend("ice.request", JsonObject(emptyMap()))) return IceConfig.NONE
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            IceConfig.NONE
        } finally {
            synchronized(lock) { if (waiting === d) waiting = null }
        }
    }
}
