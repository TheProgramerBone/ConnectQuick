package dev.connectquick.controller.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Correlates request ids with the coroutine awaiting the PC's answer. */
internal class Pending<T> {
    private val map = ConcurrentHashMap<String, CompletableDeferred<T>>()

    fun newId(): String = UUID.randomUUID().toString()

    fun register(id: String): CompletableDeferred<T> = CompletableDeferred<T>().also { map[id] = it }

    fun complete(id: String, value: T): Boolean = map.remove(id)?.complete(value) ?: false

    /** Completes the request waiting under [id] with [value] (used when the PC answers with a ctl.error). */
    fun completeIfPresent(id: String, value: T): Boolean = complete(id, value)

    fun failAll(error: Throwable) {
        map.values.forEach { it.completeExceptionally(error) }
        map.clear()
    }

    suspend fun await(id: String, deferred: CompletableDeferred<T>, timeoutMs: Long): T = try {
        withTimeout(timeoutMs) { deferred.await() }
    } finally {
        map.remove(id)
    }
}
