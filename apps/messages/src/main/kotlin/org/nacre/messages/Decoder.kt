package org.nacre.messages

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.concurrent.thread

private const val DECODE = IBinder.FIRST_CALL_TRANSACTION
private const val MAX_INPUT = 16 * 1024 * 1024
private const val MAX_SIDE = 4096
private const val TIMEOUT = 15_000L

class DecoderService : Service() {
    private val binder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != DECODE || reply == null) {
                    return super.onTransact(code, data, reply, flags)
                }
                val input = data.readFileDescriptor()
                val side = data.readInt().coerceIn(1, MAX_SIDE)
                val pixels = runCatching { input.use { decode(it, side) } }.getOrNull()
                reply.writeInt(if (pixels == null) 0 else 1)
                pixels?.let { (bitmap, memory) ->
                    reply.writeInt(bitmap.width)
                    reply.writeInt(bitmap.height)
                    memory.writeToParcel(reply, 0)
                    memory.close()
                }
                return true
            }
        }

    override fun onBind(intent: Intent): IBinder = binder

    private fun decode(
        input: ParcelFileDescriptor,
        side: Int,
    ): Pair<Bitmap, SharedMemory> {
        val bytes =
            ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                val data = stream.readNBytes(MAX_INPUT + 1)
                require(data.size <= MAX_INPUT)
                data
            }
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0)
                val scale = minOf(1f, side.toFloat() / maxOf(width, height))
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
            }
        val argb =
            bitmap.takeIf { it.config == Bitmap.Config.ARGB_8888 }
                ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val memory = SharedMemory.create("pixels", argb.byteCount)
        val buffer = memory.mapReadWrite()
        argb.copyPixelsToBuffer(buffer)
        SharedMemory.unmap(buffer)
        memory.setProtect(OsConstants.PROT_READ)
        return argb to memory
    }
}

object Decoder {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null

    private fun reset(context: Context) {
        connection?.let { runCatching { context.applicationContext.unbindService(it) } }
        connection = null
        binder = null
    }

    private suspend fun connect(context: Context): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<IBinder?>()
        reset(context)
        val created =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    ready.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    binder = null
                }

                override fun onBindingDied(name: ComponentName) {
                    binder = null
                    runCatching { context.applicationContext.unbindService(this) }
                }
            }
        val intent = Intent(context, DecoderService::class.java)
        val bound =
            context.applicationContext.bindService(
                intent,
                created,
                Context.BIND_AUTO_CREATE,
            )
        connection = created
        val service = if (bound) withTimeoutOrNull(TIMEOUT) { ready.await() } else null
        if (service == null) reset(context)
        binder = service
        return service
    }

    suspend fun decode(
        context: Context,
        uri: Uri,
        side: Int,
    ): Bitmap? =
        mutex.withLock {
            val service = connect(context) ?: return null
            val call =
                scope.async {
                    runCatching { transact(context, service, uri, side) }.getOrNull()
                }
            val bitmap = withTimeoutOrNull(TIMEOUT) { call.await() }
            if (!call.isCompleted) reset(context)
            bitmap
        }

    private fun transact(
        context: Context,
        service: IBinder,
        uri: Uri,
        side: Int,
    ): Bitmap? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                input.use { runCatching { it.copyTo(output) } }
            }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            read.use { data.writeFileDescriptor(it.fileDescriptor) }
            data.writeInt(side)
            service.transact(DECODE, data, reply, 0)
            if (reply.readInt() != 1) return null
            val width = reply.readInt()
            val height = reply.readInt()
            val memory = SharedMemory.CREATOR.createFromParcel(reply)
            return memory.use { pixels(it, width, height, side) }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun pixels(
        memory: SharedMemory,
        width: Int,
        height: Int,
        side: Int,
    ): Bitmap? {
        if (width !in 1..side || height !in 1..side) return null
        if (memory.size.toLong() != width.toLong() * height * 4) return null
        val buffer = memory.mapReadOnly()
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(buffer)
            }
        } finally {
            SharedMemory.unmap(buffer)
        }
    }
}
