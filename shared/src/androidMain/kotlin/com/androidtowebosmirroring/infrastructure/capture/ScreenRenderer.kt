package com.androidtowebosmirroring.infrastructure.capture

import com.androidtowebosmirroring.domain.media.*

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** All GL methods run on the dedicated rendering worker, separate from encoder output draining. */
class ScreenRenderer(private val videoMode: Boolean = false) : AutoCloseable {
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var input: Surface? = null
    val inputSurface: Surface get() = checkNotNull(input)
    private var textureId = 0
    private var program = 0
    private var position = 0
    private var uv = 0
    private var matrixLocation = 0
    private var samplerLocation = 0
    private var viewport = VideoViewport(0, 0, 1, 1)
    @Volatile var longestRenderMs = 0L
        private set
    @Volatile var lastSourceFrameNs = 0L
        private set
    private val frameAvailable = AtomicBoolean(false)
    private var frameNotifications: HandlerThread? = null
    private var haveFrame = false
    private val cadence = FrameCadence()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var outputWidth = 1
    private var outputHeight = 1
    private val transform = FloatArray(16)
    private val positions = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    fun initialize(encoderSurface: Surface, width: Int, height: Int, captureWidth: Int, captureHeight: Int) {
        outputWidth = width; outputHeight = height
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 0)) { "Cannot initialize video renderer" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1, EGL14.EGL_NONE) // EGL_RECORDABLE_ANDROID
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL configuration" }
        context = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        window = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(eglDisplay, window, window, context)) { "Cannot attach video encoder to renderer" }
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val notifications = HandlerThread("RoomcastFrames").also { it.start(); frameNotifications = it }
        texture = SurfaceTexture(textureId).also { it.setOnFrameAvailableListener({ frameAvailable.set(true) }, Handler(notifications.looper)) }
        resize(captureWidth, captureHeight)
        input = Surface(texture)
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 uv; uniform mat4 transform; varying vec2 coord; void main(){ gl_Position=vec4(p,0.0,1.0); coord=(transform*vec4(uv,0.0,1.0)).xy; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES screen; varying vec2 coord; void main(){ gl_FragColor=texture2D(screen,coord); }")
        try {
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
            val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Video shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        } finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
        position = GLES20.glGetAttribLocation(program, "p"); uv = GLES20.glGetAttribLocation(program, "uv")
        matrixLocation = GLES20.glGetUniformLocation(program, "transform")
        samplerLocation = GLES20.glGetUniformLocation(program, "screen")
    }
    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
        val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { val message = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); error("Video shader failed: $message") }
        return id
    }
    fun resize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        sourceWidth = width; sourceHeight = height
        val inset = videoSideInset(width, height, videoMode)
        viewport = if (inset > 0f) fitViewport(16, 9, outputWidth, outputHeight)
            else fitViewport(width, height, outputWidth, outputHeight)
        // Crop in logical capture coordinates before SurfaceTexture's rotation/flip transform.
        coordinates.position(0)
        coordinates.put(floatArrayOf(inset, 0f, 1f - inset, 0f, inset, 1f, 1f - inset, 1f))
        coordinates.position(0)
        texture?.setDefaultBufferSize(width, height)
        haveFrame = false
    }
    fun render(nowNs: Long) {
        val frameTimeNs = cadence.frameTime(nowNs) ?: return
        val startedNs = System.nanoTime()
        if (frameAvailable.getAndSet(false)) {
            texture!!.updateTexImage(); texture!!.getTransformMatrix(transform); haveFrame = true
            lastSourceFrameNs = System.nanoTime()
        }
        if (!haveFrame) return
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val fit = viewport
        GLES20.glViewport(fit.x, fit.y, fit.width, fit.height)
        GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transform, 0)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(EGLExt.eglPresentationTimeANDROID(eglDisplay, window, frameTimeNs) && EGL14.eglSwapBuffers(eglDisplay, window)) { "Video render failed" }
        longestRenderMs = maxOf(longestRenderMs, (System.nanoTime() - startedNs) / 1_000_000)
    }
    override fun close() {
        input?.release(); texture?.setOnFrameAvailableListener(null); texture?.release()
        frameNotifications?.quitSafely()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(eglDisplay)
        }
    }
}
