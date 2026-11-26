package org.nacre.files

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

private const val OPEN = 1
private const val RENDER = 2
private const val ERROR = 3
private const val MAX_WIDTH = 2400
private const val MAX_HEIGHT = 8192

class PdfService : Service() {
    private val thread = HandlerThread("pdf").apply { start() }
    private val messenger =
        Messenger(
            Handler(thread.looper) {
                handle(it)
                true
            }
        )
    private var renderer: PdfRenderer? = null

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        renderer?.close()
        thread.quitSafely()
    }

    private fun handle(message: Message) {
        val reply = Message.obtain(null, message.what, message.arg1, 0)
        runCatching {
            reply.data =
                if (message.what == OPEN) open(message.data) else render(message.data)
        }.onFailure { reply.what = ERROR }
        runCatching { message.replyTo.send(reply) }
    }

    private fun open(data: Bundle): Bundle {
        renderer?.close()
        renderer = null
        val descriptor = data.getParcelable("fd", ParcelFileDescriptor::class.java)!!
        val opened = PdfRenderer(descriptor)
        renderer = opened
        val sizes =
            List(opened.pageCount) { index ->
                opened.openPage(index).use {
                    it.width to
                        it.height
                }
            }
        return Bundle().apply {
            putIntArray("widths", sizes.map { it.first }.toIntArray())
            putIntArray("heights", sizes.map { it.second }.toIntArray())
        }
    }

    private fun render(data: Bundle): Bundle {
        val current = renderer!!
        val index = data.getInt("page")
        require(index in 0 until current.pageCount)
        return current.openPage(index).use { page ->
            val width = data.getInt("width").coerceIn(1, MAX_WIDTH)
            val height =
                (width.toLong() * page.height / page.width).toInt().coerceIn(
                    1,
                    MAX_HEIGHT
                )
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Bundle().apply { putParcelable("bitmap", bitmap) }
        }
    }
}

class PdfDocument(private val context: Context) : ServiceConnection {
    private val service = CompletableDeferred<Messenger>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Message>>()
    private val ids = AtomicInteger()
    private val replies =
        Messenger(
            Handler(Looper.getMainLooper()) { reply ->
                pending.remove(reply.arg1)?.complete(Message.obtain(reply))
                true
            }
        )

    fun bind() = context.bindService(
        Intent(context, PdfService::class.java),
        this,
        Context.BIND_AUTO_CREATE
    )

    fun close() = context.unbindService(this)

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        service.complete(Messenger(binder))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        val error = IOException("The PDF renderer stopped")
        service.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private suspend fun request(what: Int, data: Bundle): Bundle {
        val messenger = service.await()
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Message>().also { pending[id] = it }
        messenger.send(
            Message.obtain(null, what, id, 0).also {
                it.data = data
                it.replyTo =
                    replies
            }
        )
        val message = reply.await()
        if (message.what == ERROR) throw IOException("Can't read this PDF")
        return message.data
    }

    suspend fun open(descriptor: ParcelFileDescriptor): List<Float> {
        val data = request(OPEN, Bundle().apply { putParcelable("fd", descriptor) })
        val widths = data.getIntArray("widths")!!
        val heights = data.getIntArray("heights")!!
        return widths.indices.map { heights[it].toFloat() / widths[it] }
    }

    suspend fun render(page: Int, width: Int): Bitmap {
        val data =
            request(
                RENDER,
                Bundle().apply {
                    putInt("page", page)
                    putInt("width", width)
                }
            )
        return data.getParcelable("bitmap", Bitmap::class.java)!!
    }
}
