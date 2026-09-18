package com.androidtowebosmirroring

import com.androidtowebosmirroring.domain.Receiver
import com.androidtowebosmirroring.domain.availableQualities
import com.androidtowebosmirroring.presentation.MirrorState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF142C46)
private val Blue = Color(0xFF245CC7)
private val Mist = Color(0xFFEDF2F8)
private val Muted = Color(0xFF536478)
private val Line = Color(0xFFD5DFEC)
private val Sky = Color(0xFFBFD7FF)

@Composable
fun App(state: MirrorState = MirrorState(message = "Screen capture is available in the Android app."),
        onScan: () -> Unit = {}, onStart: (Receiver, Boolean, Int, Boolean) -> Unit = { _, _, _, _ -> },
        onStop: () -> Unit = {}, maxVideoHeight: Int = 1080, captureAvailable: Boolean = false) {

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var audio by rememberSaveable { mutableStateOf(true) }

    var quality by rememberSaveable { mutableStateOf(720) }
    var videoMode by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var otherDevices by rememberSaveable { mutableStateOf(false) }

    val qualities = availableQualities(maxVideoHeight)
    val effectiveQuality = qualities.lastOrNull { it <= quality } ?: qualities.firstOrNull() ?: 480
    val receiver = state.receivers.find { it.id == selected && it.protocol == "DLNA" }
    val supported = state.receivers.filter { it.protocol == "DLNA" }
    val unsupported = state.receivers.filter { it.protocol != "DLNA" }


    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White,
        background = Mist, surface = Color.White, onSurface = Ink, onBackground = Ink,
        onSurfaceVariant = Muted, outlineVariant = Line, secondaryContainer = Color(0xFFE4EDFC),

        onSecondaryContainer = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Mist) {
            Column(Modifier.safeDrawingPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),

                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("roomcast", fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
                                fontSize = 32.sp, letterSpacing = (-1).sp)
                            Text("Your screen. A bigger view.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { help = true }) { Text("Help") }
                    }

                    Surface(shape = RoundedCornerShape(24.dp), color = Ink, contentColor = Color.White) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (state.active) "SCREEN SHARING" else "PHONE → TV",
                                color = Sky, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                            Text(if (state.active) "Your session" else "Make room for the big screen.",
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Local streaming · No session timer", color = Sky,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Your TV", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = onScan, enabled = captureAvailable && !state.scanning && !state.active) {
                                Text(if (state.scanning) "Searching…" else if (state.receivers.isEmpty()) "Find TVs" else "Refresh")
                            }
                        }
                        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (supported.isEmpty()) Panel {
                            Text(if (state.scanning) "Looking for a compatible TV…" else "Connect on the same Wi-Fi",
                                fontWeight = FontWeight.SemiBold)
                            Text("Turn on your TV and enable media sharing / DLNA, then tap Find TVs.",
                                color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            supported.forEach { tv ->
                                Surface(shape = RoundedCornerShape(18.dp), color = if (receiver?.id == tv.id) Color(0xFFE4EDFC) else Color.White,
                                    modifier = Modifier.fillMaxWidth().border(1.dp, if (receiver?.id == tv.id) Blue else Line, RoundedCornerShape(18.dp))) {
                                    Row(Modifier.selectable(selected = receiver?.id == tv.id, enabled = !state.active,
                                        role = Role.RadioButton, onClick = { selected = tv.id }).padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = receiver?.id == tv.id, onClick = null, enabled = !state.active)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(tv.name, fontWeight = FontWeight.SemiBold)
                                            Text("DLNA · Built-in TV player", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        if (unsupported.isNotEmpty()) {
                            TextButton(onClick = { otherDevices = !otherDevices }) {
                                Text(if (otherDevices) "Hide other receivers" else "Other receivers (${unsupported.size})")
                            }
                            if (otherDevices) Panel {
                                Text("Detected, but not supported for mirroring yet.", color = Muted)
                                unsupported.forEach { Text("${it.name} · ${it.protocol}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Picture & sound", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (state.active) Text("Stop mirroring to change these settings.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Panel {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Internal audio", fontWeight = FontWeight.SemiBold)
                                    Text("Supported media and games. No microphone.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = audio, onCheckedChange = { audio = it }, enabled = !state.active,
                                    modifier = Modifier.semantics { contentDescription = "Internal audio" })
                            }
                            HorizontalDivider(color = Line)
                            Text("Video quality", fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                qualities.forEach { size ->
                                    FilterChip(selected = effectiveQuality == size, onClick = { quality = size },
                                        enabled = !state.active, label = { Text("${size}p") })
                                }
                            }
                            Text(if (effectiveQuality == 480) "Less network load. Try this if playback stutters."
                                else "Up to 30 fps. Lower quality can help on a weak connection.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (maxVideoHeight < 1080) Text("Resolution is limited to your phone’s screen.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Panel {
                            Text("TV picture", fontWeight = FontWeight.SemiBold)
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PictureChoice("Fit screen", "Show the whole phone screen.", !videoMode, !state.active, false) { videoMode = false }
                                PictureChoice("16:9 video", "Remove side areas around centered video.", videoMode, !state.active, true) { videoMode = true }
                            }
                            if (videoMode) Text("Use with black side bars in landscape. This crops a fixed area—not automatic bar detection. Other content at the sides will be cut off; portrait stays fitted.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.diagnostics.isNotBlank()) {
                        TextButton(onClick = { details = !details }) { Text(if (details) "Hide session details" else "Show session details") }
                        if (details) Panel {
                            Text(state.diagnostics, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("Longest times are session maximums. These numbers do not measure TV playback.", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("TV playback may be delayed. Protected video and some app audio cannot be shared.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { if (state.active) onStop() else receiver?.let { onStart(it, audio, effectiveQuality, videoMode) } },
                            enabled = state.active || (captureAvailable && receiver != null && !state.scanning),
                            colors = ButtonDefaults.buttonColors(containerColor = if (state.active) Ink else Blue),
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                            Text(if (state.active) "Stop mirroring" else "Start mirroring", fontWeight = FontWeight.SemiBold)
                        }
                        Text(when {
                            state.active -> "Stop sharing at any time."
                            !captureAvailable -> "Mirroring is available in the Android app."
                            receiver == null -> "Choose a DLNA TV to continue."
                            else -> "Android will ask permission to share your screen."
                        }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Before you mirror") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Connect both devices to the same local network. Your TV must support live DLNA playback; AirPlay and Google Cast are discovery-only.")
                    Text("No internet is needed for the mirroring stream. Online videos still use data. Hotspot compatibility depends on your devices.")
                    Text("Your screen—including visible notifications—is shared. Calls and protected content are not supported. Stop from Roomcast or its notification.")
                    Text("The stream uses unencrypted local HTTP. Use a trusted network. Roomcast does not save a recording or use cloud streaming.")
                    Text("If playback stutters, try 480p. TV buffering adds delay; mirroring is not ideal for fast games.")
                }
            }, confirmButton = { TextButton(onClick = { help = false }) { Text("Got it") } })
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PictureChoice(title: String, description: String, selected: Boolean, enabled: Boolean,
                          filled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.size(48.dp, 30.dp).background(Ink, RoundedCornerShape(5.dp)).padding(4.dp),
            contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(if (filled) 1f else 0.65f)
                .background(Sky, RoundedCornerShape(2.dp)))
        }
    }
}
