package dev.kalimote.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.app.AppShortcut
import dev.kalimote.app.UiState
import dev.kalimote.atvremote.Direction
import dev.kalimote.atvremote.KeyCodes
import dev.kalimote.atvremote.RemoteState

class RemoteActions(
    val key: (Int, Direction) -> Unit,
    val text: (String) -> Unit,
    val launch: (AppShortcut) -> Unit,
    val addApp: (AppShortcut) -> Unit,
    val removeApp: (AppShortcut) -> Unit,
    val setTouchpad: (Boolean) -> Unit,
    val pair: () -> Unit,
    val reconnect: () -> Unit,
    val openDevices: () -> Unit,
)

@Composable
fun RemoteScreen(state: UiState, actions: RemoteActions, modifier: Modifier = Modifier) {
    val key = { code: Int -> actions.key(code, Direction.SHORT) }
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showAddApp by rememberSaveable { mutableStateOf(false) }
    val connected = state.remote.connected

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        StatusBanner(state, actions)

        Column(
            Modifier.widthIn(max = 420.dp).alpha(if (connected) 1f else 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RemoteButton("Power", Modifier.size(56.dp, 44.dp), icon = Icons.Filled.PowerSettingsNew, content = Danger, shape = RoundedCornerShape(22.dp)) { key(KeyCodes.POWER) }
                RemoteButton("Input", Modifier.size(72.dp, 44.dp), icon = Icons.AutoMirrored.Filled.Input, shape = RoundedCornerShape(22.dp)) { key(KeyCodes.TV_INPUT) }
                RemoteButton("Settings", Modifier.size(56.dp, 44.dp), icon = Icons.Filled.Settings, shape = RoundedCornerShape(22.dp)) { key(KeyCodes.SETTINGS) }
                RemoteButton(
                    if (state.touchpad) "Use D-pad" else "Use touchpad",
                    Modifier.size(56.dp, 44.dp),
                    icon = if (state.touchpad) Icons.Filled.Gamepad else Icons.Filled.TouchApp,
                    shape = RoundedCornerShape(22.dp),
                ) { actions.setTouchpad(!state.touchpad) }
            }

            if (state.touchpad) {
                Touchpad(Modifier.fillMaxWidth(0.85f), actions.key)
            } else {
                DPad(Modifier.fillMaxWidth(0.78f), key)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RemoteButton("Back", Modifier.size(58.dp), icon = Icons.AutoMirrored.Filled.ArrowBack) { key(KeyCodes.BACK) }
                RemoteButton("Home", Modifier.size(58.dp), icon = Icons.Filled.Home) { key(KeyCodes.HOME) }
                RemoteButton("Menu", Modifier.size(58.dp), icon = Icons.Filled.Menu) { key(KeyCodes.MENU) }
                RemoteButton("Assistant", Modifier.size(58.dp), icon = Icons.Filled.Mic) { key(KeyCodes.ASSIST) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Rocker(
                    label = state.remote.volume?.let { if (it.muted) "MUTE" else it.level.toString() } ?: "VOL",
                    up = { RemoteButton("Volume up", Modifier.size(72.dp, 64.dp), text = "+", container = Color.Transparent, repeat = true) { key(KeyCodes.VOLUME_UP) } },
                    down = { RemoteButton("Volume down", Modifier.size(72.dp, 64.dp), text = "−", container = Color.Transparent, repeat = true) { key(KeyCodes.VOLUME_DOWN) } },
                )
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    RemoteButton("Mute", Modifier.size(58.dp), icon = Icons.AutoMirrored.Filled.VolumeOff) { key(KeyCodes.VOLUME_MUTE) }
                    RemoteButton("Keyboard", Modifier.size(58.dp), icon = Icons.Filled.Keyboard) { showKeyboard = true }
                }
                Rocker(
                    label = "CH",
                    up = { RemoteButton("Channel up", Modifier.size(72.dp, 64.dp), icon = Icons.Filled.KeyboardArrowUp, container = Color.Transparent) { key(KeyCodes.CHANNEL_UP) } },
                    down = { RemoteButton("Channel down", Modifier.size(72.dp, 64.dp), icon = Icons.Filled.KeyboardArrowDown, container = Color.Transparent) { key(KeyCodes.CHANNEL_DOWN) } },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(14.dp)
                val m = Modifier.weight(1f).height(50.dp)
                RemoteButton("Rewind", m, icon = Icons.Filled.FastRewind, shape = shape) { key(KeyCodes.MEDIA_REWIND) }
                RemoteButton("Previous", m, icon = Icons.Filled.SkipPrevious, shape = shape) { key(KeyCodes.MEDIA_PREVIOUS) }
                RemoteButton(
                    "Play/Pause", m, icon = Icons.Filled.PlayArrow, icon2 = Icons.Filled.Pause,
                    shape = shape, container = Accent, content = Color.White, iconSize = 20.dp,
                ) { key(KeyCodes.MEDIA_PLAY_PAUSE) }
                RemoteButton("Next", m, icon = Icons.Filled.SkipNext, shape = shape) { key(KeyCodes.MEDIA_NEXT) }
                RemoteButton("Fast forward", m, icon = Icons.Filled.FastForward, shape = shape) { key(KeyCodes.MEDIA_FAST_FORWARD) }
            }

            MoreButtons(expanded = showMore, onToggle = { showMore = !showMore }, key = key)

            Apps(state, actions, onAdd = { showAddApp = true })
        }
    }

    if (showKeyboard) KeyboardDialog(onDismiss = { showKeyboard = false }, onText = actions.text, onKey = key)
    if (showAddApp) {
        AddAppDialog(onDismiss = { showAddApp = false }) {
            actions.addApp(it)
            showAddApp = false
        }
    }
}

