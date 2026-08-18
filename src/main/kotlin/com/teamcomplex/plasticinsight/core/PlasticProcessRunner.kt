package com.teamcomplex.plasticinsight.core

fun interface PlasticProcessRunner {
    fun run(invocation: PlasticInvocation): PlasticProcessResult
}
