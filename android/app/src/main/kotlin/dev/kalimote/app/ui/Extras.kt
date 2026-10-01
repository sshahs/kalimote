package dev.kalimote.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.app.SavedMacro
import dev.kalimote.app.UiState
import dev.kalimote.atvremote.Volume
import kotlin.math.roundToInt

@Composable
fun SectionHeader(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Paste a YouTube / Netflix / any link and open it on the TV. */
@Composable
fun OpenLinkRow(onOpen: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (url.isNotBlank()) {
            onOpen(url)
            url = ""
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Link, null) },
            placeholder = { Text("Paste a link to open on TV", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = submit, enabled = url.isNotBlank()) { Text("Open") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MacrosSection(
    state: UiState,
    onRun: (SavedMacro) -> Unit,
    onStop: () -> Unit,
    onEdit: (SavedMacro?) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Macros", state.runningMacro?.let { "Running “$it”…" })
        val items: List<SavedMacro?> = state.macros + null
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { macro ->
                    val shape = RoundedCornerShape(14.dp)
                    val running = macro != null && macro.name == state.runningMacro
                    var m = Modifier.weight(1f).height(52.dp).clip(shape)
                    if (running) m = m.border(2.dp, Accent, shape)
                    Box(
                        m.background(
                            if (macro == null) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ).combinedClickable(
                            onClick = { if (macro == null) onEdit(null) else if (running) onStop() else onRun(macro) },
                            onLongClick = { if (macro != null) onEdit(macro) },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                            val icon = when {
                                macro == null -> Icons.Filled.Add
                                running -> Icons.Filled.Stop
                                else -> Icons.Filled.PlayArrow
                            }
                            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                macro?.name ?: "New macro",
                                fontSize = 13.sp,
                                fontWeight = if (macro != null) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (macro == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val MACRO_HELP =
    "One step per line (or comma separated):\n" +
        "HOME — press a key\n" +
        "DPAD_DOWN x3 — repeat\n" +
        "hold DPAD_CENTER 1s — long press\n" +
        "wait 500  /  wait 2s\n" +
        "text hello — type into a text field\n" +
        "open https://… — open a link\n\n" +
        "Keys: HOME BACK MENU DPAD_UP/DOWN/LEFT/RIGHT/CENTER VOLUME_UP/DOWN VOLUME_MUTE POWER " +
        "MEDIA_PLAY_PAUSE MEDIA_NEXT CHANNEL_UP DIGIT_0–9 SETTINGS TV_INPUT SEARCH ASSIST…"

/**
 * Create or edit a macro. [onSave] and [onTry] return an error message or null.
 */
@Composable
fun MacroDialog(
    macro: SavedMacro?,
    onSave: (name: String, script: String) -> String?,
    onTry: (script: String) -> String?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(macro?.name ?: "") }
    var script by rememberSaveable { mutableStateOf(macro?.script ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (macro == null) "New macro" else "Edit macro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    script,
                    { script = it },
                    label = { Text("Steps") },
                    placeholder = { Text("HOME\nwait 1s\nDPAD_DOWN x2\nDPAD_CENTER") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide syntax" else "Syntax help") }
                if (showHelp) Text(MACRO_HELP, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { error = onTry(script) }) { Text("Try it") }
                    if (macro != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { Button(onClick = { error = onSave(name, script) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SleepDialog(sleepAt: Long, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    val left = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60_000.0).roundToInt().coerceAtLeast(0) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (left != null) "TV turns off in about $left min. Change it:" else "Turn the TV off after…")
                listOf(listOf(15, 30, 45), listOf(60, 90, 120)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            FilledTonalButton(onClick = { onSet(m); onDismiss() }, modifier = Modifier.weight(1f)) {
                                Text(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60}½ h")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (left != null) TextButton(onClick = { onSet(0); onDismiss() }) { Text("Turn off timer") }
        },
    )
}

@Composable
fun VolumeDialog(volume: Volume, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    val max = if (volume.max > 0) volume.max else 100
    var value by remember { mutableFloatStateOf(volume.level.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Volume ${value.roundToInt()}") },
        text = {
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onSet(value.roundToInt()) },
                valueRange = 0f..max.toFloat(),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
