package io.github.alextherajunction.codexweb

internal const val MAX_RECONNECT_DELAY_MILLIS = 30_000L

internal fun reconnectDelayMillis(failedAttempt: Int): Long {
    val exponent = (failedAttempt.coerceAtLeast(1) - 1).coerceAtMost(5)
    return (1_000L shl exponent).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
}
