package org.nacre.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.JPEG
import android.graphics.Bitmap.CompressFormat.PNG
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class Adjust {
    Exposure,
    Brightness,
    Contrast,
    Saturation,
    Warmth,
    Tint,
    Fade,
    Vignette
}

enum class Filter { Original, Vivid, Warm, Cool, Mono, Noir, Sepia, Faded, Dramatic }

enum class Aspect(val label: String, val ratio: Float?) {
    Free("Free", null),
    Original("Original", 0f),
    Square("1:1", 1f),
    Classic("4:3", 4f / 3f),
    Portrait("3:4", 3f / 4f),
    Wide("16:9", 16f / 9f),
    Tall("9:16", 9f / 16f)
}

data class Stroke(
    val points: List<Offset>,
    val color: Int,
    val width: Float,
    val marker: Boolean
)

data class Edit(
    val turns: Int = 0,
    val flip: Boolean = false,
    val angle: Float = 0f,
    val crop: RectF = RectF(0f, 0f, 1f, 1f),
    val aspect: Aspect = Aspect.Free,
    val filter: Filter = Filter.Original,
    val adjust: Map<Adjust, Float> = emptyMap(),
    val strokes: List<Stroke> = emptyList()
) {
    operator fun get(adjust: Adjust) = this.adjust[adjust] ?: 0f
}

private fun scale(red: Float, green: Float, blue: Float, add: Float = 0f) =
    ColorMatrix().apply {
        setScale(red, green, blue, 1f)
        array[4] = add
        array[9] = add
        array[14] = add
    }

private fun saturation(value: Float) = ColorMatrix().apply { setSaturation(value) }

private fun contrast(value: Float) = scale(value, value, value, 128f * (1f - value))

private fun ColorMatrix.then(next: ColorMatrix) = apply { postConcat(next) }

private fun Filter.matrix(): ColorMatrix = when (this) {
    Filter.Original -> ColorMatrix()

    Filter.Vivid -> saturation(1.45f).then(contrast(1.1f))

    Filter.Warm -> scale(1.1f, 1f, 0.85f, 6f)

    Filter.Cool -> scale(0.88f, 1f, 1.12f)

    Filter.Mono -> saturation(0f)

    Filter.Noir -> saturation(0f).then(contrast(1.45f))

    Filter.Sepia -> saturation(0f).then(scale(1.07f, 0.74f, 0.43f, 20f))

    Filter.Faded -> saturation(0.7f).then(scale(0.8f, 0.8f, 0.8f, 40f))

    Filter.Dramatic -> saturation(
        0.75f
    ).then(contrast(1.35f)).then(scale(0.95f, 0.95f, 1f))
}

fun Edit.colorMatrix(): ColorMatrix {
    val exposure = 2f.pow(this[Adjust.Exposure])
    val warmth = this[Adjust.Warmth] * 0.2f
    val tint = this[Adjust.Tint] * 0.15f
    val fade = this[Adjust.Fade].coerceAtLeast(0f) * 0.3f
    return filter
        .matrix()
        .then(scale(exposure, exposure, exposure, this[Adjust.Brightness] * 60f))
        .then(contrast(1f + this[Adjust.Contrast] * 0.6f))
        .then(saturation(1f + this[Adjust.Saturation]))
        .then(scale(1f + warmth, 1f - tint, 1f - warmth))
        .then(scale(1f - fade, 1f - fade, 1f - fade, fade * 255f))
}

fun Edit.orientedSize(width: Int, height: Int) =
    if (turns % 2 == 0) width to height else height to width

private fun Edit.geometry(source: Bitmap, width: Float, height: Float): Matrix {
    val radians = Math.toRadians(angle.toDouble())
    val ratio = max(width / height, height / width)
    val fill = (abs(cos(radians)) + abs(sin(radians)) * ratio).toFloat()
    return Matrix().apply {
        postTranslate(-source.width / 2f, -source.height / 2f)
        postRotate(turns * 90f)
        if (flip) postScale(-1f, 1f)
        postRotate(angle)
        postScale(fill, fill)
        postTranslate(width / 2f, height / 2f)
    }
}

