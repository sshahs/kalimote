package dev.kalimote.app.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.app.InstallUi
import dev.kalimote.atvremote.TvApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Picks an app to open or pin: the TV's installed apps on Fire TV, or a
 * catalog of popular apps on Google TV (whose remote protocol can't list apps).
 */
@Composable
fun AppPickerDialog(
    load: suspend () -> Pair<Boolean, List<TvApp>>,
    onOpen: (TvApp) -> Unit,
    onAdd: (TvApp) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    var apps by remember { mutableStateOf<List<TvApp>?>(null) }
    var installed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { load() }
            .onSuccess { (fromTv, list) ->
                installed = fromTv
                apps = list
            }
            .onFailure { failure = it.message ?: "Could not list apps" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (installed) "Installed on the TV" else "Popular apps. Open one, or add it to your shortcuts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = apps
                when {
                    failure != null -> Text(failure!!, color = MaterialTheme.colorScheme.error)
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        val q = filter.trim().lowercase(Locale.ROOT)
                        val shown = list.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) || it.pkg.contains(q) }
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown) { app ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            app.pkg,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    TextButton(onClick = { onOpen(app) }) { Text("Open") }
                                    TextButton(onClick = { onAdd(app) }) { Text("Add") }
                                }
                                HorizontalDivider()
                            }
                            if (shown.isEmpty()) item { Text("No matching apps", Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onCustom) { Text("Custom link…") } },
    )
}

/** Fire TV only: screenshot and APK install. */
@Composable
fun FireTvTools(
    screenshot: suspend () -> ByteArray,
    install: (android.net.Uri) -> Unit,
) {
    var showShot by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) install(uri)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Fire TV")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showShot = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Screenshot, null)
                Text("  Screenshot")
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.InstallMobile, null)
                Text("  Install APK")
            }
        }
    }
    if (showShot) ScreenshotDialog(screenshot) { showShot = false }
}

@Composable
private fun ScreenshotDialog(capture: suspend () -> ByteArray, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var png by remember { mutableStateOf<ByteArray?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }

    suspend fun take() {
        loading = true
        runCatching { capture() }
            .onSuccess { bytes ->
                png = bytes
                image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                failure = null
            }
            .onFailure { failure = it.message ?: "Screenshot failed" }
        loading = false
    }

    LaunchedEffect(request) { take() }
    LaunchedEffect(live) {
        while (live) {
            delay(2000)
            take()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TV screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = image
                    if (img != null) Image(img, "TV screenshot", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    if (img == null && loading) CircularProgressIndicator()
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { live = !live }) {
                    Checkbox(checked = live, onCheckedChange = { live = it })
                    Text("Live (every 2 s)")
                }
            }
        },
        confirmButton = {
            Row {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && png != null) {
                    TextButton(onClick = {
                        val bytes = png ?: return@TextButton
                        scope.launch {
                            val ok = runCatching {
                                val values = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, "kalimote-${System.currentTimeMillis()}.png")
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kalimote")
                                }
                                val resolver = context.contentResolver
                                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
                            }.isSuccess
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Saved to Pictures/Kalimote" else "Could not save the screenshot",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) { Text("Save") }
                }
                TextButton(enabled = !loading, onClick = { request++ }) { Text("Refresh") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** Progress of an APK upload and install. */
@Composable
fun InstallDialog(install: InstallUi, onDismiss: () -> Unit) {
    val finished = install.done || install.error != null
    AlertDialog(
        onDismissRequest = { if (finished) onDismiss() },
        title = { Text(install.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    install.error != null -> Text(install.error, color = MaterialTheme.colorScheme.error)
                    install.done -> Text("Installed. Find it in the TV's apps.")
                    install.installing -> {
                        Text("Installing on the TV…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        val mb = { b: Long -> String.format(Locale.ROOT, "%.1f MB", b / 1_048_576.0) }
                        Text(if (install.total > 0) "Uploading ${mb(install.sent)} of ${mb(install.total)}" else "Uploading ${mb(install.sent)}")
                        if (install.total > 0) {
                            LinearProgressIndicator(
                                progress = { (install.sent.toFloat() / install.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (finished) Button(onClick = onDismiss) { Text("OK") }
        },
    )
}
