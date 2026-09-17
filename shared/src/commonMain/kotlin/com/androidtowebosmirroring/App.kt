package com.androidtowebosmirroring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Receiver(val id: String, val name: String, val protocol: String, val controlUrl: String = "", val serviceType: String = "")
data class MirrorState(val receivers: List<Receiver> = emptyList(), val scanning: Boolean = false,
    val active: Boolean = false, val message: String = "Connect your phone and TV to the same network.",
    val diagnostics: String = "")

@Composable
fun App(state: MirrorState = MirrorState(message = "Screen capture is available in the Android app."),
        onScan: () -> Unit = {}, onStart: (Receiver, Boolean, Int) -> Unit = { _, _, _ -> }, onStop: () -> Unit = {}) {
    var selected by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(720) }
    val receiver = state.receivers.find { it.id == selected }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF285BB5), background = Color(0xFFF0F4FA),
        surface = Color.White, onSurface = Color(0xFF182944), secondary = Color(0xFF51717B))) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeContentPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("LOCAL / SCREEN + SOUND", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Text("Roomcast", style = MaterialTheme.typography.displayMedium)
            Text("Your phone. On the big screen.", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.active) "PHONE  →  TV" else "PHONE  ···  TV", fontFamily = FontFamily.Monospace, fontSize = 24.sp)
                    Text(state.message)
                    if (state.diagnostics.isNotEmpty()) Text(state.diagnostics, style = MaterialTheme.typography.bodySmall)
                    Text("Local connection • No session timer", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Nearby TVs", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onScan, enabled = !state.scanning && !state.active) { Text(if (state.scanning) "Searching…" else "Find TVs") }
            }
            if (state.receivers.isEmpty()) Text("Turn on your TV, enable media sharing, then tap Find TVs. For offline use, connect the TV to your phone’s hotspot.")
            state.receivers.forEach { tv ->
                OutlinedCard(onClick = { selected = tv.id }, enabled = !state.active, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp)) {
                        RadioButton(selected = selected == tv.id, onClick = null)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (tv.protocol == "DLNA") "DLNA · Native TV player" else "${tv.protocol} · Detected; mirroring not implemented", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Internal audio"); Text("Media and game sound", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = audio, onCheckedChange = { audio = it }, enabled = !state.active)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(480, 720, 1080).forEach { size -> FilterChip(selected = quality == size, onClick = { quality = size }, enabled = !state.active, label = { Text("${size}p") }) }
            }
            Button(onClick = { if (state.active) onStop() else receiver?.let { onStart(it, audio, quality) } },
                enabled = state.active || receiver?.protocol == "DLNA", modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(if (state.active) "Stop mirroring" else "Start mirroring")
            }
            Text("DLNA may have several seconds of delay. Your TV must support live H.264/AAC MPEG-TS playback. A TV approval prompt depends on its firmware.", style = MaterialTheme.typography.bodySmall)
            Text("Android asks before sharing your screen. Protected video, calls, and apps that block audio capture cannot be shared.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
