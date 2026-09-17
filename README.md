# Roomcast

### No Smart View? Bring your Android screen to your TV.

Roomcast mirrors your Android screen and supported internal audio to compatible DLNA TVs—an alternative for phones without built-in Smart View or screen mirroring. No TV browser, extra TV app, or session timer. Requires Android 10 or newer.

Well compatable with a National **webOS TV**. **Android TV** may also work if it provides a compatible DLNA receiver, built-in Chromecast alone is not enough.

**Experimental:** compatibility and playback smoothness vary. The TV must accept a live, close-delimited HTTP MPEG-TS stream containing H.264 video and AAC audio through DLNA/UPnP AVTransport. Some TVs support file playback through DLNA but reject this live format. Finding a TV does not prove it can play the stream.

| Connection | Implemented |
| --- | --- |
| DLNA / UPnP | Discovery, native-player commands, live screen and optional internal audio |
| AirPlay | Discovery / identification only |
| Google Cast | Discovery / identification only |
| Miracast / Wi-Fi Direct | No sender implementation |

No TV browser, custom TV application, cloud service, account, or mirroring-duration paywall is used. The TV decides whether to show its native approval popup; the app cannot force one. DLNA playback can have several seconds of latency.

## Build and install

Open this project in Android Studio with the SDK and JDK required by its existing Gradle configuration, or run:

