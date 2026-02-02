package org.denovogroup.rangzen.ui

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests to verify anonymity properties of the feed.
 *
 * Per the Rangzen paper: "No authorship information is stored in messages."
 * The user should NOT be able to identify which messages they authored.
 */
class FeedAnonymityTest {

    @Test
    fun `FeedFragment should not have myPseudonym field`() {
        // Use reflection to verify the field doesn't exist
        val feedFragmentClass = FeedFragment::class.java
        val fields = feedFragmentClass.declaredFields.map { it.name }
        assertFalse(
            "FeedFragment should not have myPseudonym field - this enables self-identification",
            fields.contains("myPseudonym")
        )
    }

    @Test
    fun `FeedFragment should not have hideMine field`() {
        val feedFragmentClass = FeedFragment::class.java
        val fields = feedFragmentClass.declaredFields.map { it.name }
        assertFalse(
            "FeedFragment should not have hideMine field - this enables self-identification",
            fields.contains("hideMine")
        )
    }
}
