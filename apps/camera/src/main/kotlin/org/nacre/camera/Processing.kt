package org.nacre.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

private fun Context.insert(collection: Uri, name: String, mime: String, write: (Uri) -> Unit): Uri? {
    val values = mediaValues(name, mime).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
    val uri = contentResolver.insert(collection, values) ?: return null
    return runCatching {
        write(uri)
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }.getOrElse {
        contentResolver.delete(uri, null, null)
        null
    }
}

fun saveBitmap(context: Context, bitmap: Bitmap, name: String): Uri? =
    context.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/jpeg") { uri ->
        context.contentResolver.openOutputStream(uri)!!.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

fun saveTimelapse(context: Context, source: File, name: String): Uri? {
    val uri = context.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name, "video/mp4") { uri ->
        context.contentResolver.openFileDescriptor(uri, "w")!!.use { remux(source, it.fileDescriptor) }
    }
    source.delete()
    return uri
}

private fun remux(source: File, target: java.io.FileDescriptor) {
    val extractor = MediaExtractor()
    extractor.setDataSource(source.path)
    val track = (0 until extractor.trackCount).first {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
    }
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val muxer = MediaMuxer(target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
    }
    val output = muxer.addTrack(format)
    muxer.start()

    val buffer = ByteBuffer.allocate(16 shl 20)
    val info = MediaCodec.BufferInfo()
    var frame = 0L
    while (true) {
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            info.set(0, size, frame++ * 33_333L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(output, buffer, info)
        }
        extractor.advance()
    }
    muxer.stop()
    muxer.release()
    extractor.release()
}

fun ImageProxy.uprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun luma(pixel: Int) =
    ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

object Document {
    private const val SIZE = 200

    fun find(bitmap: Bitmap): List<PointF>? {
        val scale = SIZE.toFloat() / max(bitmap.width, bitmap.height)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
        val gray = IntArray(pixels.size) { luma(pixels[it]) }
        val threshold = otsu(gray)
        val mask = BooleanArray(gray.size) { gray[it] > threshold }
        val region = largest(mask, width, height)
        if (region.size < gray.size / 10 || region.size > gray.size * 95 / 100) return null

        val corners = listOf(
            region.minBy { it % width + it / width },
            region.maxBy { it % width - it / width },
            region.maxBy { it % width + it / width },
            region.minBy { it % width - it / width }
        )
        return corners.map { PointF((it % width + 0.5f) / width, (it / width + 0.5f) / height) }
    }

    fun crop(source: Bitmap, corners: List<PointF>): Bitmap {
        val (topLeft, topRight, bottomRight, bottomLeft) =
            corners.map { PointF(it.x * source.width, it.y * source.height) }
        val width = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
        val height = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
        val matrix = Matrix()
        matrix.setPolyToPoly(
            floatArrayOf(
                topLeft.x, topLeft.y, topRight.x, topRight.y,
                bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y
            ),
            0,
            floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height),
            0,
            4
        )
        val contrast = 1.35f
        val offset = (1 - contrast) * 128 + 18
        val enhance = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(enhance)
        val output = Bitmap.createBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, matrix, paint)
        return output
    }

    fun process(context: Context, file: File): Uri? {
        val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        file.delete()
        val corners = find(source) ?: listOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))
        return saveBitmap(context, crop(source, corners), "DOC_${stamp()}")
    }

    private fun distance(first: PointF, second: PointF) = hypot(first.x - second.x, first.y - second.y)

    private fun otsu(gray: IntArray): Int {
        val histogram = IntArray(256).also { counts -> gray.forEach { counts[it]++ } }
        val total = gray.size
        val sum = histogram.indices.sumOf { it.toLong() * histogram[it] }
        var background = 0L
        var weight = 0
        var best = 0.0
        var threshold = 128
        for (level in 0 until 256) {
            weight += histogram[level]
            if (weight == 0 || weight == total) continue
            background += level.toLong() * histogram[level]
            val meanBack = background.toDouble() / weight
            val meanFore = (sum - background).toDouble() / (total - weight)
            val variance = weight.toDouble() * (total - weight) * (meanBack - meanFore) * (meanBack - meanFore)
            if (variance > best) {
                best = variance
                threshold = level
            }
        }
        return threshold
    }

    private fun largest(mask: BooleanArray, width: Int, height: Int): List<Int> {
        val seen = BooleanArray(mask.size)
        var best = emptyList<Int>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val region = fill(start, mask, seen, width, height)
            if (region.size > best.size) best = region
        }
        return best
    }

    private fun fill(start: Int, mask: BooleanArray, seen: BooleanArray, width: Int, height: Int): List<Int> {
        val region = ArrayList<Int>()
        val stack = ArrayDeque<Int>().apply { add(start) }
        seen[start] = true
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            region += index
            val x = index % width
            val neighbors = listOfNotNull(
                (index - 1).takeIf { x > 0 },
                (index + 1).takeIf { x < width - 1 },
                (index - width).takeIf { it >= 0 },
                (index + width).takeIf { it < width * height }
            )
            neighbors.filter { mask[it] && !seen[it] }.forEach {
                seen[it] = true
                stack.add(it)
            }
        }
        return region
    }
}

