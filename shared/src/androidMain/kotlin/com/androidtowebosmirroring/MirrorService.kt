package com.androidtowebosmirroring

import com.androidtowebosmirroring.infrastructure.capture.AndroidCaptureEngine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import com.androidtowebosmirroring.domain.MirrorRequest
import com.androidtowebosmirroring.domain.Receiver
import com.androidtowebosmirroring.domain.SessionController
import java.util.concurrent.Executors

/** Android lifecycle/notification host only; the controller owns the session lifecycle. */
class MirrorService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var controller: SessionController? = null
    private var started = false
    private val sessions get() = RoomcastDependencies.sessions

    override fun onBind(intent: Intent?) = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            controller?.stop()
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started || intent == null) {
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        started = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mirroring", "Screen mirroring", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(
                this, 0, Intent(this, MirrorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, "mirroring")
                .setContentTitle("Roomcast is sharing your screen")
                .setContentText("Tap Stop to end screen and audio sharing.")
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val consent = intent.getParcelableExtra<Intent>("consent") ?: error("Screen capture approval missing")
            val request = MirrorRequest(
                receiver = Receiver(
                    intent.getStringExtra("id") ?: error("No TV selected"),
                    intent.getStringExtra("name").orEmpty(),
                    "DLNA",
                    intent.getStringExtra("url") ?: error("No TV address"),
                    intent.getStringExtra("type") ?: error("No TV service"),
                ),
                audio = intent.getBooleanExtra("audio", true),
                quality = intent.getIntExtra("quality", 720),
                videoMode = intent.getBooleanExtra("videoMode", false),
            )
            val session = SessionController(AndroidCaptureEngine(applicationContext, consent, sessions), sessions) {
                android.util.Log.e("Roomcast", "Capture session ended", it)
            }
            controller = session
            worker.execute {
                try {
                    session.run(request)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (error: Exception) {
            controller?.stop()
            sessions.update { it.copy(active = false, message = "Cannot start screen capture: ${error.message}") }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller?.stop()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        controller?.stop()
    }
}
