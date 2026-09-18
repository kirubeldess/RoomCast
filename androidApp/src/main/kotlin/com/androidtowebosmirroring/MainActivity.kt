package com.androidtowebosmirroring

import com.androidtowebosmirroring.infrastructure.network.Discovery

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidtowebosmirroring.domain.DiscoveryFactory
import com.androidtowebosmirroring.domain.Receiver
import com.androidtowebosmirroring.presentation.MirrorViewModel
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    private val sessions get() = RoomcastDependencies.sessions
    private val screenModel: MirrorViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == MirrorViewModel::class.java)
                val appContext = applicationContext
                return MirrorViewModel(sessions, DiscoveryFactory { found, finished ->
                    Discovery(appContext, found, finished)
                }) as T
            }
        })[MirrorViewModel::class.java]
    }
    private var pending: Receiver? = null
    private var captureAudio = true
    private var quality = 720
    private var videoMode = false
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else sessions.update { it.copy(message = "Allow local network access to find and connect to your TV.") }
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tv = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && tv != null) {
            try {
                startForegroundService(Intent(this, MirrorService::class.java).putExtra("consent", result.data)
                    .putExtra("id", tv.id).putExtra("name", tv.name).putExtra("url", tv.controlUrl).putExtra("type", tv.serviceType)
                    .putExtra("audio", captureAudio).putExtra("quality", quality).putExtra("videoMode", videoMode))
            } catch (e: Exception) { sessions.update { it.copy(message = "Cannot start mirroring: ${e.message}") } }
        } else sessions.update { it.copy(message = "Screen sharing was cancelled. Nothing is being captured.") }
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepareCapture() else { pending = null; sessions.update { it.copy(message = "Audio permission denied. Turn Internal audio off to share only your screen.") } }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }
    private fun prepareCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestCapture()
    }
    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        consent.launch(if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent())
    }
    private fun scan() {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK"); return
        }
        screenModel.scan()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArray("pending")?.let { pending = Receiver(it[0], it[1], "DLNA", it[2], it[3]) }
        captureAudio = savedInstanceState?.getBoolean("audio", true) ?: true
        quality = savedInstanceState?.getInt("quality", 720) ?: 720
        videoMode = savedInstanceState?.getBoolean("videoMode", false) ?: false

        setContent {
            val state = screenModel.state.collectAsStateWithLifecycle().value
            App(state, captureAvailable = true,
                maxVideoHeight = resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) },
                onScan = ::scan, onStart = { tv, audio, resolution, cropVideo ->
                if (pending == null && !sessions.state.value.active) {
                    pending = tv; captureAudio = audio; quality = resolution; videoMode = cropVideo
                    if (audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    else prepareCapture()
                }
            }, onStop = { startService(Intent(this, MirrorService::class.java).setAction("STOP")) })
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pending?.let { outState.putStringArray("pending", arrayOf(it.id, it.name, it.controlUrl, it.serviceType)) }
        outState.putBoolean("audio", captureAudio); outState.putInt("quality", quality)
        outState.putBoolean("videoMode", videoMode)
        super.onSaveInstanceState(outState)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
