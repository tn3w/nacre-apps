package org.nacre.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

const val DOCK = -1
private const val MAX_PAGES = 12
private const val MAX_FOLDER = 48

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Apps.init(this)
        Store.init(this)
    }
}

enum class Kind { App, Shortcut, Folder }

data class Item(
    val id: Long,
    val kind: Kind,
    val page: Int = 0,
    val cell: Int = 0,
    val target: String = "",
    val serial: Long = 0,
    val shortcut: String = "",
    val name: String = "",
    val children: List<Item> = emptyList(),
) {
    val key get() = key(target, serial)

    fun matches(packageName: String, serial: Long) =
        this.serial == serial && target.substringBefore('/') == packageName

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("page", page)
        .put("cell", cell)
        .put("target", target)
        .put("serial", serial)
        .put("shortcut", shortcut)
        .put("name", name)
        .put("children", JSONArray(children.map(Item::toJson)))

    companion object {
        fun fromJson(json: JSONObject): Item {
            val children = json.optJSONArray("children") ?: JSONArray()
            return Item(
                id = json.getLong("id"),
                kind = Kind.valueOf(json.getString("kind")),
                page = json.getInt("page"),
                cell = json.getInt("cell"),
                target = json.getString("target"),
                serial = json.getLong("serial"),
                shortcut = json.optString("shortcut"),
                name = json.optString("name").take(40),
                children = List(children.length()) { fromJson(children.getJSONObject(it)) }
                    .filter { it.kind != Kind.Folder }
                    .take(MAX_FOLDER),
            )
        }

        fun of(app: App, id: Long) =
            Item(id, Kind.App, target = app.component.flattenToString(), serial = app.serial)
    }
}

object Store {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    val items = MutableStateFlow(emptyList<Item>())
    val columns = MutableStateFlow(4)
    val themed = MutableStateFlow(false)
    val keyboard = MutableStateFlow(false)
    val autoAdd = MutableStateFlow(true)
    val hidden = MutableStateFlow(emptySet<String>())

    val rows get() = columns.value + 1

    fun init(application: Context) {
        context = application
        preferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        columns.value = preferences.getInt("columns", 4).coerceIn(4, 5)
        themed.value = preferences.getBoolean("themed", false)
        keyboard.value = preferences.getBoolean("keyboard", false)
        autoAdd.value = preferences.getBoolean("autoAdd", true)
        hidden.value = preferences.getStringSet("hidden", emptySet()).orEmpty().toSet()
        val saved = preferences.getString("items", null)
        if (saved == null) {
            preferences.edit { putString("items", "[]") }
            seedDock()
            return
        }
        val array = runCatching { JSONArray(saved) }.getOrDefault(JSONArray())
        val loaded = List(array.length()) {
            runCatching { Item.fromJson(array.getJSONObject(it)) }.getOrNull()
        }
        save(loaded.filterNotNull().distinctBy { it.id })
    }

    private fun seedDock() {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val packages = intents.mapNotNull {
            context.packageManager.resolveActivity(it, 0)?.activityInfo?.packageName
                ?.takeIf { name -> name != "android" }
        }
        Apps.reload { apps ->
            val main = Apps.serial(android.os.Process.myUserHandle())
            val dock = packages.distinct().mapNotNull { name ->
                apps.find { it.component.packageName == name && it.serial == main }
            }
            save(dock.mapIndexed { index, app -> Item.of(app, nextId() + index).copy(page = DOCK, cell = index) })
        }
    }

    fun setColumns(value: Int) {
        columns.value = value
        preferences.edit { putInt("columns", value) }
        save(items.value)
    }

    fun setThemed(value: Boolean) {
        themed.value = value
        preferences.edit { putBoolean("themed", value) }
    }

    fun setKeyboard(value: Boolean) {
        keyboard.value = value
        preferences.edit { putBoolean("keyboard", value) }
    }

    fun setAutoAdd(value: Boolean) {
        autoAdd.value = value
        preferences.edit { putBoolean("autoAdd", value) }
    }

    fun setHidden(app: App, hide: Boolean) {
        hidden.value = if (hide) hidden.value + app.key else hidden.value - app.key
        preferences.edit { putStringSet("hidden", hidden.value) }
    }

    fun nextId() = (items.value.flatMap { listOf(it) + it.children }.maxOfOrNull { it.id } ?: 0) + 1

    fun folder(id: Long) = items.value.find { it.id == id && it.kind == Kind.Folder }

    fun has(app: App) = items.value.any { item ->
        item.key == app.key || item.children.any { it.key == app.key }
    }

