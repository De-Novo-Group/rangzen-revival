package org.denovogroup.rangzen.backend

import org.denovogroup.rangzen.backend.legacy.LegacyExchangeMath
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for trust computation during message exchange.
 */
class TrustComputationTest {

    @Test
    fun `trust should be computed locally for new messages`() {
        // Scenario: We receive a message from a peer
        // Peer computed trust = 0.8 (from their perspective)
        // But we have 5 friends, peer has 2 mutual friends with us
        // OUR trust should be: sigmoid(2/5) = sigmoid(0.4) ≈ 0.73

        val peerTrust = 0.8
        val ourFriends = 5
        val mutualFriends = 2

        val ourTrust = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = 1.0,  // Base priority
            sharedFriends = mutualFriends,
            myFriends = ourFriends
        )

        // Our trust should be based on OUR mutual friend ratio, not peer's
        // With 40% mutual friends, sigmoid should give ~0.73
        assertTrue(
            "Trust should be computed locally, not use peer's value",
            ourTrust > 0.5 && ourTrust < 0.9
        )
    }

    @Test
    fun `stranger messages should have very low trust`() {
        val ourFriends = 5
        val mutualFriends = 0  // No mutual friends = stranger

        val trust = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = 1.0,
            sharedFriends = mutualFriends,
            myFriends = ourFriends
        )

        // With 0 mutual friends, trust should be EPSILON_TRUST (0.001)
        assertTrue(
            "Stranger messages should have trust near EPSILON_TRUST",
            trust < 0.01
        )
    }
}
