package com.pocketai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFollowStateTest {
    @Test
    fun followsUntilUserScrollsAwayAndResumesAtBottom() {
        val state = StreamingFollowState()
        assertTrue(state.following)

        state.onUserViewport(false)
        assertFalse(state.following)

        state.onUserViewport(true)
        assertTrue(state.following)
    }

    @Test
    fun newMessageReenablesFollowing() {
        val state = StreamingFollowState()
        state.onUserViewport(false)
        state.onNewMessage()
        assertTrue(state.following)
    }
}
