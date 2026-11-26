package org.nacre.gallery

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Media(
    val uri: Uri,
    val name: String,
    val mime: String,
    val taken: Long,
    val modified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val albumId: Long,
    val album: String,
    val path: String,
    val favorite: Boolean,
    val trashed: Boolean,
    val expires: Long,
    val external: Boolean = false
) {
    val video get() = mime.startsWith("video/")
    val date get() = localDate(taken)

    companion object {
        fun external(uri: Uri, mime: String) = Media(
            uri,
            uri.lastPathSegment.orEmpty(),
            mime,
            System.currentTimeMillis(),
            0,
            0,
            0,
            0,
            0,
            0,
            "",
            "",
            favorite = false,
            trashed = false,
            expires = 0,
            external = true
        )
    }
}

data class Album(val id: Long, val name: String, val path: String, val items: List<Media>)

data class Section(val title: String?, val items: List<Media>)

private val COLUMNS =
    arrayOf(
        MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaColumns.DISPLAY_NAME,
        MediaColumns.MIME_TYPE,
        MediaColumns.DATE_TAKEN,
        MediaColumns.DATE_MODIFIED,
        MediaColumns.SIZE,
        MediaColumns.WIDTH,
        MediaColumns.HEIGHT,
        MediaColumns.DURATION,
        MediaColumns.BUCKET_ID,
        MediaColumns.BUCKET_DISPLAY_NAME,
        MediaColumns.RELATIVE_PATH,
        MediaColumns.IS_FAVORITE,
        MediaColumns.IS_TRASHED,
        MediaColumns.DATE_EXPIRES
    )

object Library {
    val media = MutableStateFlow<List<Media>>(emptyList())
    val loaded = MutableStateFlow(false)
    private val refresh =
        MutableSharedFlow<Unit>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private val thumbnails =
        object : LruCache<String, ImageBitmap>(
            (Runtime.getRuntime().maxMemory() / 8).toInt()
        ) {
            override fun sizeOf(key: String, value: ImageBitmap) =
                value.width * value.height * 4
        }

    fun observe(context: Context) {
        reload()
        if (observer != null) return
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            refresh.collectLatest {
                delay(200)
                media.value = runCatching { query(resolver) }.getOrDefault(media.value)
                loaded.value = true
            }
        }
        observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    thumbnails.evictAll()
                    reload()
                }
            }
        resolver.registerContentObserver(MediaStore.AUTHORITY_URI, true, observer!!)
    }

    fun reload() {
        refresh.tryEmit(Unit)
    }

    fun find(uri: Uri): Media? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        return media.value.find { ContentUris.parseId(it.uri) == id }
    }

    private fun query(resolver: ContentResolver): List<Media> {
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val args =
            Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($image, $video)"
                )
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cursor = resolver.query(files, COLUMNS, args, null) ?: return emptyList()
        return cursor.use {
            List(it.count) { index -> it.apply { moveToPosition(index) }.toMedia() }
        }
    }

    private fun Cursor.toMedia(): Media {
        val video = getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val collection =
            if (video) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        val modified = getLong(5) * 1000
        return Media(
            uri = ContentUris.withAppendedId(collection, getLong(0)),
            name = getString(2).orEmpty(),
            mime = getString(3) ?: if (video) "video/*" else "image/*",
            taken = getLong(4).takeIf { it > 0 } ?: modified,
            modified = modified,
            size = getLong(6),
            width = getInt(7),
            height = getInt(8),
            duration = getLong(9),
            albumId = getLong(10),
            album = getString(11) ?: "Internal storage",
            path = getString(12).orEmpty(),
            favorite = getInt(13) == 1,
            trashed = getInt(14) == 1,
            expires = getLong(15) * 1000
        )
    }

    fun thumbnail(context: Context, uri: Uri, size: Int): ImageBitmap? {
        val key = "$uri@$size"
        thumbnails[key]?.let { return it }
        val bitmap =
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            }
        return bitmap.getOrNull()?.asImageBitmap()?.also { thumbnails.put(key, it) }
    }

    fun cached(uri: Uri, size: Int): ImageBitmap? = thumbnails["$uri@$size"]

    suspend fun update(context: Context, uris: List<Uri>, values: ContentValues) =
        withContext(Dispatchers.IO) {
            uris.count {
                runCatching { context.contentResolver.update(it, values, null) > 0 }
                    .getOrDefault(false)
            }
        }

    suspend fun move(context: Context, uris: List<Uri>, path: String) = update(
        context,
        uris,
        ContentValues().apply { put(MediaColumns.RELATIVE_PATH, path) }
    )

    suspend fun rename(context: Context, uri: Uri, name: String) = update(
        context,
        listOf(uri),
        ContentValues().apply { put(MediaColumns.DISPLAY_NAME, name) }
    )
}