    fun drop(item: Item, page: Int, cell: Int) {
        val existing = items.value.find { it.id == item.id }
        val others = items.value.filter { it.id != item.id }
        val occupant = others.find { it.page == page && it.cell == cell }
        val moved = item.copy(page = page, cell = cell)
        save(
            when {
                occupant == null -> others + moved
                item.kind == Kind.Folder || occupant.kind == Kind.Folder && occupant.children.size >= MAX_FOLDER -> {
                    if (existing == null) return
                    others.map {
                        if (it.id == occupant.id) it.copy(page = existing.page, cell = existing.cell) else it
                    } + moved
                }
                occupant.kind == Kind.Folder -> others.map {
                    if (it.id == occupant.id) it.copy(children = it.children + item) else it
                }
                else -> others.filter { it.id != occupant.id } + Item(
                    id = nextId(),
                    kind = Kind.Folder,
                    page = page,
                    cell = cell,
                    children = listOf(occupant, item),
                )
            },
        )
    }

    fun add(item: Item) {
        val free = freeSlot() ?: return Apps.toast("No room on the home screen")
        save(items.value + item.copy(page = free.first, cell = free.second))
    }

    fun addNew(app: App) {
        if (autoAdd.value && !has(app)) add(Item.of(app, nextId()))
    }

    private fun freeSlot(): Pair<Int, Int>? {
        val taken = items.value.map { it.page to it.cell }.toSet()
        val cells = columns.value * rows
        for (page in 0 until MAX_PAGES) {
            val cell = (0 until cells).firstOrNull { (page to it) !in taken }
            if (cell != null) return page to cell
        }
        return null
    }

    fun remove(id: Long) {
        val removed = items.value.find { it.id == id } ?: return
        (removed.children + removed).forEach(::unpin)
        save(items.value - removed)
    }

    fun removeFromFolder(folderId: Long, childId: Long, toHome: Boolean) {
        val folder = folder(folderId) ?: return
        val child = folder.children.find { it.id == childId } ?: return
        save(items.value.map { if (it.id == folderId) it.copy(children = it.children - child) else it })
        if (toHome) add(child.copy(id = nextId())) else unpin(child)
    }

    fun rename(folderId: Long, name: String) {
        save(items.value.map { if (it.id == folderId) it.copy(name = name.take(40)) else it })
    }

    fun removePackage(packageName: String, serial: Long) {
        save(
            items.value
                .filterNot { it.matches(packageName, serial) }
                .map { item -> item.copy(children = item.children.filterNot { it.matches(packageName, serial) }) },
        )
    }

    fun keepShortcuts(packageName: String, serial: Long, pinned: Set<String>) {
        fun gone(item: Item) =
            item.kind == Kind.Shortcut && item.matches(packageName, serial) && item.shortcut !in pinned
        save(items.value.filterNot(::gone).map { it.copy(children = it.children.filterNot(::gone)) })
    }

    private fun unpin(item: Item) {
        if (item.kind == Kind.Shortcut) Apps.unpin(item.target, item.shortcut, item.serial)
    }

    private fun save(list: List<Item>) {
        val arranged = arrange(list.mapNotNull(::settleFolder))
        items.value = arranged
        preferences.edit { putString("items", JSONArray(arranged.map(Item::toJson)).toString()) }
    }

    private fun settleFolder(item: Item): Item? {
        if (item.kind != Kind.Folder) return item
        return when (item.children.size) {
            0 -> null
            1 -> item.children.single().copy(page = item.page, cell = item.cell)
            else -> item
        }
    }

    private fun arrange(list: List<Item>): List<Item> {
        val cells = columns.value * rows
        val taken = mutableSetOf<Pair<Int, Int>>()
        val placed = mutableListOf<Item>()
        val overflow = mutableListOf<Item>()
        for (item in list.sortedWith(compareBy({ it.page }, { it.cell }))) {
            val limit = if (item.page == DOCK) columns.value else cells
            val slot = item.page to item.cell
            if (item.cell in 0 until limit && item.page in DOCK until MAX_PAGES && taken.add(slot)) {
                placed += item
            } else {
                overflow += item
            }
        }
        for (item in overflow) {
            val slot = (0 until MAX_PAGES).asSequence()
                .flatMap { page -> (0 until cells).asSequence().map { page to it } }
                .firstOrNull { it !in taken } ?: break
            taken += slot
            placed += item.copy(page = slot.first, cell = slot.second)
        }
        val pages = placed.map { it.page }.filter { it >= 0 }.distinct().sorted()
        return placed.map { item ->
            if (item.page == DOCK) item else item.copy(page = pages.indexOf(item.page))
        }
    }
}
