package org.nacre.contacts

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@Composable
fun <T> rememberQuery(initial: T, vararg keys: Any?, load: ContentResolver.() -> T): State<T> {
    val resolver = LocalContext.current.contentResolver
    return produceState(initial, *keys) {
        resolver.changes().collectLatest {
            value = withContext(Dispatchers.IO) { resolver.load() }
        }
    }
}

@Composable
fun Avatar(name: String, photo: Uri?, size: Dp, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val image by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val shape = modifier.size(size).clip(CircleShape)
    image?.let {
        Image(it, null, shape, contentScale = ContentScale.Crop)
        return
    }
    Box(shape.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercase()
        if (initial == null) {
            Icon(painterResource(R.drawable.ic_person), null, Modifier.size(size / 2))
        } else {
            Text(initial, fontSize = (size.value * 0.42f).sp)
        }
    }
}

@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(
                Modifier.padding(8.dp),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), "Back")
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1
                )
                actions()
            }
            content()
        }
    }
}
