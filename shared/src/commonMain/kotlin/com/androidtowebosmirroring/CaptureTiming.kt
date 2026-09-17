package com.androidtowebosmirroring

data class VideoViewport(val x: Int, val y: Int, val width: Int, val height: Int)

fun videoSideInset(sourceWidth: Int, sourceHeight: Int, videoMode: Boolean): Float {
    require(sourceWidth > 0 && sourceHeight > 0)
    if (!videoMode || sourceWidth.toDouble() / sourceHeight <= 16.0 / 9.0) return 0f
    return ((1.0 - sourceHeight * (16.0 / 9.0) / sourceWidth) / 2.0).toFloat()
}

fun fitViewport(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): VideoViewport {
    require(sourceWidth > 0 && sourceHeight > 0 && outputWidth > 0 && outputHeight > 0)
    val scale = minOf(outputWidth.toDouble() / sourceWidth, outputHeight.toDouble() / sourceHeight)
    val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
    return VideoViewport((outputWidth - width) / 2, (outputHeight - height) / 2, width, height)
}

class AudioCaptureClock(private val startUs: Long, private val sampleRate: Int = 48000) {
    private var offsetUs = startUs
    private var lastPtsUs = Long.MIN_VALUE
    private var anchored = false
    fun presentationTime(frame: Long, timestampFrame: Long? = null, timestampUs: Long? = null): Long {
        if (timestampFrame != null && timestampUs != null) {
            val measuredOffset = timestampUs - timestampFrame * 1_000_000 / sampleRate
            // Once established, follow hardware drift gradually, never jump playback backwards.
            offsetUs = if (!anchored) measuredOffset else offsetUs + (measuredOffset - offsetUs).coerceIn(-200, 200)
            anchored = true
        }
        val pts = maxOf(offsetUs + frame * 1_000_000 / sampleRate, if (lastPtsUs == Long.MIN_VALUE) startUs else lastPtsUs + 1)
        lastPtsUs = pts
        return pts
    }
}

class FrameCadence(private val intervalNs: Long = 1_000_000_000L / 30) {
    init { require(intervalNs > 0) }
    private var nextNs: Long? = null
    fun frameTime(nowNs: Long): Long? {
        val next = nextNs ?: nowNs
        if (nowNs < next) return null
        // Keep the original phase despite scheduler jitter. Skip all missed slots in one step.
        val slot = next + ((nowNs - next) / intervalNs) * intervalNs
        nextNs = slot + intervalNs
        return slot
    }
    fun due(nowNs: Long): Boolean = frameTime(nowNs) != null
}