fun Context.trashRequest(uris: List<Uri>, trash: Boolean): PendingIntent =
    MediaStore.createTrashRequest(contentResolver, uris, trash)

fun Context.favoriteRequest(uris: List<Uri>, favorite: Boolean): PendingIntent =
    MediaStore.createFavoriteRequest(contentResolver, uris, favorite)

fun Context.deleteRequest(uris: List<Uri>): PendingIntent =
    MediaStore.createDeleteRequest(contentResolver, uris)

fun Context.writeRequest(uris: List<Uri>): PendingIntent =
    MediaStore.createWriteRequest(contentResolver, uris)

@Composable
fun rememberRequest(): (PendingIntent, () -> Unit) -> Unit {
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(StartIntentSenderForResult()) {
            if (it.resultCode == Activity.RESULT_OK) pending.value?.invoke()
            pending.value = null
        }
    return { intent, granted ->
        pending.value = granted
        launcher.launch(IntentSenderRequest.Builder(intent).build())
    }
}

@Composable
fun rememberThumbnail(uri: Uri, size: Int): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState(Library.cached(uri, size), uri, size) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { Library.thumbnail(context, uri, size) }
        }
    }
    return bitmap
}

fun List<Media>.arranged(settings: Settings): List<Media> {
    val kind =
        when (settings.kind) {
            Kind.All -> this
            Kind.Photos -> filterNot { it.video }
            Kind.Videos -> filter { it.video }
        }
    val sorted =
        when (settings.sort) {
            Sort.Taken -> kind.sortedBy { it.taken }

            Sort.Modified -> kind.sortedBy { it.modified }

            Sort.Name -> kind.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) {
                    it.name
                }
            )

            Sort.Size -> kind.sortedBy { it.size }
        }
    return if (settings.descending) sorted.reversed() else sorted
}

private fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

fun List<Media>.sections(settings: Settings): List<Section> {
    if (!settings.sort.dated ||
        settings.group == Group.None
    ) {
        return listOf(Section(null, this))
    }
    val today = LocalDate.now()
    val groups = linkedMapOf<String, MutableList<Media>>()
    forEach { media ->
        val date =
            if (settings.sort == Sort.Modified) localDate(media.modified) else media.date
        groups.getOrPut(dateTitle(date, settings.group, today)) { mutableListOf() } +=
            media
    }
    return groups.map { Section(it.key, it.value) }
}

private fun dateTitle(date: LocalDate, group: Group, today: LocalDate): String = when {
    group == Group.Year -> date.year.toString()
    group == Group.Month -> date.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

fun List<Media>.albums(sort: AlbumSort): List<Album> {
    val albums =
        filterNot { it.trashed }
            .groupBy { it.albumId }
            .map { (id, items) ->
                val newest = items.sortedByDescending { it.taken }
                Album(id, newest.first().album, newest.first().path, newest)
            }
    return when (sort) {
        AlbumSort.Recent -> albums.sortedByDescending { it.items.first().taken }

        AlbumSort.Name -> albums.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) {
                it.name
            }
        )

        AlbumSort.Size -> albums.sortedByDescending { it.items.size }
    }
}

fun List<Media>.search(query: String): List<Media> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return this
    val month = DateTimeFormatter.ofPattern("LLLL MMMM MMM yyyy EEEE")
    return filter { media ->
        val text =
            listOf(
                media.name,
                media.album,
                media.date.format(month),
                if (media.video) "video" else "photo",
                if (media.favorite) "favorite" else ""
            ).joinToString(" ").lowercase()
        words.all { it in text }
    }
}

fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%d KB".format(bytes / 1024)
}
