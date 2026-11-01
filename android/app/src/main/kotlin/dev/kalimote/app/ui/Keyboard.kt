package dev.kalimote.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.atvremote.KeyCodes
import dev.kalimote.atvremote.TextField as TvTextField
import kotlinx.coroutines.delay

/**
 * Typing bar docked on top of the phone's keyboard, with the remote still
 * visible above it. Every edit goes to the TV as you type; the keyboard's
 * Search/Enter key submits on the TV and closes the bar.
 *
 * [field] is the text box the TV reported, if any. A new one (the TV asking
 * for text again) restarts from its content.
 */
@Composable
fun TypingBar(
    field: TvTextField?,
    connected: Boolean,
    fireTv: Boolean,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
    onKey: (Int) -> Unit,
    onClose: () -> Unit,
) {
    fun at(text: String) = TextFieldValue(text, TextRange(text.length))
    var value by remember { mutableStateOf(at(field?.value.orEmpty())) }
    var session by remember { mutableStateOf(field?.id) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // No text box reported: start from empty rather than stale text.
        if (field == null) onReset()
        delay(50)
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
        onClose()
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val text = value.text
            update(at(if (text.isEmpty() || text.endsWith(" ")) text + spoken else "$text $spoken"))
        }
    }
    var voiceError by remember { mutableStateOf<String?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (connected) Ok else MaterialTheme.colorScheme.error, Modifier.padding(start = 4.dp))
                Text(
                    voiceError ?: when {
                        !connected -> "TV is not connected"
                        field != null -> "Typing into " + field.label.ifBlank { "the TV" }
                        fireTv -> "Typing on the TV"
                        else -> "Typing on the TV · lower case, letters, digits and . , - @ /"
                    },
                    fontSize = 12.sp,
                    color = if (voiceError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardHide, "Close keyboard", Modifier.size(20.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextField(
                    value = value,
                    onValueChange = update,
                    singleLine = true,
                    placeholder = { Text(field?.label?.ifBlank { null } ?: "Type on the TV…") },
                    trailingIcon = {
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { update(at("")) }) { Icon(Icons.Filled.Close, "Clear") }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }, onGo = { submit() }),
                    modifier = Modifier
                        .weight(1f)
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
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV")
                    try {
                        voice.launch(intent)
                        voiceError = null
                    } catch (_: ActivityNotFoundException) {
                        voiceError = "No speech recognition available on this phone"
                    }
                }) { Icon(Icons.Filled.Mic, "Voice typing") }
                FilledIconButton(
                    onClick = submit,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter on TV") }
            }
        }
    }
}
