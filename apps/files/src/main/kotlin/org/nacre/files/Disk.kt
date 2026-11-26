package org.nacre.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import org.json.JSONObject

data class Entry(
    val file: File,
    val folder: Boolean,
    val size: Long,
    val modified: Long,
    val count: Int
) {
    val mime get() = Disk.mime(file)
}

enum class Category(val icon: Int, val selection: String) {
    Images(R.drawable.ic_image, "mime_type LIKE 'image/%'"),
    Videos(R.drawable.ic_video, "mime_type LIKE 'video/%'"),
    Audio(R.drawable.ic_audio, "mime_type LIKE 'audio/%'"),
    Documents(
        R.drawable.ic_document,
        "mime_type LIKE 'text/%' OR mime_type = 'application/pdf' OR " +
            "mime_type LIKE 'application/vnd.%' OR mime_type = 'application/msword' OR " +
            "mime_type = 'application/rtf' OR mime_type = 'application/json'"
    ),
    Archives(
        R.drawable.ic_archive,
        "_data LIKE '%.zip' OR _data LIKE '%.7z' OR _data LIKE '%.rar' OR " +
            "_data LIKE '%.tar' OR _data LIKE '%.gz' OR _data LIKE '%.xz'"
    ),
    Apps(R.drawable.ic_apk, "_data LIKE '%.apk'"),
    Downloads(R.drawable.ic_download, "")
}

private val TEXT =
    setOf(
        "txt",
        "md",
        "log",
        "json",
        "xml",
        "csv",
        "ini",
        "conf",
        "cfg",
        "yml",
        "yaml",
        "toml",
        "properties",
        "sh",
        "py",
        "kt",
        "java",
        "js",
        "ts",
        "css",
        "html",
        "c",
        "h"
    )

private const val MAX_ENTRIES = 100_000

data class Volume(val root: File, val name: String, val removable: Boolean)

@OptIn(ExperimentalPathApi::class)
object Disk {
    val home: File = Environment.getExternalStorageDirectory()
    val downloads = File(home, Environment.DIRECTORY_DOWNLOADS)

    @Volatile
    var volumes = listOf(Volume(home, "Internal storage", false))
        private set