fun render(source: Bitmap, edit: Edit, cropped: Boolean = true): Bitmap {
    val (fullWidth, fullHeight) = edit.orientedSize(source.width, source.height)
    val crop = if (cropped) edit.crop else RectF(0f, 0f, 1f, 1f)
    val width = (crop.width() * fullWidth).toInt().coerceAtLeast(1)
    val height = (crop.height() * fullHeight).toInt().coerceAtLeast(1)
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val matrix = edit.geometry(source, fullWidth.toFloat(), fullHeight.toFloat())
    matrix.postTranslate(-crop.left * fullWidth, -crop.top * fullHeight)
    val paint =
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(edit.colorMatrix())
        }
    canvas.drawBitmap(source, matrix, paint)
    vignette(canvas, edit[Adjust.Vignette], width.toFloat(), height.toFloat())
    if (cropped) {
        edit.strokes.forEach { draw(canvas, it, width.toFloat(), height.toFloat()) }
    }
    return output
}

private fun vignette(canvas: Canvas, amount: Float, width: Float, height: Float) {
    if (amount == 0f) return
    val color = if (amount > 0) Color.BLACK else Color.WHITE
    val edge = (abs(amount) * 230).toInt() shl 24 or (color and 0xFFFFFF)
    val radius = max(width, height) * 0.75f
    val gradient =
        RadialGradient(
            width / 2,
            height / 2,
            radius,
            intArrayOf(0, 0, edge),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    canvas.drawRect(0f, 0f, width, height, Paint().apply { shader = gradient })
}

fun draw(canvas: Canvas, stroke: Stroke, width: Float, height: Float) {
    val path = Path()
    stroke.points.forEachIndexed { index, point ->
        if (index == 0) {
            path.moveTo(point.x * width, point.y * height)
        } else {
            path.lineTo(point.x * width, point.y * height)
        }
    }
    if (stroke.points.size == 1) path.rLineTo(0.1f, 0f)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.width * min(width, height)
            color = stroke.color
            if (stroke.marker) alpha = 110
        }
    canvas.drawPath(path, paint)
}

fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val largest = max(info.size.width, info.size.height)
        if (largest > maxSide) {
            val factor = maxSide.toFloat() / largest
            decoder.setTargetSize(
                (info.size.width * factor).toInt().coerceAtLeast(1),
                (info.size.height * factor).toInt().coerceAtLeast(1)
            )
        }
    }
}

private val EXIF_TAGS =
    listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF
    )

private val EDITED_PATH = "${Environment.DIRECTORY_PICTURES}/Edited/"

private val UNSAFE = Regex("[\\u0000-\\u001f/\\\\:*?\"<>|]")

fun safeName(name: String) = name.replace(UNSAFE, "").trim().trimStart('.').take(120)

fun save(context: Context, media: Media, edit: Edit): Uri? {
    val resolver = context.contentResolver
    val source = decode(context, media.uri, 4096)
    val bitmap = render(source, edit)
    source.recycle()
    val png = media.mime == "image/png"
    val extension = if (png) "png" else "jpg"
    val base = safeName(media.name.substringBeforeLast('.')).ifEmpty { "image" }
    val folders = listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES)
    val local = !media.external && folders.any { media.path.startsWith(it) }
    val values =
        ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, "$base-edited.$extension")
            put(MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaColumns.RELATIVE_PATH, if (local) media.path else EDITED_PATH)
            put(MediaColumns.DATE_TAKEN, media.taken)
            put(MediaColumns.IS_PENDING, 1)
        }
    val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    val collection = MediaStore.Images.Media.getContentUri(volume)
    val uri = resolver.insert(collection, values) ?: return null
    val written = runCatching {
        resolver.openOutputStream(uri)!!.use {
            bitmap.compress(if (png) PNG else JPEG, 95, it)
        }
        if (!png) copyExif(context, media.uri, uri)
    }
    bitmap.recycle()
    if (written.isFailure) {
        resolver.delete(uri, null)
        return null
    }
    resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null)
    return uri
}

private fun copyExif(context: Context, from: Uri, to: Uri) {
    val resolver = context.contentResolver
    val source =
        runCatching {
            resolver.openInputStream(from)!!.use { ExifInterface(it) }
        }.getOrNull() ?: return
    resolver.openFileDescriptor(to, "rw")!!.use { descriptor ->
        val target = ExifInterface(descriptor.fileDescriptor)
        EXIF_TAGS.forEach { tag ->
            source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        target.saveAttributes()
    }
}
