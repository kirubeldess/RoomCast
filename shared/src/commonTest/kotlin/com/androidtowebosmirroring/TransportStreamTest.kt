package com.androidtowebosmirroring

import com.androidtowebosmirroring.domain.media.TransportStream

import kotlin.test.*

class TransportStreamTest {
    @Test fun batchingPreservesTheExactStreamAndReducesCallbackCount() {
        for (size in listOf(1, 162, 163, 184, 5800, 5888, 6000, 65536)) {
            val individual = mutableListOf<ByteArray>()
            val batched = mutableListOf<ByteArray>()
            fun produce(mux: TransportStream) {
                mux.tables()
                mux.video(ByteArray(size) { (it % 251).toByte() }, 2_500_000, 1_000_000)
                mux.audio(ByteArray(400) { it.toByte() }, 2_500_000)
                mux.video(byteArrayOf(0, 0, 1, 0x65), 2_533_333, 1_033_333)
            }
            produce(TransportStream(true, individual::add))
            produce(TransportStream(true, batched::add, packetsPerBatch = 32))
            assertContentEquals(individual.fold(byteArrayOf()) { all, bytes -> all + bytes },
                batched.fold(byteArrayOf()) { all, bytes -> all + bytes })
            assertTrue(batched.all { it.isNotEmpty() && it.size % 188 == 0 && it.size <= 32 * 188 })
            if (size == 65536) assertTrue(batched.size * 10 < individual.size)
        }
    }
    private fun pid(p: ByteArray) = ((p[1].toInt() and 31) shl 8) or (p[2].toInt() and 255)
    private fun payload(p: ByteArray): ByteArray {
        val offset = if (p[3].toInt() and 0x20 != 0) 5 + (p[4].toInt() and 255) else 4
        return p.copyOfRange(offset, 188)
    }
    @Test fun packetsPreserveLargeVideoPayloadAndContinuity() {
        val packets = mutableListOf<ByteArray>()
        val mux = TransportStream(false, packets::add)
        val bytes = ByteArray(10000) { (it % 251).toByte() }
        mux.video(bytes, 1_000_000)
        packets.forEachIndexed { index, p ->
            assertEquals(188, p.size); assertEquals(0x47, p[0].toInt()); assertEquals(256, pid(p))
            assertEquals(index % 16, p[3].toInt() and 15)
            assertEquals(index == 0, p[1].toInt() and 0x40 != 0)
        }
        val pes = packets.fold(byteArrayOf()) { all, p -> all + payload(p) }
        assertContentEquals(bytes, pes.copyOfRange(14, pes.size))
        assertEquals(0x10, packets.first()[5].toInt()) // PCR flag
        val p = packets.first()
        val clock = ((p[6].toLong() and 255) shl 25) or ((p[7].toLong() and 255) shl 17) or
            ((p[8].toLong() and 255) shl 9) or ((p[9].toLong() and 255) shl 1) or ((p[10].toLong() and 128) shr 7)
        assertEquals(90000L, clock)
        val pts = ((pes[9].toLong() and 14) shl 29) or ((pes[10].toLong() and 255) shl 22) or
            ((pes[11].toLong() and 254) shl 14) or ((pes[12].toLong() and 255) shl 7) or ((pes[13].toLong() and 254) shr 1)
        assertEquals(clock, pts)
    }
    @Test fun programTablesHaveValidCrcAndAudioDeclaration() {
        for (audio in listOf(false, true)) {
            val packets = mutableListOf<ByteArray>()
            TransportStream(audio, packets::add).tables()
            assertEquals(listOf(0, 4096), packets.map(::pid))
            packets.forEach { p ->
                val section = payload(p).drop(1).toByteArray()
                assertEquals(section.size - 3, section[2].toInt() and 255)
                var crc = -1
                section.forEach { b -> crc = crc xor ((b.toInt() and 255) shl 24); repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04C11DB7 else 0 } }
                assertEquals(0, crc)
            }
            assertEquals(if (audio) 27 else 22, payload(packets[1]).size)
        }
    }
    @Test fun aacHasAdtsHeaderAndIntactPayload() {
        val packets = mutableListOf<ByteArray>()
        val bytes = ByteArray(250) { it.toByte() }
        TransportStream(true, packets::add).audio(bytes, 0)
        assertTrue(packets.all { pid(it) == 257 })
        val pes = packets.fold(byteArrayOf()) { all, p -> all + payload(p) }
        assertEquals(0xFF, pes[14].toInt() and 255)
        assertEquals(0x4C, pes[16].toInt() and 255)
        val length = ((pes[17].toInt() and 3) shl 11) or ((pes[18].toInt() and 255) shl 3) or ((pes[19].toInt() and 224) shr 5)
        assertEquals(bytes.size + 7, length)
        assertContentEquals(bytes, pes.copyOfRange(21, pes.size))
    }
    @Test fun exactAndTinyPacketBoundariesKeepPayload() {
        for (size in listOf(1, 161, 162, 163, 345, 346, 347)) {
            val packets = mutableListOf<ByteArray>()
            val bytes = ByteArray(size) { 42 }
            TransportStream(false, packets::add).video(bytes, 0)
            val pes = packets.fold(byteArrayOf()) { all, p -> all + payload(p) }
            assertContentEquals(bytes, pes.copyOfRange(14, pes.size))
        }
    }
    @Test fun presentationTimeLeavesDecodingMarginAfterProgramClock() {
        val packets = mutableListOf<ByteArray>()
        TransportStream(true, packets::add).video(byteArrayOf(0, 0, 1, 0x65), 1_700_000, 1_000_000)
        val packet = packets.first()
        val clock = ((packet[6].toLong() and 255) shl 25) or ((packet[7].toLong() and 255) shl 17) or
            ((packet[8].toLong() and 255) shl 9) or ((packet[9].toLong() and 255) shl 1) or ((packet[10].toLong() and 128) shr 7)
        val pes = payload(packet)
        val pts = ((pes[9].toLong() and 14) shl 29) or ((pes[10].toLong() and 255) shl 22) or
            ((pes[11].toLong() and 254) shl 14) or ((pes[12].toLong() and 255) shl 7) or ((pes[13].toLong() and 254) shr 1)
        assertEquals(63_000L, pts - clock)
    }
}
