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
private const val MAX_SPAN = 6

class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Apps.init(this)
        Widgets.init(this)
        Store.init(this)
        Widgets.prune()
    }
}

enum class Kind { App, Shortcut, Folder, Widget }

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
    val widget: Int = 0,
    val width: Int = 1,
    val height: Int = 1,
) {
    val key get() = key(target, serial)

    fun cells(columns: Int) = (0 until height).flatMap { row ->
        (0 until width).map { cell + row * columns + it }
    }

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
        .put("widget", widget)
        .put("width", width)
        .put("height", height)

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
                children = List(children.length()) {
                    fromJson(children.getJSONObject(it))
                }
                    .filter { it.kind != Kind.Folder }
                    .take(MAX_FOLDER),
                widget = json.optInt("widget"),
                width = json.optInt("width", 1).coerceIn(1, MAX_SPAN),
                height = json.optInt("height", 1).coerceIn(1, MAX_SPAN),
            )
        }

        fun of(app: App, id: Long) = Item(
            id,
            Kind.App,
            target = app.component.flattenToString(),
            serial = app.serial,
        )
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
            Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_MESSAGING,
            ),
            Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_BROWSER,
            ),
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
            save(
                dock.mapIndexed { index, app ->
                    Item.of(app, nextId() + index).copy(page = DOCK, cell = index)
                },
            )
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

    fun nextId() =
        (items.value.flatMap { listOf(it) + it.children }.maxOfOrNull { it.id } ?: 0) + 1

    fun folder(id: Long) = items.value.find { it.id == id && it.kind == Kind.Folder }

    fun has(app: App) = items.value.any { item ->
        item.key == app.key || item.children.any { it.key == app.key }
    }

    fun drop(item: Item, page: Int, cell: Int) {
        val existing = items.value.find { it.id == item.id }
        val others = items.value - setOfNotNull(existing)
        val moved = item.copy(page = page, cell = cell)
        if (item.kind == Kind.Widget) {
            if (fits(moved, others)) save(others + moved)
            return
        }
        val occupant = others.find {
            it.page == page && cell in it.cells(columns.value)
        }
        if (occupant == null) return save(others + moved)
        if (occupant.kind == Kind.Widget) return

        val isFolder = occupant.kind == Kind.Folder
        val fits = !isFolder || occupant.children.size < MAX_FOLDER
        if (item.kind == Kind.Folder || !fits) {
            if (existing == null) return
            val swapped = occupant.copy(page = existing.page, cell = existing.cell)
            return save(others - occupant + swapped + moved)
        }
        if (isFolder) {
            val joined = occupant.copy(children = occupant.children + item)
            return save(others - occupant + joined)
        }

        val folder = Item(
            id = maxOf(nextId(), item.id + 1),
            kind = Kind.Folder,
            page = page,
            cell = cell,
            children = listOf(occupant, item),
        )
        save(others - occupant + folder)
    }

    fun add(item: Item): Boolean {
        val spot = spots(item).firstOrNull { fits(it, items.value) }
        if (spot == null) {
            Apps.toast("No room on the home screen")
            return false
        }
        save(items.value + spot)
        return true
    }

    fun occupied(page: Int, cell: Int) = items.value.any {
        it.page == page && cell in it.cells(columns.value)
    }

    fun addNew(app: App) {
        if (autoAdd.value && !has(app)) add(Item.of(app, nextId()))
    }

    private fun spots(item: Item) = (0 until MAX_PAGES).asSequence().flatMap { page ->
        (0 until columns.value * rows).asSequence().map { item.copy(page = page, cell = it) }
    }

    private fun slots(item: Item) = item.cells(columns.value).map { item.page to it }

    private fun inside(item: Item): Boolean {
        if (item.page == DOCK) {
            return item.kind != Kind.Widget && item.cell in 0 until columns.value
        }
        val column = item.cell % columns.value
        val row = item.cell / columns.value
        return item.page in 0 until MAX_PAGES && item.cell >= 0 &&
            column + item.width <= columns.value && row + item.height <= rows
    }

    private fun fits(item: Item, others: List<Item>): Boolean {
        val taken = others.filter { it.id != item.id }.flatMap(::slots).toSet()
        return inside(item) && slots(item).none { it in taken }
    }

    fun remove(id: Long) {
        val removed = items.value.find { it.id == id } ?: return
        (removed.children + removed).forEach(::unpin)
        save(items.value - removed)
    }

    fun removeFromFolder(folderId: Long, childId: Long, toHome: Boolean) {
        val folder = folder(folderId) ?: return
        val child = folder.children.find { it.id == childId } ?: return
        update(folderId) { it.copy(children = it.children - child) }
        if (toHome) add(child.copy(id = nextId())) else unpin(child)
    }

    fun rename(folderId: Long, name: String) {
        update(folderId) { it.copy(name = name.take(40)) }
    }

    private fun update(id: Long, change: (Item) -> Item) {
        save(items.value.map { if (it.id == id) change(it) else it })
    }

    fun removePackage(packageName: String, serial: Long) {
        save(
            items.value
                .filterNot { it.matches(packageName, serial) }
                .map { item ->
                    item.copy(
                        children = item.children.filterNot {
                            it.matches(packageName, serial)
                        },
                    )
                },
        )
    }

    fun keepShortcuts(packageName: String, serial: Long, pinned: Set<String>) {
        fun gone(item: Item) =
            item.kind == Kind.Shortcut && item.matches(packageName, serial) &&
                item.shortcut !in pinned
        save(
            items.value.filterNot(::gone).map {
                it.copy(children = it.children.filterNot(::gone))
            },
        )
    }

    private fun unpin(item: Item) {
        if (item.kind != Kind.Shortcut) return
        Apps.unpin(item.target, item.shortcut, item.serial)
    }

    private fun save(list: List<Item>) {
        val arranged = arrange(list.mapNotNull(::settleFolder))
        val kept = arranged.map { it.widget }.toSet()
        items.value.map { it.widget }.filter { it != 0 && it !in kept }.forEach(Widgets::delete)
        items.value = arranged
        preferences.edit {
            putString("items", JSONArray(arranged.map(Item::toJson)).toString())
        }
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
        val taken = mutableSetOf<Pair<Int, Int>>()
        val placed = mutableListOf<Item>()
        val overflow = mutableListOf<Item>()
        val clamped = list.map {
            it.copy(width = it.width.coerceAtMost(columns.value), height = it.height.coerceAtMost(rows))
        }
        for (item in clamped.sortedWith(compareBy({ it.page }, { it.cell }))) {
            val slots = slots(item)
            if (inside(item) && slots.none { it in taken }) {
                taken += slots
                placed += item
            } else {
                overflow += item
            }
        }
        for (item in overflow) {
            val spot = spots(item).firstOrNull { inside(it) && slots(it).none(taken::contains) }
                ?: continue
            taken += slots(spot)
            placed += spot
        }
        val pages = placed.map { it.page }.filter { it >= 0 }.distinct().sorted()
        return placed.map { item ->
            if (item.page == DOCK) item else item.copy(page = pages.indexOf(item.page))
        }
    }
}
