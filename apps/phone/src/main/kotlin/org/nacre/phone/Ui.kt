package org.nacre.phone

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private val KEYS =
    listOf(
        '1' to "",
        '2' to "ABC",
        '3' to "DEF",
        '4' to "GHI",
        '5' to "JKL",
        '6' to "MNO",
        '7' to "PQRS",
        '8' to "TUV",
        '9' to "WXYZ",
        '*' to "",
        '0' to "+",
        '#' to "",
    )

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
) {
    val resolver = LocalContext.current.contentResolver
    val image by produceState<ImageBitmap?>(null, photo) {
        value =
            photo?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        resolver
                            .openInputStream(uri)
                            ?.use(BitmapFactory::decodeStream)
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
        val initial = name.firstOrNull { it.isLetter() }?.uppercase()
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
    onClick: () -> Unit,
) {
    ListItem(
        supportingContent = detail?.let { { Text(it) } },
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
fun Dialpad(
    onPress: (Char) -> Unit,
    onRelease: () -> Unit = {},
    onLongPress: (Char) -> Unit = {},
) {
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        KEYS.chunked(3).forEach { row ->
            Row {
                row.forEach { (key, letters) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .height(80.dp)
                            .clip(CircleShape)
                            .pointerInput(key) {
                                detectTapGestures(
                                    onPress = {
                                        view.performHapticFeedback(
                                            HapticFeedbackConstants.KEYBOARD_TAP,
                                        )
                                        onPress(key)
                                        tryAwaitRelease()
                                        onRelease()
                                    },
                                    onLongPress = { onLongPress(key) },
                                )
                            },
                        Arrangement.Center,
                        Alignment.CenterHorizontally,
                    ) {
                        Text(key.toString(), fontSize = 34.sp)
                        Text(
                            letters,
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

class Tones(
    private val generator: ToneGenerator?,
) {
    fun start(key: Char) {
        val tone =
            when (key) {
                in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (key - '0')
                '*' -> ToneGenerator.TONE_DTMF_S
                '#' -> ToneGenerator.TONE_DTMF_P
                else -> return
            }
        generator?.startTone(tone, 150)
    }

    fun stop() = generator?.stopTone()

    fun release() = generator?.release()
}

@Composable
fun rememberTones(): Tones {
    val context = LocalContext.current
    val tones =
        remember {
            val enabled =
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.DTMF_TONE_WHEN_DIALING,
                    1,
                ) == 1
            Tones(
                if (enabled) {
                    runCatching {
                        ToneGenerator(
                            AudioManager.STREAM_DTMF,
                            80,
                        )
                    }.getOrNull()
                } else {
                    null
                },
            )
        }
    DisposableEffect(tones) { onDispose { tones.release() } }
    return tones
}
