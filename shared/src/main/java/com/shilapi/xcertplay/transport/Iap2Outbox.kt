package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Messages for the iPhone from other threads, such as a seek from Android's media controls. An
 * iAP2 control loop sends them between reads, waiting no longer than [POLL_MILLIS] for the
 * iPhone, so they go out promptly. Nothing is taken while no loop runs: a seek asked for between
 * sessions would land on the wrong track.
 */
class Iap2Outbox {
    private val frames = ConcurrentLinkedQueue<Iap2Frame>()
    private val loops = AtomicInteger()

    /** Queues [frame] for the running control loop; false when none runs. */
    fun post(frame: Iap2Frame): Boolean {
        if (loops.get() == 0) return false
        frames.add(frame)
        return true
    }

    /** A control loop starts taking messages. */
    fun open() {
        loops.incrementAndGet()
    }

    /** A control loop has ended; with none left, whatever it didn't send is dropped. */
    fun close() {
        if (loops.decrementAndGet() <= 0) {
            loops.set(0)
            frames.clear()
        }
    }

    fun drain(send: (Iap2Frame) -> Unit) {
        while (true) send(frames.poll() ?: return)
    }

    /** How long a loop may wait for the iPhone before checking for queued messages. */
    fun pollTimeout(timeoutMillis: Long): Long = minOf(timeoutMillis, POLL_MILLIS)

    private companion object {
        const val POLL_MILLIS = 100L
    }
}
