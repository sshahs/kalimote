package dev.kalimote.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.app.UiState
import dev.kalimote.atvremote.JellyfinAction
import dev.kalimote.atvremote.JellyfinLibraryItem
import dev.kalimote.atvremote.JellyfinSection
import dev.kalimote.atvremote.JellyfinSession
import dev.kalimote.atvremote.JellyfinTrack
import dev.kalimote.atvremote.KeyCodes
import kotlinx.coroutines.delay

val JellyfinPurple = Color(0xFFAA5CC3)
val JellyfinBlue = Color(0xFF00A4DC)

class JellyfinActions(
    val key: (Int) -> Unit,
    val control: (JellyfinAction) -> Unit,
    val openSettings: () -> Unit,
    val poster: suspend (itemId: String, tag: String?) -> ByteArray?,
    val browse: suspend (view: String, query: String?, item: JellyfinLibraryItem?) -> List<JellyfinSection>,
    val play: (JellyfinLibraryItem) -> Unit,
)

@Composable
private fun JellyfinLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width / 2, size.height * 0.05f)
            lineTo(size.width * 0.95f, size.height * 0.9f)
            lineTo(size.width * 0.05f, size.height * 0.9f)
            close()
        }
        drawPath(path, Brush.linearGradient(listOf(JellyfinPurple, JellyfinBlue), Offset.Zero, Offset(size.width, size.height)))
    }
}

private fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Shown on the remote while Jellyfin (release or debug build) is in the TV's foreground. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JellyfinPanel(state: UiState, actions: JellyfinActions, onLibrary: () -> Unit) {
    val app = state.jellyfinApp ?: return
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        JellyfinPurple.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainer),
                        JellyfinBlue.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surfaceContainer),
                    ),
                ),
            )
            .border(1.dp, JellyfinPurple.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JellyfinLogo()
            Text("Jellyfin", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (app.debug) {
                Text(
                    "DEBUG",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF222222),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Warn).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (state.jellyfinConfigured) {
                TextButton(onClick = onLibrary) { Text("Library") }
            }
            IconButton(onClick = actions.openSettings, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Settings, "Jellyfin server settings", Modifier.size(18.dp))
            }
        }

        val session = state.jellyfinSession
        when {
            session?.item != null -> NowPlaying(session, state.jellyfinFetchedAt, actions)
            !state.jellyfinConfigured -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Connect your Jellyfin server to see what's playing, seek, and switch audio and subtitle tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.openSettings) { Text("Set up") }
            }
            else -> Text(
                state.jellyfinError ?: "Nothing playing in Jellyfin on this TV right now.",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.jellyfinError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Plain remote keys: work with no server setup.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "« Skip" to KeyCodes.MEDIA_REWIND,
                "Skip »" to KeyCodes.MEDIA_FAST_FORWARD,
                "Subtitles" to KeyCodes.CAPTIONS,
                "Audio" to KeyCodes.MEDIA_AUDIO_TRACK,
                "Options" to KeyCodes.MENU,
                "Info" to KeyCodes.INFO,
                "Stop" to KeyCodes.MEDIA_STOP,
                "Search" to KeyCodes.SEARCH,
            ).forEach { (label, code) ->
                RemoteButton(
                    label,
                    Modifier.height(36.dp),
                    text = label,
                    shape = RoundedCornerShape(18.dp),
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) { actions.key(code) }
            }
        }
    }
}

