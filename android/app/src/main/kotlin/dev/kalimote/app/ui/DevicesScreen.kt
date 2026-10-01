package dev.kalimote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.app.PairingUi
import dev.kalimote.app.TvDevice
import dev.kalimote.app.UiState
import dev.kalimote.atvremote.RemoteState

class DeviceActions(
    val select: (TvDevice) -> Unit,
    val pair: (TvDevice) -> Unit,
    val add: (host: String, name: String?) -> Unit,
    /** Returns an error message, or null when saved. */
    val edit: (TvDevice, name: String, mac: String) -> String?,
    val remove: (TvDevice) -> Unit,
    val rescan: () -> Unit,
    val setVolumeKeys: (Boolean) -> Unit,
)

@Composable
fun DevicesScreen(state: UiState, actions: DeviceActions, modifier: Modifier = Modifier) {
    var host by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Your TVs")
        if (state.devices.isEmpty()) {
            Text(
                "No TVs yet. Pick one found on your network or add its IP address.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.devices.forEach { d ->
            DeviceRow(d, state, actions)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Found on your network", Modifier.weight(1f))
            IconButton(onClick = actions.rescan) { Icon(Icons.Filled.Refresh, "Rescan") }
        }
        if (state.newDiscovered.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Searching… (phone and TV must be on the same Wi-Fi)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.newDiscovered.forEach { tv ->
            Card(onClick = { actions.add(tv.host, tv.name) }) {
                Icon(Icons.Filled.Tv, null)
                Column(Modifier.weight(1f)) {
                    Text(tv.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tv.host, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Button(onClick = { actions.add(tv.host, tv.name) }) { Text("Pair") }
            }
        }

        SectionTitle("Add by IP address")
        OutlinedTextField(
            host,
            { host = it.trim() },
            label = { Text("IP address") },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            name,
            { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = host.isNotBlank(),
            onClick = {
                actions.add(host, name)
                host = ""
                name = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add and pair") }
        Text(
            "Find the IP address on the TV under Settings → Network & Internet. " +
                "The TV's remote service is on by default on Google TV and Android TV devices with Google services.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Settings")
        Card(onClick = { actions.setVolumeKeys(!state.volumeKeys) }) {
            Column(Modifier.weight(1f)) {
                Text("Phone volume buttons control the TV", fontWeight = FontWeight.SemiBold)
                Text("While the app is open and connected", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Switch(checked = state.volumeKeys, onCheckedChange = actions.setVolumeKeys)
        }
    }
}

@Composable
private fun DeviceRow(d: TvDevice, state: UiState, actions: DeviceActions) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val isSelected = d.id == state.selectedId
    val status = when {
        !d.paired -> "Not paired"
        !isSelected -> "Paired"
        state.remote.connected -> "Connected"
        state.remote.status == RemoteState.Status.CONNECTING -> "Connecting…"
        else -> "Offline"
    }
    Card(onClick = { if (d.paired) actions.select(d) else actions.pair(d) }, highlighted = isSelected) {
        StatusDot(
            when {
                isSelected && state.remote.connected -> Ok
                !d.paired -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> Warn
            },
        )
        Column(Modifier.weight(1f)) {
            Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${d.host} · $status", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        if (!d.paired) Button(onClick = { actions.pair(d) }) { Text("Pair") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menu = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Pair again") },
                    leadingIcon = { Icon(Icons.Filled.Link, null) },
                    onClick = { menu = false; actions.pair(d) },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; confirmRemove = true },
                )
            }
        }
    }
    if (renaming) {
        var value by remember { mutableStateOf(d.name) }
        var mac by remember { mutableStateOf(d.mac ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Edit TV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value, { value = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        mac,
                        { mac = it },
                        label = { Text("MAC address (Wake-on-LAN)") },
                        placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                        singleLine = true,
                    )
                    Text(
                        "Lets the power button turn on a TV that drops off the network when off. " +
                            "Find it on the TV under Settings → Network → About (Wi-Fi or Ethernet MAC).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    error = actions.edit(d, value, mac)
                    if (error == null) renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${d.name}?") },
            text = { Text("You will need to pair again to control it.") },
            confirmButton = { TextButton(onClick = { actions.remove(d); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PairingDialog(pairing: PairingUi, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable(pairing.device.id) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val ready = pairing.phase == PairingUi.Phase.ENTER_CODE
    LaunchedEffect(ready) { if (ready) runCatching { focus.requestFocus() } }
    LaunchedEffect(pairing.error) { if (pairing.error != null) code = "" }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair with ${pairing.device.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (pairing.phase) {
                    PairingUi.Phase.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting to TV…")
                    }
                    else -> Text("Enter the code shown on your TV.")
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                    enabled = ready,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        letterSpacing = 8.sp,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                pairing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = ready && code.length == 6, onClick = { onSubmit(code) }) {
                if (pairing.phase == PairingUi.Phase.VERIFYING) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Pair")
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 12.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Surface(color = color, shape = CircleShape, modifier = modifier.size(10.dp)) {}
}

@Composable
private fun Card(
    onClick: () -> Unit,
    highlighted: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
