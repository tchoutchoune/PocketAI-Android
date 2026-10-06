package com.pocketai.app

/** Small state machine so programmatic layout changes never look like a user scroll-away. */
internal class StreamingFollowState {
    var following: Boolean = true
        private set

    fun onUserViewport(nearBottom: Boolean) {
        following = nearBottom
    }

    fun onNewMessage() {
        following = true
    }

    fun reset() {
        following = true
    }
}