    fun refresh(context: Context) {
        val manager = context.getSystemService(StorageManager::class.java)
        volumes = manager.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume ->
                volume.directory?.let {
                    Volume(it, volume.getDescription(context), volume.isRemovable)
                }
            }.ifEmpty { volumes }
    }

    fun volumeOf(file: File) = volumes.firstOrNull {
        file.path == it.root.path || file.path.startsWith(it.root.path + File.separator)
    }

    fun isRoot(file: File) = volumes.any { it.root == file }

    fun name(file: File) = volumes.find { it.root == file }?.name ?: file.name

    private fun trashOf(file: File) = File(volumeOf(file)?.root ?: home, ".Trash")

    fun isTrash(file: File) = volumes.any { file.startsWith(File(it.root, ".Trash")) }

    fun isLink(file: File) = Files.isSymbolicLink(file.toPath())

    private fun walk(file: File) = file.walkTopDown().onEnter { !isLink(it) }

    fun delete(file: File) {
        if (isLink(file)) file.delete() else file.toPath().deleteRecursively()
    }

    fun entry(file: File, hidden: Boolean) = Entry(
        file = file,
        folder = file.isDirectory,
        size = if (file.isDirectory) 0 else file.length(),
        modified = file.lastModified(),
        count = file.listFiles()?.count { hidden || !it.isHidden } ?: 0
    )

    fun list(folder: File, settings: Settings) = folder
        .listFiles()
        .orEmpty()
        .filter { settings.hidden || !it.isHidden }
        .map { entry(it, settings.hidden) }
        .sortedWith(order(settings))

    fun order(settings: Settings): Comparator<Entry> {
        val order = when (settings.sort) {
            Sort.Name -> compareBy<Entry> { it.file.name.lowercase() }

            Sort.Modified -> compareBy { it.modified }

            Sort.Size -> compareBy<Entry> { it.size }.thenBy { it.count }

            Sort.Type -> compareBy<Entry> { it.file.extension.lowercase() }
                .thenBy { it.file.name.lowercase() }
        }
        val directed = if (settings.descending) order.reversed() else order
        return compareBy<Entry> { !it.folder }.then(directed)
    }

    fun search(folder: File, query: String, settings: Settings, active: () -> Boolean) =
        folder.walkTopDown()
            .onEnter {
                it == folder || (!isLink(it) && (settings.hidden || !it.isHidden))
            }
            .takeWhile { active() }
            .drop(1)
            .filter { (settings.hidden || !it.isHidden) && it.name.contains(query, true) }
            .take(500)
            .map { entry(it, settings.hidden) }
            .sortedWith(order(settings))
            .toList()

    fun query(context: Context, selection: String, settings: Settings): List<Entry> {
        val arguments = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "($selection) AND format != 12289"
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 300)
        }
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val paths = context.contentResolver
            .query(uri, arrayOf(FileColumns.DATA), arguments, null)
            ?.use { cursor ->
                List(cursor.count) {
                    cursor.moveToNext()
                    cursor.getString(0)
                }
            }.orEmpty()
        return paths
            .map(::File)
            .filter { it.isFile && !isTrash(it) }
            .filter {
                settings.hidden ||
                    it.path.split('/').none { part -> part.startsWith('.') }
            }.map { entry(it, settings.hidden) }
    }

    fun category(context: Context, category: Category, settings: Settings) =
        query(context, category.selection, settings).sortedWith(order(settings))

    fun recent(context: Context, settings: Settings) =
        query(context, "mime_type IS NOT NULL", settings).take(100)

    fun space(root: File): Pair<Long, Long> =
        StatFs(root.path).run { totalBytes - availableBytes to totalBytes }

    fun size(file: File): Long = walk(file).filter { it.isFile }.sumOf { it.length() }

    fun unique(folder: File, name: String): File {
        val stem = name.substringBeforeLast('.').ifEmpty { name }
        val extension = name.removePrefix(stem)
        var target = File(folder, name)
        var index = 1
        while (target.exists() || isLink(target)) {
            target = File(folder, "$stem (${index++})$extension")
        }
        return target
    }

    private fun checkTarget(file: File, folder: File) {
        if (folder.canonicalPath.startsWith(file.canonicalPath + File.separator) ||
            folder.canonicalFile == file.canonicalFile
        ) {
            throw IOException("Can't put ${file.name} inside itself")
        }
    }

    private fun duplicate(file: File, target: File) {
        try {
            file.toPath().copyToRecursively(target.toPath(), followLinks = false)
        } catch (error: Exception) {
            delete(target)
            throw error
        }
    }

    private fun relocate(file: File, target: File): File {
        if (file.renameTo(target)) return target
        duplicate(file, target)
        delete(file)
        return target
    }

    fun copy(file: File, folder: File): File {
        checkTarget(file, folder)
        return unique(folder, file.name).also { duplicate(file, it) }
    }

    fun move(file: File, folder: File): File {
        if (file.parentFile == folder) return file
        checkTarget(file, folder)
        return relocate(file, unique(folder, file.name))
    }

    fun transfer(files: List<File>, folder: File, cut: Boolean): List<Pair<File, File>> {
        val done = mutableListOf<Pair<File, File>>()
        try {
            files.forEach {
                done += it to if (cut) move(it, folder) else copy(it, folder)
            }
        } catch (error: Exception) {
            revert(done, cut)
            throw error
        }
        return done
    }

    fun revert(done: List<Pair<File, File>>, cut: Boolean) = done.asReversed().forEach {
        val (source, target) = it
        when {
            !cut -> delete(target)
            source != target -> relocate(target, unique(source.parentFile!!, source.name))
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank() || '/' in name || '\u0000' in name || name == "." ||
            name == ".."
        ) {
            throw IOException("Invalid name")
        }
    }

    fun rename(file: File, name: String): File {
        checkName(name)
        val target = File(file.parentFile, name)
        if (target.exists()) throw IOException("$name already exists")
        if (!file.renameTo(target)) throw IOException("Couldn't rename ${file.name}")
        return target
    }

    fun create(folder: File, name: String, directory: Boolean): File {
        checkName(name)
        val target = File(folder, name)
        if (target.exists()) throw IOException("$name already exists")
        val created = if (directory) target.mkdir() else target.createNewFile()
        if (!created) throw IOException("Couldn't create $name")
        return target
    }

    fun toTrash(files: List<File>): List<Trashed> {
        val done = mutableListOf<Trashed>()
        try {
            files.forEach { done += toTrash(it) }
        } catch (error: Exception) {
            done.forEach(::restore)
            throw error
        }
        return done
    }

    private fun toTrash(file: File): Trashed {
        val trash = trashOf(file)
        val folder = File(trash, "files").apply { mkdirs() }
        val info = File(trash, "info").apply { mkdirs() }
        File(trash, ".nomedia").createNewFile()
        val target = unique(folder, file.name)
        val original = file.relativeTo(trash.parentFile!!).path
        val item = Trashed(target.path, File(trash.parentFile, original).path, now())
        File(
            info,
            "${target.name}.json"
        ).writeText(item.toJson().put("original", original).toString())
        if (!relocate(
                file,
                target
            ).exists()
        ) {
            throw IOException("Couldn't delete ${file.name}")
        }
        return item
    }

    private fun now() = System.currentTimeMillis()

    fun trashed(): List<Trashed> = volumes.flatMap { volume ->
        val trash = File(volume.root, ".Trash")
        File(trash, "files").listFiles().orEmpty().map { file ->
            val info = File(trash, "info/${file.name}.json")
            val json = runCatching { JSONObject(info.readText()) }.getOrNull()
            val original = json?.optString("original")?.takeIf { it.isNotEmpty() }
            val safe = original?.let { File(volume.root, it) }
                ?.takeIf { volumeOf(it) == volume && !isTrash(it) }
            Trashed(
                file.path,
                (safe ?: File(volume.root, file.name)).path,
                json?.optLong("deleted") ?: file.lastModified()
            )
        }
    }

    fun purge() {
        trashed().filter { it.expired }.forEach(::erase)
        volumes.forEach { volume ->
            val trash = File(volume.root, ".Trash")
            File(trash, "info").listFiles().orEmpty()
                .filter {
                    !File(trash, "files/${it.name.removeSuffix(".json")}").exists()
                }
                .forEach { it.delete() }
        }
    }

    fun erase(item: Trashed) {
        val file = File(item.path)
        delete(file)
        File(file.parentFile!!.parentFile, "info/${file.name}.json").delete()
    }

    fun restore(item: Trashed): File {
        val file = File(item.path)
        val original = File(item.original)
        original.parentFile?.mkdirs()
        val target = relocate(file, unique(original.parentFile ?: home, original.name))
        File(file.parentFile!!.parentFile, "info/${file.name}.json").delete()
        return target
    }

    fun compress(files: List<File>, folder: File): File {
        val name = if (files.size == 1) files[0].nameWithoutExtension else "Archive"
        val target = unique(folder, "$name.zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                files.forEach { file -> zipInto(zip, file, target) }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        return target
    }

    private fun zipInto(zip: ZipOutputStream, file: File, target: File) {
        walk(file).filter { it != target && !isLink(it) }.forEach { item ->
            val path = item.relativeTo(file.parentFile!!).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry(if (item.isDirectory) "$path/" else path))
            if (item.isFile) item.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun extract(archive: File): File {
        val target = unique(archive.parentFile!!, archive.nameWithoutExtension)
        target.mkdirs()
        try {
            unzip(archive, target)
        } catch (error: Exception) {
            delete(target)
            throw error
        }
        return target
    }

    private fun unzip(archive: File, target: File) {
        val base = target.canonicalPath + File.separator
        var budget = target.usableSpace - 64L * 1024 * 1024
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEachIndexed { index, item ->
                if (index >= MAX_ENTRIES) throw IOException("Archive has too many files")
                val output = File(target, item.name)
                if (!(output.canonicalPath + File.separator).startsWith(base)) {
                    throw IOException("Unsafe archive")
                }
                if (item.isDirectory) {
                    output.mkdirs()
                    return@forEachIndexed
                }
                output.parentFile!!.mkdirs()
                output.outputStream().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        budget -= read
                        if (budget < 0) throw IOException("Not enough space to extract")
                        stream.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    fun scan(context: Context, files: List<File>) {
        val paths = files.flatMap { file -> walk(file).map { it.path }.toList() }
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    fun mime(file: File): String {
        val extension = file.extension.lowercase()
        if (extension == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (extension in TEXT) "text/plain" else "application/octet-stream"
    }

    fun isText(file: File) =
        file.extension.lowercase() in TEXT || mime(file).startsWith("text/")

    fun isPdf(file: File) = file.extension.equals("pdf", true)

    fun isZip(file: File) = file.extension.equals("zip", true)

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    fun open(context: Context, file: File) {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(context, file), mime(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast
                .makeText(
                    context,
                    "No app can open this file",
                    Toast.LENGTH_SHORT
                ).show()
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { uri(context, it) }
        val type = files.map { mime(it) }.distinct().singleOrNull() ?: "*/*"
        val intent =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData =
            ClipData.newRawUri(null, uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
