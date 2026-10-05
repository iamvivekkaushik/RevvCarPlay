package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2OutboxTest {
    private val outbox = Iap2Outbox()
    private val sent = mutableListOf<Iap2Frame>()

    @Test
    fun nothingIsTakenWithoutAControlLoop() {
        assertFalse(outbox.post(Iap2ControlMessages.setNowPlayingElapsedTime(1_000)))
        outbox.open()
        outbox.drain { sent += it }
        assertEquals(emptyList<Iap2Frame>(), sent)
    }

    @Test
    fun aRunningLoopSendsWhatWasQueuedInOrder() {
        outbox.open()
        assertTrue(outbox.post(Iap2ControlMessages.setNowPlayingElapsedTime(1_000)))
        assertTrue(outbox.post(Iap2ControlMessages.setNowPlayingElapsedTime(2_000)))
        outbox.drain { sent += it }
        assertEquals(2, sent.size)
        outbox.drain { sent += it }
        assertEquals(2, sent.size)
    }

    @Test
    fun theLastLoopEndingDropsWhatItDidNotSend() {
        outbox.open()
        outbox.open()
        outbox.post(Iap2ControlMessages.setNowPlayingElapsedTime(1_000))
        outbox.close()
        // Another loop still runs: it sends the seek.
        outbox.drain { sent += it }
        assertEquals(1, sent.size)
        outbox.post(Iap2ControlMessages.setNowPlayingElapsedTime(2_000))
        outbox.close()
        outbox.open()
        outbox.drain { sent += it }
        assertEquals(1, sent.size)
    }

    @Test
    fun loopsCheckForQueuedMessagesOftenButNeverLaterThanAsked() {
        assertEquals(100L, outbox.pollTimeout(60_000))
        assertEquals(40L, outbox.pollTimeout(40))
    }
}
