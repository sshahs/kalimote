package dev.kalimote.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kalimote.atvremote.JellyfinLibraryItem
import dev.kalimote.atvremote.JellyfinSection

/** One screen of the library browser. */
private data class LibraryPage(
    val title: String,
    val view: String,
    val query: String? = null,
    val item: JellyfinLibraryItem? = null,
)

/**
 * Full-screen Jellyfin library: continue watching, latest and libraries;
 * folders and series open, anything playable starts on the TV (opening
 * Jellyfin there first when needed).
 */
@Composable
fun JellyfinLibraryDialog(
    browse: suspend (view: String, query: String?, item: JellyfinLibraryItem?) -> List<JellyfinSection>,
    play: (JellyfinLibraryItem) -> Unit,
    poster: suspend (itemId: String, tag: String?) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    val stack = remember { mutableStateListOf(LibraryPage("Jellyfin", "home")) }
    val page = stack.last()
    var sections by remember { mutableStateOf<List<JellyfinSection>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(page) {
        sections = null
        failure = null
        runCatching { browse(page.view, page.query, page.item) }
            .onSuccess { sections = it }
            .onFailure { failure = it.message ?: "Could not load the library" }
    }

    val back: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) {
                        Icon(if (stack.size > 1) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, "Back")
                    }
                    Text(
                        page.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    query,
                    { query = it },
                    placeholder = { Text("Search movies, shows, episodes") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = query.trim()
                        if (q.isNotEmpty()) stack.add(LibraryPage("“$q”", "search", query = q))
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = sections
                when {
                    failure != null -> Text(failure!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                    list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = JellyfinPurple)
                    }
                    list.all { it.items.isEmpty() } -> Text(
                        "Nothing here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(list.filter { it.items.isNotEmpty() }) { section ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionHeader(section.title)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(section.items) { item ->
                                        LibraryCard(item, poster) {
                                            if (item.browsable) {
                                                stack.add(LibraryPage(item.name, "open", item = item))
                                            } else {
                                                play(item)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(
    item: JellyfinLibraryItem,
    poster: suspend (itemId: String, tag: String?) -> ByteArray?,
    onClick: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, item.id, item.imageTag) {
        value = if (item.imageTag == null) {
            null
        } else {
            poster(item.id, item.imageTag)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Column(
        Modifier.width(112.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(JellyfinPurple, JellyfinBlue))),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img != null) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(if (item.browsable) Icons.Filled.Folder else Icons.Filled.Movie, null, tint = Color.White.copy(alpha = 0.85f))
            }
            val progress = item.progress
            if (progress != null && progress > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((progress / 100.0).toFloat().coerceIn(0f, 1f)).background(JellyfinBlue))
                }
            }
        }
        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = when {
            item.season != null && item.episode != null -> "S${item.season} · E${item.episode}"
            item.year != null -> item.year.toString()
            else -> item.seriesName ?: ""
        }
        if (sub.isNotEmpty()) {
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
