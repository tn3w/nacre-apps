package org.nacre.messages

import android.content.ContentResolver
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.DateFormat

private const val PHOTO_PIXELS = 256

@Composable
fun <T> rememberQuery(
    initial: T,
    vararg keys: Any?,
    load: Context.() -> T,
): State<T> {
    val context = LocalContext.current
    return produceState(initial, *keys) {
        context.contentResolver.changes().collectLatest {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { context.load() }.getOrDefault(initial)
                }
        }
    }
}

@Composable
fun Avatar(
    name: String,
    photo: Uri?,
    size: Dp,
    modifier: Modifier = Modifier,
    group: Boolean = false,
) {
    val resolver = LocalContext.current.contentResolver
    val image by produceState<ImageBitmap?>(null, photo) {
        value =
            photo?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching { resolver.thumbnail(uri) }.getOrNull()
                }
            }
    }
    val shape = modifier.size(size).clip(CircleShape)
    image?.let {
        Image(it, null, shape, contentScale = ContentScale.Crop)
        return
    }
    Box(shape.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        val initial = name.firstOrNull { it.isLetter() }?.uppercase()
        when {
            group -> {
                Icon(
                    painterResource(R.drawable.ic_group),
                    null,
                    Modifier.size(size / 2),
                )
            }

            initial == null -> {
                Icon(
                    painterResource(R.drawable.ic_person),
                    null,
                    Modifier.size(size / 2),
                )
            }

            else -> {
                Text(initial, fontSize = (size.value * 0.42f).sp)
            }
        }
    }
}

private fun ContentResolver.thumbnail(uri: Uri): ImageBitmap? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    if (uri.authority != ContactsContract.AUTHORITY) return null
    val source = ImageDecoder.createSource(this, uri)
    return ImageDecoder
        .decodeBitmap(source) { decoder, info, _ ->
            val side = minOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize((side / PHOTO_PIXELS).coerceAtLeast(1))
        }.asImageBitmap()
}

@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(
                Modifier.padding(8.dp),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), "Back")
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                actions()
            }
            content()
        }
    }
}

@Composable
fun Action(
    title: String,
    detail: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    ) { Text(title) }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SearchField(
    query: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onQuery: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = { onQuery(it.take(100)) },
        modifier = modifier.fillMaxWidth().padding(16.dp, 8.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_close), "Clear")
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}

fun shortTime(
    context: Context,
    date: Long,
): String {
    val now = System.currentTimeMillis()
    if (DateUtils.isToday(
            date,
        )
    ) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    }
    if (now - date < DateUtils.WEEK_IN_MILLIS) {
        return DateUtils.formatDateTime(
            context,
            date,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
    }
    return DateUtils.formatDateTime(
        context,
        date,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

fun Context.toast(text: String) =
    android.widget.Toast
        .makeText(this, text, android.widget.Toast.LENGTH_SHORT)
        .show()

fun Context.open(intent: android.content.Intent) {
    runCatching { startActivity(intent) }.onFailure { toast("No app can do this") }
}
