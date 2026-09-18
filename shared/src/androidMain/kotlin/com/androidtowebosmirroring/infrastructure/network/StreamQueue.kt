package com.androidtowebosmirroring.infrastructure.network

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounds both bytes and objects, including when TS packets are batched into larger chunks. */
internal class StreamQueue(private val byteLimit: Int = 188 * 8192, chunkLimit: Int = 1024) {
    private val queue = ArrayBlockingQueue<ByteArray>(chunkLimit)
    private val reserved = AtomicInteger()
    init { require(byteLimit > 0) }
    val bytes: Int get() = reserved.get()
    fun offer(chunk: ByteArray): Boolean {
        if (chunk.isEmpty() || chunk.size > byteLimit) return false
        while (true) {
            val size = reserved.get()
            if (chunk.size > byteLimit - size) return false
            if (reserved.compareAndSet(size, size + chunk.size)) break
        }
        if (queue.offer(chunk)) return true
        reserved.addAndGet(-chunk.size)
        return false
    }
    fun poll(timeout: Long, unit: TimeUnit): ByteArray? = queue.poll(timeout, unit)?.also { reserved.addAndGet(-it.size) }
}
