package org.nacre.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nacre.core.Palette

private const val HOST_ID = 1024
private const val PREVIEW_PIXELS = 480

object Widgets {
    private lateinit var context: Context
    private lateinit var manager: AppWidgetManager
    lateinit var host: AppWidgetHost
    var cell = Size(1f, 1f)

    fun init(application: Context) {
        context = application
        manager = AppWidgetManager.getInstance(context)
        host = AppWidgetHost(context, HOST_ID)
    }

    fun prune() {
        val used =
            Store.items.value
                .map { it.widget }
                .toSet()
        host.appWidgetIds.filter { it !in used }.forEach(::delete)
    }

    fun providers(): List<AppWidgetProviderInfo> = Apps.profiles.value
        .filterNot { it.quiet }
        .flatMap { manager.getInstalledProvidersForProfile(it.user) }

    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    fun allocate() = host.allocateAppWidgetId()

    fun delete(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
    }

    fun bind(id: Int, info: AppWidgetProviderInfo) =
        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)

    fun bindIntent(id: Int, info: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)

    fun span(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val width =
            info.targetCellWidth.takeIf { it > 0 }
                ?: ceil(info.minWidth / cell.width).toInt()
        val height =
            info.targetCellHeight.takeIf { it > 0 }
                ?: ceil(info.minHeight / cell.height).toInt()
        return width.coerceIn(1, Store.columns.value) to height.coerceIn(1, Store.rows)
    }

    fun appLabel(info: AppWidgetProviderInfo): String {
        val serial = Apps.serial(info.profile)
        val packageName = info.provider.packageName
        Apps.apps.value
            .find {
                it.serial == serial && it.component.packageName == packageName
            }?.let { return it.label }
        return runCatching {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val application =
                launcherApps.getApplicationInfo(
                    packageName,
                    0,
                    info.profile,
                )
            context.packageManager.getApplicationLabel(application).toString()
        }.getOrDefault(packageName)
    }

    fun place(id: Int) {
        val info = info(id) ?: return delete(id)
        val (width, height) = span(info)
        val item =
            Item(
                id = Store.nextId(),
                kind = Kind.Widget,
                target = info.provider.flattenToString(),
                serial = Apps.serial(info.profile),
                widget = id,
                width = width,
                height = height,
            )
        if (!Store.add(item)) delete(id)
    }

    fun preview(info: AppWidgetProviderInfo): ImageBitmap? = runCatching {
        val density = context.resources.displayMetrics.densityDpi
        val drawable =
            info.loadPreviewImage(context, density) ?: info.loadIcon(context, density)
        val scale =
            minOf(1f, PREVIEW_PIXELS / maxOf(drawable.intrinsicWidth, 1).toFloat())
        drawable
            .toBitmap(
                (drawable.intrinsicWidth * scale).toInt().coerceAtLeast(1),
                (drawable.intrinsicHeight * scale).toInt().coerceAtLeast(1),
            ).asImageBitmap()
    }.getOrNull()
}

@Composable
fun WidgetPicker(
    close: () -> Unit,
    pick: (AppWidgetProviderInfo) -> Unit,
    glance: () -> Unit,
) {
    BackHandler(onBack = close)
    val context = LocalContext.current
    val groups by produceState<List<Pair<String, List<AppWidgetProviderInfo>>>>(
        emptyList(),
    ) {
        value =
            withContext(Dispatchers.IO) {
                Widgets
                    .providers()
                    .groupBy(Widgets::appLabel)
                    .toList()
                    .sortedBy { it.first.lowercase() }
            }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) {
                    Icon(painterResource(R.drawable.ic_back), "Back")
                }
                Text(
                    "Widgets",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("launcher") {
                    WidgetGroup("Launcher") {
                        item {
                            WidgetCard(
                                "At a glance",
                                "${Store.columns.value} × 1",
                                glance,
                            ) {
                                GlancePreview()
                            }
                        }
                    }
                }
                items(groups, key = { it.first }) { (app, providers) ->
                    WidgetGroup(app) {
                        items(
                            providers,
                            key = { "${it.provider}${it.profile}" },
                        ) { info ->
                            val (width, height) = Widgets.span(info)
                            val label = info.loadLabel(context.packageManager)
                            WidgetCard(label, "$width × $height", { pick(info) }) {
                                ProviderPreview(info)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetGroup(title: String, content: LazyListScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun WidgetCard(
    label: String,
    size: String,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Column(
        Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (isSystemInDarkTheme()) Palette.Ink else Palette.Graphite)
                .padding(12.dp),
            Alignment.Center,
        ) { preview() }
        Text(
            label,
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            size,
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ProviderPreview(info: AppWidgetProviderInfo) {
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) { Widgets.preview(info) }
    }
    preview?.let {
        Image(
            it,
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun GlancePreview() {
    val context = LocalContext.current
    val flags =
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_ALL
    Text(
        DateUtils.formatDateTime(context, System.currentTimeMillis(), flags),
        color = Palette.Paper,
        style = MaterialTheme.typography.titleMedium,
    )
}
