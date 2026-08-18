package com.teamcomplex.plasticinsight.core

/** Signals malformed machine-readable Plastic output. */
class PlasticParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
