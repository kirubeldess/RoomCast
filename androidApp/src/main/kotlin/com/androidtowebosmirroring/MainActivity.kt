package com.androidtowebosmirroring

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
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    private var discovery: Discovery? = null
    private var pending: Receiver? = null
    private var captureAudio = true
    private var quality = 720
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else MirrorSession.update { it.copy(message = "Allow local network access to find and connect to your TV.") }
    }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tv = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && tv != null) {
            try {
                startForegroundService(Intent(this, MirrorService::class.java).putExtra("consent", result.data)
                    .putExtra("id", tv.id).putExtra("name", tv.name).putExtra("url", tv.controlUrl).putExtra("type", tv.serviceType)
                    .putExtra("audio", captureAudio).putExtra("quality", quality))
            } catch (e: Exception) { MirrorSession.update { it.copy(message = "Cannot start mirroring: ${e.message}") } }
        } else MirrorSession.update { it.copy(message = "Screen sharing was cancelled. Nothing is being captured.") }
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) prepareCapture() else { pending = null; MirrorSession.update { it.copy(message = "Audio permission denied. Turn Internal audio off to share only your screen.") } }
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
        discovery?.close()
        MirrorSession.update { it.copy(receivers = emptyList(), scanning = true, message = "Searching Wi-Fi and hotspot interfaces…") }
        discovery = Discovery(this, { tv -> MirrorSession.update { it.copy(receivers = (it.receivers.filterNot { old -> old.id == tv.id } + tv)) } },
            { message -> MirrorSession.update { it.copy(scanning = false, message = message) } }).also { it.start() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArray("pending")?.let { pending = Receiver(it[0], it[1], "DLNA", it[2], it[3]) }
        captureAudio = savedInstanceState?.getBoolean("audio", true) ?: true
        quality = savedInstanceState?.getInt("quality", 720) ?: 720

        setContent {
            App(MirrorSession.state, onScan = ::scan, onStart = { tv, audio, resolution ->
                if (pending == null && !MirrorSession.state.active) {
                    pending = tv; captureAudio = audio; quality = resolution
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
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { discovery?.close(); MirrorSession.update { it.copy(scanning = false) }; super.onDestroy() }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
