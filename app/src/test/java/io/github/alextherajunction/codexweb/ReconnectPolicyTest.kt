package io.github.alextherajunction.codexweb

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun `backs off exponentially and caps retry delay`() {
        assertEquals(1_000L, reconnectDelayMillis(1))
        assertEquals(2_000L, reconnectDelayMillis(2))
        assertEquals(4_000L, reconnectDelayMillis(3))
        assertEquals(16_000L, reconnectDelayMillis(5))
        assertEquals(MAX_RECONNECT_DELAY_MILLIS, reconnectDelayMillis(6))
        assertEquals(MAX_RECONNECT_DELAY_MILLIS, reconnectDelayMillis(100))
    }
}
