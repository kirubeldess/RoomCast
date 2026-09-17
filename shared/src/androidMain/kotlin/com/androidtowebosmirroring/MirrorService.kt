package com.androidtowebosmirroring

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object MirrorSession {
    var state by mutableStateOf(MirrorState())
        private set
    private val main = Handler(Looper.getMainLooper())
    fun update(change: (MirrorState) -> MirrorState) { main.post { state = change(state) } }
}

class MirrorService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()
    private val renderWorker = Executors.newSingleThreadExecutor()
    private var renderTask: java.util.concurrent.Future<*>? = null
    private val resizeRequest = java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)
    @Volatile private var controlRequested = false
    private val stopped = AtomicBoolean(false)
    private var started = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var renderer: ScreenRenderer? = null
    @Volatile private var capturedSize: Pair<Int, Int>? = null
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var server: LiveServer? = null
    private var receiver: Receiver? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSession() }
        override fun onCapturedContentResize(width: Int, height: Int) { if (width > 0 && height > 0) capturedSize = width to height }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSession(); if (!started) stopSelf(); return START_NOT_STICKY }
        if (started || intent == null) { if (!started) stopSelf(); return START_NOT_STICKY }
        started = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mirroring", "Screen mirroring", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, MirrorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "mirroring").setContentTitle("Roomcast is sharing your screen")
            .setContentText("Tap Stop to end screen and audio sharing.").setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            MirrorSession.update { it.copy(active = false, message = "Cannot start screen capture: ${e.message}") }; stopSelf(); return START_NOT_STICKY
        }
        MirrorSession.update { it.copy(active = true, message = "Preparing screen capture…", diagnostics = "") }
        worker.execute {
            var failure: String? = null
            try { capture(intent) } catch (e: Exception) {
                android.util.Log.e("Roomcast", "Capture session ended", e)
                val reason = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                if (!stopped.get()) failure = "Mirroring failed: ${reason.message}"
            }
            finally {
                stopped.set(true)
                cleanup()
                MirrorSession.update { it.copy(active = false, message = failure ?: "Mirroring stopped. Screen and audio capture are off.") }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION", "MissingPermission")
    private fun capture(intent: Intent) {
        val tv = Receiver(intent.getStringExtra("id") ?: error("No TV selected"), intent.getStringExtra("name").orEmpty(), "DLNA",
            intent.getStringExtra("url") ?: error("No TV address"), intent.getStringExtra("type") ?: error("No TV service"))
        receiver = tv
        val withAudio = intent.getBooleanExtra("audio", true)
        check(!withAudio || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Allow audio permission or turn Internal audio off" }
        val consent = intent.getParcelableExtra<Intent>("consent") ?: error("Screen capture approval missing")
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
        val projection = projection ?: error("Screen capture approval expired")
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        val metrics = android.util.DisplayMetrics()
        val physicalDisplay = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        physicalDisplay.getRealMetrics(metrics)
        val shortSide = intent.getIntExtra("quality", 720).coerceIn(480, 1080)
        val height = minOf(shortSide, minOf(metrics.widthPixels, metrics.heightPixels)) / 2 * 2
        val width = (height * 16 / 9) / 2 * 2
        var captureWidth = metrics.widthPixels
        var captureHeight = metrics.heightPixels
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, when (shortSide) { 480 -> 1_500_000; 1080 -> 5_000_000; else -> 3_000_000 })
            setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val encoder = video!!
        val supportsCbr = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        if (supportsCbr) videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder.createInputSurface(); encoder.start()
        renderer = ScreenRenderer(intent.getBooleanExtra("videoMode", false))
        renderWorker.submit {
            try { renderer!!.initialize(surface!!, width, height, captureWidth, captureHeight) }
            catch (e: Exception) { renderer!!.close(); renderer = null; throw e }
        }.get()
        if (withAudio) {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val config = AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val minBuffer = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "48 kHz stereo capture is unavailable" }
            recorder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuffer * 2, 16384)).setAudioPlaybackCaptureConfig(config).build()
            check(recorder!!.state == AudioRecord.STATE_INITIALIZED) { "Internal audio could not initialize" }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audio!!.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audio!!.start()
        }
        server = LiveServer(tv) { MirrorSession.update { if (it.active) it.copy(message = "${tv.name} requested the stream. Waiting for playback…") else it } }
        val stream = server!!; stream.start()
        val mux = TransportStream(withAudio, stream::publish, packetsPerBatch = 32)
        val epochUs = System.nanoTime() / 1000
        // Favour uninterrupted viewing over minimum latency; short network/encoder delays
        // should consume this margin rather than make frames miss their TV presentation time.
        val playoutDelayUs = 1_500_000L
        val audioClock = AudioCaptureClock(System.nanoTime() / 1000)
        val audioTimestamp = AudioTimestamp()
        recorder?.startRecording()
        display = projection.createVirtualDisplay("Roomcast", captureWidth, captureHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, renderer!!.inputSurface, null, null)
        val captureDpi = metrics.densityDpi
        renderTask = renderWorker.submit {
            try {
                while (!stopped.get()) {
                    resizeRequest.getAndSet(null)?.let { size ->
                        display!!.surface = null
                        renderer!!.resize(size.first, size.second)
                        display!!.resize(size.first, size.second, captureDpi)
                        display!!.surface = renderer!!.inputSurface
                    }
                    renderer!!.render(System.nanoTime())
                    Thread.sleep(2)
                }
            } finally { renderer?.close() }
        }
        // Control requests run independently so hardware output continues draining while the TV connects.
        val playback = control.submit { if (!stopped.get()) { controlRequested = true; Dlna.play(tv, stream.url) } }
        var videoConfig = byteArrayOf()
        var audioFrames = 0L
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var controlChecked = false
        var pendingAudioInput = -1
        var lastSizeCheckNs = 0L
        var lastTablesUs = Long.MIN_VALUE
        var statsStartNs = System.nanoTime()
        var encodedFrames = 0
        var lastEncodedNs = 0L
        var longestGapMs = 0L
        try {
            MirrorSession.update { it.copy(message = "Asking ${tv.name} to open the live stream…") }
            while (!stopped.get()) {
                val nowNs = System.nanoTime()
                if (renderTask!!.isDone) { renderTask!!.get(); check(stopped.get()) { "Video renderer stopped unexpectedly" } }
                if (nowNs - statsStartNs >= 2_000_000_000L) {
                    val fps = encodedFrames * 1_000_000_000L / (nowNs - statsStartNs)
                    val sourceAgeMs = renderer!!.lastSourceFrameNs.takeIf { it > 0 }?.let { (nowNs - it).coerceAtLeast(0) / 1_000_000 } ?: 0
                    val details = "Video: $fps fps • ${if (supportsCbr) "CBR" else "VBR"}\n" +
                        "Longest phone gap: ${longestGapMs}ms • render: ${renderer!!.longestRenderMs}ms\n" +
                        "Capture age: ${sourceAgeMs}ms • pending send: ${stream.queuedBytes / 1024} KB\n" +
                        "TV connections: ${stream.connectionCount} • slow disconnects: ${stream.slowDisconnects}"
                    MirrorSession.update { if (it.active) it.copy(diagnostics = details) else it }
                    android.util.Log.i("Roomcast", details)
                    encodedFrames = 0; statsStartNs = nowNs
                }
                // API 29–33 have no projection resize callback. Read the physical display there.
                if (nowNs - lastSizeCheckNs > 200_000_000L) {
                    lastSizeCheckNs = nowNs
                    physicalDisplay.getRealMetrics(metrics)
                    val size = if (Build.VERSION.SDK_INT >= 34) capturedSize ?: (metrics.widthPixels to metrics.heightPixels)
                        else metrics.widthPixels to metrics.heightPixels
                    if (size.first != captureWidth || size.second != captureHeight) {
                        captureWidth = size.first; captureHeight = size.second
                        resizeRequest.set(captureWidth to captureHeight)
                        encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                }
                check(System.nanoTime() - stream.lastReadNanos < 30_000_000_000L) { "TV has not read the stream for 30 seconds. It may not support live DLNA MPEG-TS. Check TV permissions and the network, then try again." }
                if (!controlChecked && playback.isDone) {
                    controlChecked = true
                    try {
                        playback.get()
                        MirrorSession.update { if (it.active) it.copy(message = "TV accepted playback. Sending screen and sound to ${tv.name}.") else it }
                    } catch (e: java.util.concurrent.ExecutionException) {
                        val reason = e.cause
                        // Some receivers read the live stream before completing the Play response.
                        // Keep delivering only when media writes demonstrate recent socket progress.
                        if (Dlna.canContinueAfterTimeout(reason, stream.hasWrittenMedia, System.nanoTime() - stream.lastReadNanos)) {
                            android.util.Log.w("Roomcast", "Play acknowledgement timed out while media was flowing", reason)
                            MirrorSession.update { if (it.active) it.copy(message = "TV is receiving the stream but did not acknowledge Play. Continuing while data flows.") else it }
                        } else throw e
                    }
                }
                audio?.let { codec ->
                    for (attempt in 0 until 4) {
                        if (pendingAudioInput < 0) pendingAudioInput = codec.dequeueInputBuffer(0)
                        if (pendingAudioInput < 0) break
                        val buffer = codec.getInputBuffer(pendingAudioInput)!!; buffer.clear()
                        val count = recorder!!.read(buffer, minOf(buffer.capacity(), 4096), AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "Internal audio capture stopped ($count)" }
                        if (count == 0) break
                        if (count > 0) {
                            val haveTimestamp = recorder!!.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                            val pts = audioClock.presentationTime(audioFrames,
                                if (haveTimestamp) audioTimestamp.framePosition else null,
                                if (haveTimestamp) audioTimestamp.nanoTime / 1000 else null)
                            codec.queueInputBuffer(pendingAudioInput, 0, count, pts - epochUs + playoutDelayUs, 0)
                            pendingAudioInput = -1; audioFrames += count / 4
                        }
                    }
                    drain(codec, audioInfo) { bytes, info -> if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) mux.audio(bytes, info.presentationTimeUs) }
                }
                // Rendering can block on encoder input independently; keep draining output here.
                val index = encoder.dequeueOutputBuffer(videoInfo, 5000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoConfig = listOf("csd-0", "csd-1").fold(byteArrayOf()) { all, key ->
                        val buffer = encoder.outputFormat.getByteBuffer(key)?.duplicate()
                        all + (buffer?.let { ByteArray(it.remaining()).also(it::get) } ?: byteArrayOf())
                    }
                } else if (index >= 0) {
                    try {
                        val buffer = encoder.getOutputBuffer(index)!!; buffer.position(videoInfo.offset); buffer.limit(videoInfo.offset + videoInfo.size)
                        val bytes = ByteArray(videoInfo.size); buffer.get(bytes)
                        if (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) videoConfig = bytes
                        else if (bytes.isNotEmpty()) {
                            val encodedNs = System.nanoTime()
                            if (lastEncodedNs > 0) longestGapMs = maxOf(longestGapMs, (encodedNs - lastEncodedNs) / 1_000_000)
                            lastEncodedNs = encodedNs; encodedFrames++
                            val key = videoInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val clockUs = System.nanoTime() / 1000 - epochUs
                            if (key) stream.keyframe()
                            if (key || lastTablesUs == Long.MIN_VALUE || clockUs - lastTablesUs >= 100_000) { mux.tables(); lastTablesUs = clockUs }
                            val aud = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
                            mux.video(aud + (if (key) videoConfig else byteArrayOf()) + bytes,
                                videoInfo.presentationTimeUs - epochUs + playoutDelayUs, clockUs)
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
        } finally { playback.cancel(true); control.shutdownNow() }
    }
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, consume: (ByteArray, MediaCodec.BufferInfo) -> Unit) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            try { val buffer = codec.getOutputBuffer(index)!!; buffer.position(info.offset); buffer.limit(info.offset + info.size)
                if (info.size > 0) consume(ByteArray(info.size).also(buffer::get), info)
            } finally { codec.releaseOutputBuffer(index, false) }
        }
    }
    private fun stopSession() { stopped.set(true) }
    private fun cleanup() {
        runCatching { server?.close() }
        // Let an in-flight swap finish by draining output before joining the renderer.
        val shutdownDeadline = System.nanoTime() + 2_000_000_000L
        val discardInfo = MediaCodec.BufferInfo()
        while (renderTask?.isDone == false && System.nanoTime() < shutdownDeadline) {
            runCatching { video?.let { codec ->
                val index = codec.dequeueOutputBuffer(discardInfo, 5000)
                if (index >= 0) codec.releaseOutputBuffer(index, false)
            } }
        }
        if (renderTask?.isDone == false) runCatching { video?.stop() }
        // GL cleanup must execute on its owning thread, including failures before the loop starts.
        if (renderTask == null) renderWorker.submit { renderer?.close() }
        renderWorker.shutdown()
        runCatching { renderWorker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { display?.release() }
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }
        listOf(video, audio).forEach { codec -> runCatching { codec?.stop() }; runCatching { codec?.release() } }
        runCatching { surface?.release() }
        runCatching { projection?.unregisterCallback(callback) }; runCatching { projection?.stop() }
        MirrorSession.update { it.copy(message = "Screen and audio capture are off. Closing the TV connection…") }
        control.shutdownNow()
        runCatching { control.awaitTermination(19, java.util.concurrent.TimeUnit.SECONDS) }
        if (controlRequested) receiver?.let { runCatching { Dlna.action(it, "Stop", "", timeoutMs = 4000) } }
    }
    override fun onDestroy() { stopSession(); worker.shutdown(); control.shutdownNow(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSession() }
}
