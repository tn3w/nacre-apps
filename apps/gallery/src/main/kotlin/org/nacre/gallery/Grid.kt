package org.nacre.gallery

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.nacre.core.Palette

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    sections: List<Section>,
    columns: Int,
    selected: Set<Uri>,
    empty: String,
    onOpen: (Media) -> Unit,
    onSelect: (Set<Uri>) -> Unit,
    onColumns: (Int) -> Unit,
    caption: ((Media) -> String)? = null,
    header: (LazyGridScope.() -> Unit)? = null
) {
    if (sections.all { it.items.isEmpty() }) return Empty(empty)
    val currentColumns by rememberUpdatedState(columns)
    val selecting = selected.isNotEmpty()
    val state = rememberLazyGridState()

    LazyVerticalGrid(
        GridCells.Fixed(columns),
        Modifier.fillMaxSize().pointerInput(Unit) {
            pinch { onColumns((currentColumns + it).coerceIn(Settings.COLUMNS)) }
        },
        state = state,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        header?.invoke(this)
        sections.forEach { section ->
            section.title?.let { title ->
                item(key = "header:$title", span = { GridItemSpan(maxLineSpan) }) {
                    val uris = section.items.map { it.uri }.toSet()
                    Text(
                        title,
                        Modifier
                            .fillMaxWidth()
                            .clickable(selecting) {
                                val all = selected.containsAll(uris)
                                onSelect(if (all) selected - uris else selected + uris)
                            }.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            items(section.items, key = { it.uri }) { media ->
                val checked = media.uri in selected
                Cell(
                    media,
                    checked,
                    selecting,
                    caption?.invoke(media),
                    Modifier.combinedClickable(
                        onClick = {
                            when {
                                !selecting -> onOpen(media)
                                checked -> onSelect(selected - media.uri)
                                else -> onSelect(selected + media.uri)
                            }
                        },
                        onLongClick = { onSelect(selected + media.uri) }
                    )
                )
            }
        }
    }
}

private suspend fun PointerInputScope.pinch(onStep: (Int) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } < 2) continue
            zoom *= event.calculateZoom()
            event.changes.forEach { it.consume() }
            if (zoom > 1.35f || zoom < 0.75f) {
                onStep(if (zoom > 1f) -1 else 1)
                zoom = 1f
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun Cell(
    media: Media,
    checked: Boolean,
    selecting: Boolean,
    caption: String?,
    modifier: Modifier
) {
    val inset = if (checked) 10.dp else 1.dp
    Box(modifier.aspectRatio(1f).padding(1.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(inset - 1.dp)
                .clip(RoundedCornerShape(if (checked) 12.dp else 2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            rememberThumbnail(media.uri, 256)?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            val label =
                caption ?: if (media.video) formatDuration(media.duration) else null
            Row(
                Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (media.favorite) Badge(R.drawable.ic_favorite)
                Box(Modifier.weight(1f))
                if (label != null) {
                    Text(
                        label,
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Palette.Ink.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp),
                        color = Palette.Paper,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (selecting) {
            Box(
                Modifier
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Palette.Ink.copy(
                                alpha = 0.3f
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(icon: Int) {
    Icon(painterResource(icon), null, Modifier.size(16.dp), tint = Palette.Paper)
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun AlbumGrid(albums: List<Album>, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) return Empty("No albums yet")
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onOpen(album) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    rememberThumbnail(album.items.first().uri, 384)?.let {
                        Image(
                            it,
                            null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Text(
                    album.name,
                    Modifier.padding(start = 4.dp, top = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${album.items.size}",
                    Modifier.padding(start = 4.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(settings: Settings, albums: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            if (albums) {
                Options("Sort albums by", AlbumSort.entries, settings.albumSort, {
                    it.name
                }) {
                    Store.update { copy(albumSort = it) }
                }
                return@Column
            }
            Options("Sort by", Sort.entries, settings.sort, { it.label }) {
                Store.update { copy(sort = it) }
            }
            val order =
                if (settings.sort.dated) {
                    listOf("Newest first", "Oldest first")
                } else {
                    listOf("Descending", "Ascending")
                }
            Options("Order", listOf(true, false), settings.descending, {
                order[if (it) 0 else 1]
            }) {
                Store.update { copy(descending = it) }
            }
            if (settings.sort.dated) {
                Options("Group by", Group.entries, settings.group, { it.name }) {
                    Store.update { copy(group = it) }
                }
            }
            Options("Show", Kind.entries, settings.kind, { it.name }) {
                Store.update { copy(kind = it) }
            }
            Title("Columns: ${settings.columns}")
            Slider(
                settings.columns.toFloat(),
                { value -> Store.update { copy(columns = value.roundToInt()) } },
                valueRange =
                    Settings.COLUMNS.first.toFloat()..Settings.COLUMNS.last.toFloat(),
                steps = Settings.COLUMNS.last - Settings.COLUMNS.first - 1
            )
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun <T> Options(
    title: String,
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Title(title)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach {
            FilterChip(it == selected, { onSelect(it) }, { Text(label(it)) })
        }
    }
}
