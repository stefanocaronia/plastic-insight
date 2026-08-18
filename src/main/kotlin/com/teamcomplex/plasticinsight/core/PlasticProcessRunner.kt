package com.teamcomplex.plasticinsight.core

fun interface PlasticProcessRunner {
    fun run(invocation: PlasticInvocation): PlasticProcessResult

    /** Implementations supporting active cancellation should override this overload. */
    fun run(
        invocation: PlasticInvocation,
        cancellation: PlasticCancellation,
    ): PlasticProcessResult =
        if (cancellation.isCancellationRequested()) {
            PlasticProcessResult.cancelled()
        } else {
            run(invocation)
        }
}
