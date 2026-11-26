package org.nacre.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class Histogram(private val onBins: (IntArray) -> Unit) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) = image.use {
        val now = SystemClock.uptimeMillis()
        if (now - last < 120) return@use
        last = now
        val plane = it.planes[0]
        val bins = IntArray(64)
        for (y in 0 until it.height step 4) {
            for (x in 0 until it.width step 4) {
                bins[(plane.buffer.get(y * plane.rowStride + x).toInt() and 0xFF) shr 2]++
            }
        }
        onBins(bins)
    }
}

@Composable
fun HistogramView(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(128.dp, 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(6.dp)
    ) {
        val peak = bins.max().coerceAtLeast(1).toFloat()
        val barWidth = size.width / bins.size
        bins.forEachIndexed { index, count ->
            val height = size.height * count / peak
            drawRect(
                Color.White.copy(alpha = 0.85f),
                Offset(index * barWidth, size.height - height),
                Size(barWidth, height)
            )
        }
    }
}

@Composable
fun StepSlider(labels: List<String>, index: Int, onIndex: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(labels[index.coerceIn(labels.indices)], style = MaterialTheme.typography.titleSmall)
        Slider(
            value = index.toFloat(),
            onValueChange = { onIndex(it.roundToInt()) },
            valueRange = 0f..(labels.size - 1).coerceAtLeast(1).toFloat(),
            steps = (labels.size - 2).coerceAtLeast(0)
        )
    }
}

private enum class Dial(val label: String) {
    Iso("ISO"),
    Shutter("Shutter"),
    Focus("Focus"),
    Balance("WB"),
    Exposure("EV")
}

fun shutterLabel(nanos: Long) =
    if (nanos >= 1_000_000_000L) "${nanos / 1_000_000_000L}s" else "1/${1_000_000_000L / nanos}"

private fun focusLabel(diopters: Float) =
    if (diopters <= 0f) "∞" else "%.2f m".format(1f / diopters)

private fun evLabel(index: Int, step: Float) = "%+.1f".format(index * step)

@Composable
fun ProControls(
    capabilities: Capabilities,
    manual: Manual,
    onManual: (Manual) -> Unit,
    exposure: Exposure?,
    onExposure: (Int) -> Unit
) {
    var dial by remember { mutableStateOf<Dial?>(null) }
    val focusSteps = List(21) { capabilities.minFocus * (20 - it) / 20f }
    val available = Dial.entries.filter {
        when (it) {
            Dial.Iso -> capabilities.isoSteps.isNotEmpty()
            Dial.Shutter -> capabilities.shutterSteps.isNotEmpty()
            Dial.Focus -> capabilities.minFocus > 0f
            Dial.Balance -> true
            Dial.Exposure -> exposure != null && !manual.exposure
        }
    }

    fun value(dial: Dial) = when (dial) {
        Dial.Iso -> manual.iso?.toString() ?: "Auto"
        Dial.Shutter -> manual.shutter?.let(::shutterLabel) ?: "Auto"
        Dial.Focus -> manual.focus?.let(::focusLabel) ?: "Auto"
        Dial.Balance -> manual.balance.label
        Dial.Exposure -> exposure?.let { evLabel(it.index, it.step) } ?: "0"
    }

    Column {
        when (dial?.takeIf { it in available }) {
            Dial.Iso -> StepSlider(
                listOf("Auto") + capabilities.isoSteps.map { "ISO $it" },
                capabilities.isoSteps.indexOf(manual.iso) + 1
            ) { onManual(manual.copy(iso = capabilities.isoSteps.getOrNull(it - 1))) }
            Dial.Shutter -> StepSlider(
                listOf("Auto") + capabilities.shutterSteps.map(::shutterLabel),
                capabilities.shutterSteps.indexOf(manual.shutter) + 1
            ) { onManual(manual.copy(shutter = capabilities.shutterSteps.getOrNull(it - 1))) }
            Dial.Focus -> StepSlider(
                listOf("Auto") + focusSteps.map(::focusLabel),
                focusSteps.indexOf(manual.focus) + 1
            ) { onManual(manual.copy(focus = focusSteps.getOrNull(it - 1))) }
            Dial.Balance -> StepSlider(Balance.entries.map { it.label }, manual.balance.ordinal) {
                onManual(manual.copy(balance = Balance.entries[it]))
            }
            Dial.Exposure -> exposure?.let { state ->
                val range = state.range.toList()
                StepSlider(range.map { evLabel(it, state.step) }, range.indexOf(state.index)) {
                    onExposure(range[it])
                }
            }
            null -> Unit
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            available.forEach {
                FilterChip(
                    selected = dial == it,
                    onClick = { dial = if (dial == it) null else it },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(it.label, style = MaterialTheme.typography.labelSmall)
                            Text(value(it), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                )
            }
            if (manual != Manual()) {
                FilterChip(
                    selected = false,
                    onClick = { onManual(Manual()) },
                    label = { Text("Reset") }
                )
            }
        }
    }
}

data class Exposure(val index: Int, val range: IntRange, val step: Float)
