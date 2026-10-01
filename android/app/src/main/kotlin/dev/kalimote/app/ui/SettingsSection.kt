package dev.kalimote.app.ui

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kalimote.app.PlayPauseTileService
import dev.kalimote.app.PowerTileService
import dev.kalimote.app.R
import dev.kalimote.app.RemoteWidget

/** Widget, Quick Settings tiles and launcher shortcuts: what they are and one-tap setup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickControlsCard(toast: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Control the TV without opening the app: a home-screen widget, Quick Settings tiles " +
                "(power, play/pause), and long-press shortcuts on the app icon (power, play/pause and your first macros). " +
                "They act on the TV selected here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { if (!pinWidget(context)) toast("Long-press your home screen → Widgets → Kalimote") }) {
                Text("Add widget")
            }
            FilledTonalButton(onClick = {
                if (!addTile(context, PowerTileService::class.java, R.string.power, R.drawable.ic_qs_power)) {
                    toast("Edit your Quick Settings panel and drag in “TV power”")
                }
            }) { Text("Add power tile") }
            FilledTonalButton(onClick = {
                if (!addTile(context, PlayPauseTileService::class.java, R.string.play_pause, R.drawable.ic_qs_play_pause)) {
                    toast("Edit your Quick Settings panel and drag in “Play / Pause”")
                }
            }) { Text("Add play/pause tile") }
        }
    }
}

private fun pinWidget(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported) return false
    return manager.requestPinAppWidget(ComponentName(context, RemoteWidget::class.java), null, null)
}

private fun addTile(context: Context, service: Class<*>, label: Int, icon: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return false
    sbm.requestAddTileService(
        ComponentName(context, service),
        context.getString(label),
        Icon.createWithResource(context, icon),
        context.mainExecutor,
    ) {}
    return true
}

/** Export / import macros and app shortcuts as JSON; also accepts the web server's macros list. */
@Composable
fun BackupCard(export: () -> String, import: (String) -> String, toast: (String) -> Unit) {
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Move macros and app shortcuts between phones. The macros list from the web server (GET /api/devices) imports too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                val json = export()
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Kalimote backup")
                    .putExtra(Intent.EXTRA_TEXT, json)
                context.startActivity(Intent.createChooser(share, "Export Kalimote backup"))
            }) { Text("Export") }
            FilledTonalButton(onClick = { importing = true }) { Text("Import") }
        }
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste a Kalimote backup. Macros and shortcuts with the same name are replaced.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        text,
                        { text = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
                    )
                    TextButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: text
                    }) { Text("Paste from clipboard") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = text.isNotBlank(), onClick = {
                    val result = import(text)
                    if (result.startsWith("Imported")) {
                        importing = false
                        toast(result)
                    } else {
                        error = result
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
}
