package dev.kalimote.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kalimote.atvremote.KeyCodes
import dev.kalimote.atvremote.TextField
import kotlinx.coroutines.delay

/**
 * Keyboard for the TV, docked above the phone's keyboard. Every edit is
 * mirrored live into the TV's focused text box (no "Send" step), so what you
 * see here is what the TV has. Search/Enter submits on the TV.
 *
 * [field] is the text box the TV reported, if any: a new one (the TV asking
 * for text again) restarts the session from its content.
 */
@Composable
fun KeyboardSheet(
    field: TextField?,
    connected: Boolean,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
    onKey: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    fun at(text: String) = TextFieldValue(text, TextRange(text.length))
    var value by remember { mutableStateOf(at(field?.value.orEmpty())) }
    var session by remember { mutableStateOf(field?.id) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // No text box reported: start from empty rather than stale text.
        if (field == null) onReset()
        // Focus once the dialog window is attached, else the keyboard may not show.
        delay(120)
        focus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(field?.id) {
        if (field != null && field.id != session) {
            session = field.id
            value = at(field.value)
        }
    }

    val update = { next: TextFieldValue ->
        val changed = next.text != value.text
        value = next
        if (changed) onChange(next.text)
    }
    val submit = {
        onKey(KeyCodes.ENTER)
        onDismiss()
    }
    val backspace = {
        // Delete here (and so on the TV); with nothing here, send the key itself.
        if (value.text.isEmpty()) onKey(KeyCodes.DEL) else update(at(value.text.dropLast(1)))
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val text = value.text
            update(at(if (text.isEmpty() || text.endsWith(" ")) text + spoken else "$text $spoken"))
        }
    }
    val listen = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV")
        try {
            voice.launch(intent)
            voiceError = null
        } catch (_: ActivityNotFoundException) {
            voiceError = "No speech recognition available on this phone"
        }
        Unit
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Full-window layer: tap above the sheet to close; the sheet rides on the keyboard.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow taps so they don't reach the dismiss layer.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (!connected) MaterialTheme.colorScheme.error else if (field != null) Ok else Warn)
                        Text(
                            when {
                                !connected -> "TV is not connected"
                                field != null -> "Typing live into " + field.label.ifBlank { "the TV's text box" }
                                else -> "Select a text box on the TV, then type here"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Close keyboard") }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = update,
                        singleLine = true,
                        placeholder = { Text(field?.label?.ifBlank { null } ?: "Type to the TV…") },
                        trailingIcon = {
                            if (value.text.isNotEmpty()) {
                                IconButton(onClick = { update(at("")) }) { Icon(Icons.Filled.Close, "Clear") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }, onGo = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onPreviewKeyEvent { e ->
                                // Backspace with nothing left here still deletes on the TV.
                                if (e.key == Key.Backspace && e.type == KeyEventType.KeyDown && value.text.isEmpty()) {
                                    onKey(KeyCodes.DEL)
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    // Move between suggestions and results without closing the keyboard.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val m = Modifier.weight(1f).height(44.dp)
                        val shape = RoundedCornerShape(12.dp)
                        RemoteButton("Left", m, icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, shape = shape, repeat = true) { onKey(KeyCodes.DPAD_LEFT) }
                        RemoteButton("Up", m, icon = Icons.Filled.KeyboardArrowUp, shape = shape, repeat = true) { onKey(KeyCodes.DPAD_UP) }
                        RemoteButton("Down", m, icon = Icons.Filled.KeyboardArrowDown, shape = shape, repeat = true) { onKey(KeyCodes.DPAD_DOWN) }
                        RemoteButton("Right", m, icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, shape = shape, repeat = true) { onKey(KeyCodes.DPAD_RIGHT) }
                        RemoteButton("OK", m, text = "OK", shape = shape) { onKey(KeyCodes.DPAD_CENTER) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val shape = RoundedCornerShape(12.dp)
                        RemoteButton("Delete", Modifier.weight(1f).height(44.dp), icon = Icons.AutoMirrored.Filled.Backspace, shape = shape, repeat = true) { backspace() }
                        RemoteButton("Voice typing", Modifier.weight(1f).height(44.dp), icon = Icons.Filled.Mic, shape = shape) { listen() }
                        RemoteButton(
                            "Search",
                            Modifier.weight(2f).height(44.dp),
                            icon = Icons.Filled.Search,
                            shape = shape,
                            container = Accent,
                            content = Color.White,
                        ) { submit() }
                    }
                    voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            }
        }
    }
}