@Composable
private fun NowPlaying(session: JellyfinSession, fetchedAt: Long, actions: JellyfinActions) {
    val item = session.item ?: return
    // Advance the position locally between polls while playing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.paused) {
        while (!session.paused) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val runtime = item.runtimeMs.coerceAtLeast(1)
    val position = (session.positionMs + if (session.paused) 0 else (now - fetchedAt).coerceAtLeast(0)).coerceAtMost(runtime)
    var dragging by remember { mutableStateOf<Float?>(null) }

    val poster by produceState<ImageBitmap?>(null, item.id) {
        value = actions.poster(item.id, item.imageTag)?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(76.dp, 112.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(JellyfinPurple, JellyfinBlue))),
            contentAlignment = Alignment.Center,
        ) {
            val p = poster
            if (p != null) {
                Image(p, null, Modifier.fillMaxWidth().height(112.dp), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Filled.Movie, null, tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                item.seriesName?.let { add(it) }
                if (item.season != null && item.episode != null) add("S${item.season} · E${item.episode}")
                else item.year?.let { add(it.toString()) }
                if (session.paused) add("Paused")
            }.joinToString(" · ")
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(
                value = dragging ?: position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { actions.control(JellyfinAction.Seek(it.toLong())) }
                    dragging = null
                },
                valueRange = 0f..runtime.toFloat(),
                colors = SliderDefaults.colors(thumbColor = JellyfinPurple, activeTrackColor = JellyfinPurple),
            )
            Row {
                Text(fmt(dragging?.toLong() ?: position), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(fmt(item.runtimeMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(12.dp)
        val m = Modifier.weight(1f).height(42.dp)
        val c = MaterialTheme.colorScheme.surfaceContainerHigh
        RemoteButton("Previous", m, icon = Icons.Filled.SkipPrevious, shape = shape, container = c) { actions.control(JellyfinAction.Previous) }
        RemoteButton("Back 10 seconds", m, text = "−10s", shape = shape, container = c) { actions.control(JellyfinAction.SeekBy(-10_000)) }
        RemoteButton(
            "Play/Pause", m, icon = if (session.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            shape = shape, container = JellyfinPurple, content = Color.White,
        ) { actions.control(JellyfinAction.PlayPause) }
        RemoteButton("Forward 30 seconds", m, text = "+30s", shape = shape, container = c) { actions.control(JellyfinAction.SeekBy(30_000)) }
        RemoteButton("Next", m, icon = Icons.Filled.SkipNext, shape = shape, container = c) { actions.control(JellyfinAction.Next) }
        RemoteButton("Stop", m, icon = Icons.Filled.Stop, shape = shape, container = c) { actions.control(JellyfinAction.Stop) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackPicker("Audio", session.audio, includeOff = false, Modifier.weight(1f)) { actions.control(JellyfinAction.Audio(it)) }
        TrackPicker("Subtitles", session.subtitles, includeOff = true, Modifier.weight(1f), offSelected = session.subtitleIndex == -1) {
            actions.control(JellyfinAction.Subtitle(it))
        }
    }

    var messaging by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { messaging = true }, modifier = Modifier.fillMaxWidth()) { Text("Message TV…") }
    if (messaging) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { messaging = false },
            title = { Text("Message to show on the TV") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Dinner is ready!") }) },
            confirmButton = {
                Button(enabled = text.isNotBlank(), onClick = {
                    actions.control(JellyfinAction.Message(text))
                    messaging = false
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { messaging = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackPicker(
    label: String,
    tracks: List<JellyfinTrack>,
    includeOff: Boolean,
    modifier: Modifier = Modifier,
    offSelected: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = tracks.firstOrNull { it.selected }?.label ?: if (includeOff && offSelected) "Off" else "—"
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(
                onClick = { if (tracks.isNotEmpty()) open = true },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(current, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (includeOff) {
                    DropdownMenuItem(
                        text = { Text("Off") },
                        trailingIcon = { if (offSelected) Icon(Icons.Filled.Check, null) },
                        onClick = { open = false; onSelect(-1) },
                    )
                }
                tracks.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label) },
                        trailingIcon = { if (t.selected) Icon(Icons.Filled.Check, null) },
                        onClick = { open = false; onSelect(t.index) },
                    )
                }
            }
        }
    }
}

/** Server URL + API key; [onSave] reports an error message or null via its callback. */
@Composable
fun JellyfinSettingsDialog(
    url: String,
    configured: Boolean,
    onSave: (url: String, apiKey: String, done: (String?) -> Unit) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var u by remember { mutableStateOf(url) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jellyfin server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(u, { u = it }, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.10:8096") }, singleLine = true)
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text("API key") },
                    placeholder = { Text(if (configured) "Saved (leave blank to keep)" else "Paste API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "Create a key in Jellyfin: Dashboard → API Keys → +. Used for now playing, seeking and track selection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (configured) {
                    TextButton(onClick = { onRemove(); onDismiss() }) { Text("Remove server", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && u.isNotBlank() && (configured || key.isNotBlank()), onClick = {
                busy = true
                error = null
                onSave(u, key) { err ->
                    busy = false
                    if (err == null) onDismiss() else error = err
                }
            }) { Text(if (busy) "Connecting…" else "Test & save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
