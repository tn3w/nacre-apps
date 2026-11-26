package org.nacre.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SIZE = 256
private const val GLYPH = 232f
private const val SAFE_ZONE = 66f / 108f
private const val DISPLACEMENT = -110f
private const val ERODE = 4
private const val COVERED = 0.6f
private const val EDGE = 0.15f
private val FALLOFF = floatArrayOf(1f, 1f, 1f, 1f, 1f, 0.95f, 0.7f, 0.35f, 0.1f)
private val GLOW = floatArrayOf(0f, 0.8f, 0.8f)

object Sheen {
    private lateinit var environment: FloatArray

    fun init(context: Context) {
        val texture = context.assets.open("sheen.png").use(BitmapFactory::decodeStream)
        val pixels = IntArray(SIZE * SIZE)
        texture.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        environment = FloatArray(pixels.size) { Color.red(pixels[it]) / 255f }
    }

    fun render(icon: Drawable, tone: Tone): Bitmap {
        val alpha = mask(icon)
        val depth = blur(alpha, 12f)
        val bands = FloatArray(alpha.size)
        for (index in alpha.indices) {
            val offset = DISPLACEMENT * ((depth[index] - 0.5f).coerceIn(0f, 1f) - 0.5f)
            val x = index % SIZE + offset
            val y = index / SIZE + offset
            bands[index] = sample(x, y) * table(FALLOFF, depth[index])
        }
        val silk = blur(bands, 1f)
        val safe = blur(erode(alpha), 1.5f)
        val color = if (tone == Tone.White) 1f else 0f
        val pixels =
            IntArray(alpha.size) { index ->
                val light = table(GLOW, silk[index]) * safe[index]
                val source = alpha[index]
                val total = light + source * (1 - light)
                if (total <= 0f) return@IntArray 0
                val value = ((1 - color) * light + color * source * (1 - light)) / total
                val channel = (value * 255).roundToInt()
                Color.argb((total * 255).roundToInt(), channel, channel, channel)
            }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun mask(icon: Drawable): FloatArray {
        if (icon !is AdaptiveIconDrawable) return contrast(pixels(icon, GLYPH))
        val layer = GLYPH / SAFE_ZONE
        icon.monochrome?.let { glyph ->
            val pixels = pixels(glyph, layer)
            return FloatArray(pixels.size) { alpha(pixels[it]) }
        }
        val foreground = icon.foreground ?: return FloatArray(SIZE * SIZE)
        return contrast(pixels(foreground, layer))
    }

    private fun pixels(drawable: Drawable, extent: Float): IntArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val start = ((SIZE - extent) / 2).roundToInt()
        val end = start + extent.roundToInt()
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        copy.bounds = Rect(start, start, end, end)
        copy.draw(Canvas(bitmap))
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return pixels
    }

    private fun alpha(pixel: Int) = Color.alpha(pixel) / 255f

    private fun contrast(pixels: IntArray): FloatArray {
        val alpha = FloatArray(pixels.size) { crisp(alpha(pixels[it])) }
        val opaque = alpha.indices.filter { alpha[it] > 0.5f }
        if (opaque.size < pixels.size * COVERED) return alpha
        val light = FloatArray(pixels.size) { Color.luminance(pixels[it]) }
        val mean = opaque.sumOf { light[it].toDouble() }.toFloat() / opaque.size
        val brighter = opaque.count { light[it] > mean }
        val sign = if (brighter * 2 < opaque.size) 1f else -1f
        return FloatArray(pixels.size) {
            alpha[it] * ((light[it] - mean) * sign / EDGE).coerceIn(0f, 1f)
        }
    }

    private fun crisp(value: Float) = ((value - 0.4f) / 0.2f).coerceIn(0f, 1f)

    private fun sample(x: Float, y: Float): Float {
        val column = x.roundToInt()
        val row = y.roundToInt()
        if (column !in 0 until SIZE || row !in 0 until SIZE) return 0f
        return environment[row * SIZE + column]
    }

    private fun table(values: FloatArray, input: Float): Float {
        val position = input.coerceIn(0f, 1f) * (values.size - 1)
        val index = position.toInt().coerceAtMost(values.size - 2)
        val fraction = position - index
        return values[index] + (values[index + 1] - values[index]) * fraction
    }

    private fun erode(values: FloatArray): FloatArray {
        val rows =
            FloatArray(values.size) { index ->
                val x = index % SIZE
                if (x - ERODE < 0 || x + ERODE >= SIZE) return@FloatArray 0f
                (-ERODE..ERODE).minOf { values[index + it] }
            }
        return FloatArray(values.size) { index ->
            val y = index / SIZE
            if (y - ERODE < 0 || y + ERODE >= SIZE) return@FloatArray 0f
            (-ERODE..ERODE).minOf { rows[index + it * SIZE] }
        }
    }

    private fun blur(values: FloatArray, sigma: Float): FloatArray {
        val horizontal =
            if (sigma >=
                2f
            ) {
                boxes(values, sigma, 1)
            } else {
                kernel(values, sigma, 1)
            }
        return if (sigma >=
            2f
        ) {
            boxes(horizontal, sigma, SIZE)
        } else {
            kernel(horizontal, sigma, SIZE)
        }
    }

    private fun boxes(values: FloatArray, sigma: Float, step: Int): FloatArray {
        val width = (sigma * 3 * sqrt(2 * PI).toFloat() / 4 + 0.5f).toInt()
        if (width % 2 == 1) {
            val half = width / 2
            return box(
                box(box(values, half, half, step), half, half, step),
                half,
                half,
                step,
            )
        }
        val half = width / 2
        val first = box(values, half, half - 1, step)
        return box(box(first, half - 1, half, step), half, half, step)
    }

    private fun box(values: FloatArray, before: Int, after: Int, step: Int): FloatArray {
        val result = FloatArray(values.size)
        val count = before + after + 1
        val lineStep = if (step == 1) SIZE else 1
        for (line in 0 until SIZE) {
            val origin = line * lineStep
            var sum = 0f
            for (position in 0..minOf(after, SIZE - 1)) sum += values[origin + position * step]
            for (position in 0 until SIZE) {
                result[origin + position * step] = sum / count
                val leaving = position - before
                val entering = position + after + 1
                if (leaving >= 0) sum -= values[origin + leaving * step]
                if (entering < SIZE) sum += values[origin + entering * step]
            }
        }
        return result
    }

    private fun kernel(values: FloatArray, sigma: Float, step: Int): FloatArray {
        val radius = (sigma * 3).toInt()
        val weights =
            FloatArray(radius * 2 + 1) {
                val distance = (it - radius).toFloat()
                exp(-distance * distance / (2 * sigma * sigma))
            }
        val total = weights.sum()
        return FloatArray(values.size) { index ->
            val position = if (step == 1) index % SIZE else index / SIZE
            var sum = 0f
            for (offset in -radius..radius) {
                if (position + offset !in 0 until SIZE) continue
                sum += values[index + offset * step] * weights[offset + radius]
            }
            sum / total
        }
    }
}