class DocumentFinder(private val onCorners: (List<PointF>?) -> Unit) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) = image.use {
        val now = System.currentTimeMillis()
        if (now - last < 250) return@use
        last = now
        onCorners(Document.find(it.uprightBitmap()))
    }
}

class Panorama(private val onProgress: (Int) -> Unit) : ImageAnalysis.Analyzer {
    private val strips = mutableListOf<Bitmap>()
    private var previous: FloatArray? = null
    private var pending = 0f
    private var direction = 0f
    private var width = 0

    @Volatile
    var active = false

    override fun analyze(image: ImageProxy) = image.use {
        if (!active) return@use
        val frame = it.uprightBitmap()
        val profile = profileOf(frame)
        val last = previous
        previous = profile
        if (last == null) return@use

        pending += shiftOf(last, profile) * frame.width / PROFILE
        if (direction == 0f && abs(pending) > frame.width / 40) direction = sign(pending)
        val strip = (pending * direction).toInt()
        if (direction == 0f || strip < 8) return@use
        if (strip > frame.width / 3) {
            pending = 0f
            return@use
        }
        strips += Bitmap.createBitmap(frame, (frame.width - strip) / 2, 0, strip, frame.height)
        pending -= strip * direction
        width += strip
        onProgress(width)
    }

    fun finish(): Bitmap? {
        active = false
        val parts = if (direction < 0) strips.reversed() else strips.toList()
        val result = parts.firstOrNull()?.let { first ->
            val output = Bitmap.createBitmap(width, first.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            var x = 0f
            parts.forEach {
                canvas.drawBitmap(it, x, 0f, null)
                x += it.width
            }
            output
        }
        strips.clear()
        previous = null
        pending = 0f
        direction = 0f
        width = 0
        return result?.takeIf { it.width > it.height / 2 }
    }

    private fun profileOf(frame: Bitmap): FloatArray {
        val height = frame.height * PROFILE / frame.width
        val small = Bitmap.createScaledBitmap(frame, PROFILE, height.coerceAtLeast(1), true)
        val pixels = IntArray(PROFILE * small.height)
        small.getPixels(pixels, 0, PROFILE, 0, 0, PROFILE, small.height)
        val rows = small.height / 5 until small.height * 4 / 5
        return FloatArray(PROFILE) { x -> rows.sumOf { luma(pixels[it * PROFILE + x]) }.toFloat() / rows.count() }
    }

    private fun shiftOf(first: FloatArray, second: FloatArray): Int = (-PROFILE / 4..PROFILE / 4).minBy { shift ->
        val range = maxOf(0, -shift) until minOf(PROFILE, PROFILE - shift)
        range.sumOf { abs(first[it + shift] - second[it]).toDouble() } / range.count()
    }

    companion object {
        const val PROFILE = 320
        const val MAX_WIDTH = 12_000
    }
}
