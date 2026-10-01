package dev.kalimote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kalimote.atvremote.Direction
import dev.kalimote.atvremote.KeyCodes
import kotlin.math.abs

/**
 * Press handling for remote buttons. With [repeat] (D-pad, volume) it fires on
 * touch-down and keeps firing while held; otherwise it fires on release.
 */
fun Modifier.remotePress(
    label: String,
    repeat: Boolean = false,
    onPressedChange: (Boolean) -> Unit = {},
    onPress: () -> Unit,
): Modifier = this
    .semantics {
        role = Role.Button
        contentDescription = label
        onClick { onPress(); true }
    }
    .pointerInput(repeat) {
        awaitEachGesture {
            awaitFirstDown()
            onPressedChange(true)
            if (repeat) {
                onPress()
                // Fire again every 110ms after an initial 400ms, until released.
                var wait = 400L
                while (withTimeoutOrNull(wait) { waitForUpOrCancellation(); true } == null) {
                    onPress()
                    wait = 110L
                }
            } else if (waitForUpOrCancellation() != null) {
                // Fire on release so a scroll that starts on a button sends nothing.
                onPress()
            }
            onPressedChange(false)
        }
    }

@Composable
fun RemoteButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    icon2: ImageVector? = null,
    text: String? = null,
    shape: Shape = CircleShape,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    repeat: Boolean = false,
    iconSize: Dp = 24.dp,
    onPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .scale(if (pressed) 0.95f else 1f)
            .clip(shape)
            .background(if (pressed) container.copy(alpha = 0.7f) else container)
            .remotePress(label, repeat, { pressed = it }) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                press()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
            if (icon2 != null) Icon(icon2, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
        }
        if (text != null) Text(text, color = content, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun DPad(modifier: Modifier = Modifier, onKey: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val send by rememberUpdatedState(onKey)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        val size = maxWidth
        val arm = size * 0.32f
        @Composable
        fun Arrow(icon: ImageVector, label: String, key: Int, align: Alignment) {
            var pressed by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .align(align)
                    .size(arm)
                    .clip(CircleShape)
                    .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .remotePress(label, repeat = true, onPressedChange = { pressed = it }) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        send(key)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Arrow(Icons.Filled.KeyboardArrowUp, "Up", KeyCodes.DPAD_UP, Alignment.TopCenter)
        Arrow(Icons.Filled.KeyboardArrowDown, "Down", KeyCodes.DPAD_DOWN, Alignment.BottomCenter)
        Arrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", KeyCodes.DPAD_LEFT, Alignment.CenterStart)
        Arrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", KeyCodes.DPAD_RIGHT, Alignment.CenterEnd)
        RemoteButton(
            label = "OK",
            text = "OK",
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.align(Alignment.Center).size(size * 0.38f),
        ) { send(KeyCodes.DPAD_CENTER) }
    }
}

/** Swipe to move, tap to select, long-press for a long OK press. */
@Composable
fun Touchpad(modifier: Modifier = Modifier, onKey: (Int, Direction) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val send by rememberUpdatedState(onKey)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(Unit) {
                val step = 42.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var origin: Offset = down.position
                    var moved = false
                    var longPressed = false
                    while (true) {
                        val event = if (!moved && !longPressed) {
                            // A finger held still produces no events, so time out to detect it.
                            withTimeoutOrNull(600) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            longPressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            send(KeyCodes.DPAD_CENTER, Direction.START_LONG)
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.position - origin
                        if (!longPressed && maxOf(abs(d.x), abs(d.y)) >= step) {
                            moved = true
                            val key = if (abs(d.x) > abs(d.y)) {
                                if (d.x > 0) KeyCodes.DPAD_RIGHT else KeyCodes.DPAD_LEFT
                            } else {
                                if (d.y > 0) KeyCodes.DPAD_DOWN else KeyCodes.DPAD_UP
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            send(key, Direction.SHORT)
                            origin = change.position
                        }
                        change.consume()
                    }
                    when {
                        longPressed -> send(KeyCodes.DPAD_CENTER, Direction.END_LONG)
                        !moved -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            send(KeyCodes.DPAD_CENTER, Direction.SHORT)
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            "Swipe to move · Tap to select · Hold for options",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}