@Composable
private fun StatusBanner(state: UiState, actions: RemoteActions) {
    val device = state.selected
    val (text, button) = when {
        device == null -> "Add your TV to get started." to ("Add TV" to actions.openDevices)
        !device.paired || state.remote.status == RemoteState.Status.UNPAIRED ->
            "${device.name} needs to be paired." to ("Pair" to actions.pair)
        state.remote.connected -> return
        state.remote.error != null -> "${device.name}: ${state.remote.error}" to ("Retry" to actions.reconnect)
        else -> "Connecting to ${device.name}…" to null
    }
    Surface(
        color = Warn.copy(alpha = 0.18f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (button != null) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = button.second) { Text(button.first) }
            }
        }
    }
}

@Composable
private fun Rocker(label: String, up: @Composable () -> Unit, down: @Composable () -> Unit) {
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        up()
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        down()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreButtons(expanded: Boolean, onToggle: () -> Unit, key: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle) {
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text("More buttons")
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cell = RoundedCornerShape(12.dp)
                val rows = listOf(
                    listOf("1" to KeyCodes.digit(1), "2" to KeyCodes.digit(2), "3" to KeyCodes.digit(3)),
                    listOf("4" to KeyCodes.digit(4), "5" to KeyCodes.digit(5), "6" to KeyCodes.digit(6)),
                    listOf("7" to KeyCodes.digit(7), "8" to KeyCodes.digit(8), "9" to KeyCodes.digit(9)),
                    listOf("Info" to KeyCodes.INFO, "0" to KeyCodes.digit(0), "Guide" to KeyCodes.GUIDE),
                )
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, code) ->
                            RemoteButton(label, Modifier.weight(1f).height(48.dp), text = label, shape = cell) { key(code) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Color(0xFFE5484D) to KeyCodes.PROG_RED,
                        Color(0xFF30A46C) to KeyCodes.PROG_GREEN,
                        Color(0xFFF5D90A) to KeyCodes.PROG_YELLOW,
                        Color(0xFF0090FF) to KeyCodes.PROG_BLUE,
                    ).forEach { (color, code) ->
                        RemoteButton("Color button", Modifier.weight(1f).height(26.dp), container = color, shape = RoundedCornerShape(8.dp)) { key(code) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Captions" to KeyCodes.CAPTIONS, "Stop" to KeyCodes.MEDIA_STOP, "Search" to KeyCodes.SEARCH).forEach { (label, code) ->
                        RemoteButton(label, Modifier.size(96.dp, 44.dp), text = label, shape = RoundedCornerShape(22.dp)) { key(code) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Apps(state: UiState, actions: RemoteActions, onAdd: () -> Unit) {
    var removing by remember { mutableStateOf<AppShortcut?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "APPS",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val app = state.remote.currentApp
            if (state.remote.connected && app != null) {
                Text(
                    prettyApp(app) + if (state.remote.powered == false) " · Off" else "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val items = state.apps.map { it as AppShortcut? } + null
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { app ->
                    val m = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                    if (app == null) {
                        Box(
                            m.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                                .combinedClickable(onClick = onAdd),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Add app", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        Box(
                            m.background(MaterialTheme.colorScheme.surfaceContainer)
                                .combinedClickable(onClick = { actions.launch(app) }, onLongClick = { removing = app }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(app.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    removing?.let { app ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${app.name}?") },
            text = { Text(app.url) },
            confirmButton = { TextButton(onClick = { actions.removeApp(app); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyboardDialog(onDismiss: () -> Unit, onText: (String) -> Unit, onKey: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val send = {
        if (text.isNotEmpty()) {
            onText(text)
            text = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Focus a text field on the TV first (e.g. search).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Type here…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteButton("Delete", Modifier.size(56.dp, 40.dp), icon = Icons.AutoMirrored.Filled.Backspace, shape = RoundedCornerShape(12.dp), repeat = true) { onKey(KeyCodes.DEL) }
                    RemoteButton("Enter", Modifier.size(56.dp, 40.dp), icon = Icons.AutoMirrored.Filled.KeyboardReturn, shape = RoundedCornerShape(12.dp)) { onKey(KeyCodes.ENTER) }
                }
            }
        },
        confirmButton = { Button(onClick = send) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddAppDialog(onDismiss: () -> Unit, onAdd: (AppShortcut) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("market://launch?id=") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add app shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("App link") }, singleLine = true)
                Text(
                    "Use market://launch?id=<package> for any installed app, or a deep link such as https://www.youtube.com.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && url.contains(":"), onClick = { onAdd(AppShortcut(name.trim(), url.trim())) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun prettyApp(pkg: String): String = when (pkg) {
    "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx" -> "Home"
    "com.google.android.youtube.tv" -> "YouTube"
    "com.netflix.ninja" -> "Netflix"
    "com.amazon.amazonvideo.livingroom" -> "Prime Video"
    "com.disney.disneyplus" -> "Disney+"
    "com.spotify.tv.android" -> "Spotify"
    "com.plexapp.android" -> "Plex"
    else -> pkg
}
