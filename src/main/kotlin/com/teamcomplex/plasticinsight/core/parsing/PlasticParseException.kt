package com.teamcomplex.plasticinsight.core.parsing

/** Signals malformed machine-readable Plastic output. */
class PlasticParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
