package dev.myair.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.concurrent.Executor

/**
 * Reads on [executor] what [read] says of a key, and hands it to [land] through [edt], but only for the key asked for
 * last: a read overtaken by another [request], or by [forget], never lands. Called on the EDT only.
 */
class LatestRead<K : Any, V>(
    private val executor: Executor, private val edt: (Runnable) -> Unit, private val read: (K) -> V, private val land: (K, Result<V>) -> Unit,
) {
    /** The key of the read that may still land, or that landed last; null after [forget]. */
    var asked: K? = null
        private set
    private var reads = 0

    fun request(key: K) {
        asked = key
        val ticket = ++reads
        executor.execute {
            val value = runCatching { read(key) }
            edt { if (ticket == reads) land(key, value) }
        }
    }

    /** Drops the read under way, if any: nothing lands until the next [request]. */
    fun forget() {
        asked = null
        reads++
    }

    companion object {
        /** A [LatestRead] on [executor] landing in [modality], asked for when a read finishes: a dialog has its own only once shown. */
        fun <K : Any, V> onPool(executor: Executor, modality: () -> ModalityState, read: (K) -> V, land: (K, Result<V>) -> Unit) =
            LatestRead(executor, { ApplicationManager.getApplication().invokeLater(it, modality()) }, read, land)
    }
}
