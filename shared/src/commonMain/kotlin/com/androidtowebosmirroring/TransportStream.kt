package com.androidtowebosmirroring

/** Live MPEG-TS muxer: H.264 Annex B, AAC ADTS, 90 kHz timestamps. */
class TransportStream(private val audio: Boolean, private val emit: (ByteArray) -> Unit, private val packetsPerBatch: Int = 1) {
    init { require(packetsPerBatch in 1..64) }
    private val counters = IntArray(8192)
    fun tables() {
        section(0, byteArrayOf(0, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0))
        val streams = if (audio) byteArrayOf(0x1B, 0xE1.toByte(), 0, 0xF0.toByte(), 0, 0x0F, 0xE1.toByte(), 1, 0xF0.toByte(), 0)
                      else byteArrayOf(0x1B, 0xE1.toByte(), 0, 0xF0.toByte(), 0)
        section(4096, byteArrayOf(2, 0xB0.toByte(), (13 + streams.size).toByte(), 0, 1, 0xC1.toByte(), 0, 0,
            0xE1.toByte(), 0, 0xF0.toByte(), 0) + streams)
    }
    private fun section(pid: Int, bytes: ByteArray) {
        var crc = -1
        for (b in bytes) { crc = crc xor ((b.toInt() and 255) shl 24); repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 } }
        packetize(pid, byteArrayOf(0) + bytes + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()), null)
    }
    fun video(bytes: ByteArray, ptsUs: Long, clockUs: Long = ptsUs) = pes(256, 0xE0, bytes, ptsUs, clockUs)
    fun audio(bytes: ByteArray, ptsUs: Long) {
        val size = bytes.size + 7
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, (0x80 or (size shr 11)).toByte(),
            (size shr 3).toByte(), (((size and 7) shl 5) or 31).toByte(), 0xFC.toByte())
        pes(257, 0xC0, adts + bytes, ptsUs, null)
    }
    private fun pes(pid: Int, stream: Int, bytes: ByteArray, us: Long, clockUs: Long?) {
        val pts = (us.coerceAtLeast(0) * 90 / 1000) and 0x1FFFFFFFFL
        val length = if (clockUs != null) 0 else bytes.size + 8
        val header = byteArrayOf(0, 0, 1, stream.toByte(), (length shr 8).toByte(), length.toByte(), 0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or (((pts shr 30).toInt() and 7) shl 1)).toByte(), (pts shr 22).toByte(),
            ((((pts shr 15).toInt() and 127) shl 1) or 1).toByte(), (pts shr 7).toByte(), (((pts.toInt() and 127) shl 1) or 1).toByte())
        packetize(pid, header + bytes, clockUs?.let { (it.coerceAtLeast(0) * 90 / 1000) and 0x1FFFFFFFFL })
    }
    private fun packetize(pid: Int, data: ByteArray, clock: Long?) {
        var offset = 0
        var batch = byteArrayOf()
        var batchOffset = 0
        while (offset < data.size) {
            val first = offset == 0
            val pcr = first && clock != null
            if (batchOffset == batch.size) {
                // Account for the PCR adaptation bytes in the first packet. Every emitted
                // batch contains complete TS packets; no media bytes or timestamps change.
                val packetsLeft = (data.size - offset + (if (pcr) 8 else 0) + 183) / 184
                batch = ByteArray(minOf(packetsPerBatch, packetsLeft) * 188) { 0xFF.toByte() }
                batchOffset = 0
            }
            val count = minOf(data.size - offset, if (pcr) 176 else 184)
            val adaptation = 184 - count
            val packet = batch
            val base = batchOffset
            val cc = counters[pid]; counters[pid] = (cc + 1) and 15
            packet[base] = 0x47; packet[base + 1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            packet[base + 2] = pid.toByte(); packet[base + 3] = ((if (adaptation > 0) 0x30 else 0x10) or cc).toByte()
            if (adaptation > 0) {
                packet[base + 4] = (adaptation - 1).toByte()
                if (adaptation > 1) packet[base + 5] = if (pcr) 0x10 else 0
                if (pcr) { val c = clock; packet[base + 6] = (c shr 25).toByte(); packet[base + 7] = (c shr 17).toByte()
                    packet[base + 8] = (c shr 9).toByte(); packet[base + 9] = (c shr 1).toByte(); packet[base + 10] = (((c and 1).toInt() shl 7) or 0x7E).toByte(); packet[base + 11] = 0 }
            }
            data.copyInto(packet, base + 4 + adaptation, offset, offset + count)
            offset += count; batchOffset += 188
            if (batchOffset == batch.size) emit(batch)
        }
    }
}
