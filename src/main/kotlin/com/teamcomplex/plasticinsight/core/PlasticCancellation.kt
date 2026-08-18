package com.teamcomplex.plasticinsight.core

import java.util.concurrent.atomic.AtomicBoolean

/** Lightweight cancellation checked while a one-shot Plastic process is alive. */
fun interface PlasticCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE: PlasticCancellation = PlasticCancellation { false }
    }
}

class PlasticCancellationSource {
    private val cancelled = AtomicBoolean()

    val token: PlasticCancellation = PlasticCancellation(cancelled::get)

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)
}