```sh
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

The debug APK is in `androidApp/build/outputs/apk/debug/`. Minimum SDK is 29; the existing compile/target SDK is 37. Android 17+ receives a local-network runtime permission request before discovery. Android 13 does not need that permission.

## Use with the phone and TV

1. Connect both devices to the same Wi-Fi network. Alternatively, enable the phone hotspot and connect the TV to it. Internet access is not required, but the devices must be able to communicate locally.
2. Enable media sharing / DLNA in the TV settings if available. Keep the TV on.
3. Open **Roomcast → Find TVs**. Select a receiver labelled **DLNA**. An AirPlay-only or Cast-only result cannot be used by this version.
4. Choose 480p, 720p, or 1080p and whether to include internal audio. The setting limits the shorter screen dimension and never upscales the phone display.
5. Tap **Start mirroring**, allow audio permission if requested, and approve Android screen sharing. Allow notifications to get a convenient Stop control outside the app; denial does not prevent mirroring. Approve any prompt the TV itself presents.
6. Open the content you want to share. Use **Stop mirroring**, the ongoing notification's **Stop** action, or Android's screen-sharing control to end the session.

Capture continues when the app is in the background. Removing the app from recents ends capture. A stopped process does not automatically resume capture. The TV receives a fixed 16:9 landscape video. The captured content resizes as the phone rotates, preserving its aspect ratio; portrait content has side bars, and ultrawide phone content can have top/bottom bars. The TV does not need to restart its decoder on rotation.

## Troubleshooting and limits

Version 0.4.1 reduces capture-loop polling (including repeated empty audio reads), batches up to 32 complete TS packets per network queue operation, replaces boxed continuity counters with a fixed array, and moves frame-available callbacks off the UI thread. The outgoing queue remains bounded to roughly 1.5 MB per client, with an additional object-count limit. Byte-equivalence tests verify that batching preserves the stream exactly. These optimizations follow A12 measurements showing frame-output dips and CPU work without sustained application send-queue buildup; they do not yet prove that TV playback stalls are eliminated.

Version 0.4 moves EGL rendering/submission to a dedicated worker so a blocked input swap cannot prevent encoder output and audio from being drained. Encoder output remains on the capture worker; GL creation, rotation, rendering and destruction stay on the rendering worker. Frame deadlines retain their original phase instead of accumulating scheduling delays, and missed slots are skipped. CBR is requested only when the encoder advertises support; otherwise the existing mode is retained. The presentation margin is now 1.5 seconds, trading additional latency for more tolerance of brief delivery delays.

The connection card now shows outgoing video fps, the longest encoded-frame gap and render time, capture-frame age, pending-send bytes, TV connection count, and slow-client disconnects. Longest values are session maxima. The send queue measures only the phone's application queue, not TCP buffers or TV buffering; these statistics cannot prove the TV is displaying frames smoothly. If a pause persists, share this card after reproducing it and say whether phone playback and TV audio continued. No screen content or stream URL is included in these diagnostics. The reported 3–4 second stutter has not yet been reproduced on a connected A12/TV here.

Version 0.3 adds GPU composition for rotation into a fixed landscape stream, a maximum 30 fps render cadence without catch-up frame bursts, hardware audio timestamps referenced to the same monotonic clock as video, and a 700 ms presentation margin relative to the MPEG-TS clock. Network writes are batched in roughly 20 ms windows instead of flushing individual TS packets. This intentionally trades some latency for steadier playback. Actual A/V alignment, rotation, GPU performance, and buffering on the A12/TV require hardware testing; 50/60 fps sports playback is not provided by this version.

Version 0.2 extends TV command acknowledgement waits to 15 seconds. If only the Play acknowledgement times out while media writes are still progressing, capture continues with a warning instead of tearing down the stream. Other command failures still stop the session and identify the failing command. Socket progress is tracked on bounded writes even when the encoder queue stays busy. These changes address premature shutdown paths; they do not establish TV playback compatibility.

- **No TV found:** verify the TV is connected to the phone hotspot or same LAN, enable TV media sharing, disable VPN routing if it interferes, and check network client-isolation settings. The app sends SSDP on each active IPv4 interface, including tethering interfaces; firmware may still block hotspot multicast discovery.
- **Only AirPlay or Cast appears:** this identifies a likely receiver protocol; those sender protocols still need implementation. It does not establish which protocol PigeonCast used.
- **TV rejects Play / never loads:** live MPEG-TS may be unsupported even when ordinary DLNA video files work. The app reports command failures and ends capture if the TV does not read the stream for 30 seconds. There is no duration limit while the TV keeps reading.
- **Streaming message but no picture:** that message means the TV requested the HTTP stream, not that it decoded or displayed it. Check native-player format support.
- **Silent audio:** only permitted media/game/unknown-usage playback is captured. Individual apps can prohibit capture; calls, communication audio, and protected content are not supported. Microphone recording is not used.
- **Black video:** protected/secure windows cannot be mirrored through MediaProjection.
- **Lag or thermal throttling:** try 480p. DLNA buffering is controlled in part by the TV; this transport is unsuitable for latency-sensitive gaming.

The native popup wording, PigeonCast receiver label, exact TV model/firmware, and results on the A12 hotspot are the next useful evidence for choosing an additional transport. Do not include pairing PINs when sharing screenshots.

## Implementation

- `shared/commonMain`: Compose connection screen, receiver/session models, quality controls, and an MPEG-TS muxer with PAT/PMT, PCR/PTS, H.264 PES and AAC ADTS framing.
- `shared/androidMain/Discovery.kt`: SSDP discovery, AirPlay/Cast NSD identification, bounded XML descriptions, and AVTransport SOAP actions.
- `shared/androidMain/LiveServer.kt`: session-scoped HTTP server bound to the route toward the TV. Only the selected TV address can read a random session URL. New clients wait for a keyframe; slow clients are disconnected; encoded buffers are bounded and kept in memory.
- `shared/androidMain/MirrorService.kt`: MediaProjection foreground service, hardware H.264/AAC encoding, AudioPlaybackCapture, stream delivery and cleanup.
- `shared/androidMain/ScreenRenderer.kt`: SurfaceTexture/EGL composition with automatic capture resizing, aspect-preserving landscape output, and bounded render cadence.
- `androidApp`: runtime permissions and Android's per-session screen-capture approval flow.
- iOS and desktop retain the shared UI entry point; capture and transport are Android-only.

Traffic uses unencrypted local HTTP because native DLNA receivers commonly require it. Use a trusted local network. There is no persistent screen recording or analytics. The Android permission named `INTERNET` is needed for local sockets even when the network is offline.

## Verification

```sh
./gradlew :shared:jvmTest :shared:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug
```

Tests check TS packet boundaries, payload preservation, continuity counters, table CRCs, timestamps, AAC framing, XML parsing/DTD rejection, SOAP ordering/escaping, and HTTP session-path/keyframe behavior. These checks do not replace real MediaCodec, audio-policy, hotspot, or TV playback tests.

Android platform references: [MediaProjection](https://developer.android.com/media/grow/media-projection), [playback capture](https://developer.android.com/media/platform/av-capture), and [local network permission](https://developer.android.com/privacy-and-security/local-network-permission).
