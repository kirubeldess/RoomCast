package com.androidtowebosmirroring

import com.androidtowebosmirroring.infrastructure.network.StreamQueue

import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.test.*

class StreamQueueTest {
    @Test fun byteBudgetAppliesToVariableChunksAndIsReturnedOnRead() {
        val queue = StreamQueue(byteLimit = 100, chunkLimit = 10)
        assertTrue(queue.offer(ByteArray(80)))
        assertFalse(queue.offer(ByteArray(21)))
        assertTrue(queue.offer(ByteArray(20)))
        assertEquals(100, queue.bytes)
        assertEquals(80, queue.poll(0, TimeUnit.MILLISECONDS)!!.size)
        assertEquals(20, queue.bytes)
        assertTrue(queue.offer(ByteArray(80)))
        assertEquals(20, queue.poll(0, TimeUnit.MILLISECONDS)!!.size)
        assertEquals(80, queue.poll(0, TimeUnit.MILLISECONDS)!!.size)
        assertEquals(0, queue.bytes)
    }
    @Test fun rejectedQueueOfferDoesNotLeakItsByteReservation() {
        val queue = StreamQueue(byteLimit = 100, chunkLimit = 1)
        assertTrue(queue.offer(ByteArray(20)))
        assertFalse(queue.offer(ByteArray(20)))
        assertEquals(20, queue.bytes)
        queue.poll(0, TimeUnit.MILLISECONDS)
        assertEquals(0, queue.bytes)
        assertFalse(queue.offer(ByteArray(101)))
        assertFalse(queue.offer(byteArrayOf()))
        assertEquals(0, queue.bytes)
    }
    @Test fun concurrentProducerAndConsumerPreserveOrderAndReleaseBudget() {
        val queue = StreamQueue(byteLimit = 1024, chunkLimit = 32)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val producer = worker.submit {
                for (i in 0 until 1000) {
                    val chunk = ByteArray(64) { (i % 251).toByte() }
                    while (!queue.offer(chunk)) {
                        check(!Thread.currentThread().isInterrupted)
                        Thread.yield()
                    }
                    check(queue.bytes in 0..1024)
                }
            }
            repeat(1000) { i ->
                val chunk = assertNotNull(queue.poll(5, TimeUnit.SECONDS))
                assertEquals((i % 251).toByte(), chunk[0])
                assertTrue(queue.bytes in 0..1024)
            }
            producer.get(5, TimeUnit.SECONDS)
            assertEquals(0, queue.bytes)
        } finally { worker.shutdownNow() }
    }
}
