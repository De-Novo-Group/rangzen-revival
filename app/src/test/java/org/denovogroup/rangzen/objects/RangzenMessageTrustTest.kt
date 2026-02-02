package org.denovogroup.rangzen.objects

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for message trust score behavior.
 *
 * Per Rangzen paper Section 4.4:
 * - "The local user writes new messages, which are initialized to have priority 1."
 * - Trust is computed via PSI-Ca during exchange, NOT preset.
 */
class RangzenMessageTrustTest {

    @Test
    fun `new message should have default trust, not maximum`() {
        val message = RangzenMessage("test", "anon")
        // Trust should be DEFAULT (0.5), not maximum (1.0)
        // Maximum trust (1.0) would indicate "from direct friend" which is
        // only knowable after PSI-Ca computation
        assertTrue(
            "New message trust should be <= 0.5 (default), not 1.0",
            message.trustScore <= 0.5
        )
    }

    @Test
    fun `new message priority should be 0 or 1 for own messages`() {
        val message = RangzenMessage("test", "anon")
        // Per paper: "initialized to have priority 1"
        // But in our unified model, priority = hearts, which should start at 0
        // and user can upvote to 1 ("equivalent to reauthoring")
        assertTrue(
            "New message priority should be 0 or 1",
            message.priority in 0..1
        )
    }
}
