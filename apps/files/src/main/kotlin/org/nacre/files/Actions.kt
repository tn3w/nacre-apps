package org.nacre.files

import android.content.Context
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import java.io.File

class Actions(
    private val context: Context,
    private val selection: Set<File>,
    private val entries: List<Entry>,
    private val starred: Set<String>,
    private val trash: List<Trashed>,
    private val onSelect: (Set<File>) -> Unit,
    private val onClip: (Clip) -> Unit,
    private val onDialog: (Dialog) -> Unit,
    private val work: (() -> Done) -> Unit
) {
    private val files get() = selection.toList()
    private val count get() = items(files.size)

    private fun trashed() = trash.filter { File(it.path) in selection }

    private fun delete() = onDialog(
        Dialog.Confirm("Move $count to trash?", "Move to trash") {
            val selected = files
            work {
                val trashed = Disk.toTrash(selected)
                Store.star(selected.map { it.path }, false)
                Done(selected, "Moved $count to trash") { trashed.map(Disk::restore) }
            }
        }
    )

    private fun restore() {
        val items = trashed()
        work { Done(items.map(Disk::restore)) }
    }

    private fun deleteForever(items: List<Trashed>) = onDialog(
        Dialog.Confirm("Permanently delete ${items(items.size)}?", "Delete") {
            work {
                items.forEach(Disk::erase)
                Done(emptyList())
            }
        }
    )

    fun emptyTrash() = deleteForever(trash)

    private fun rename(file: File) = onDialog(
        Dialog.Name("Rename", file.name) { name ->
            work {
                Done(listOf(file, Disk.rename(file, name)))
            }
        }
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Bar(tab: Tab) {
        var menu by remember { mutableStateOf(false) }
        val single = files.singleOrNull()
        TopAppBar(
            title = { Text("${files.size}") },
            navigationIcon = {
                IconButton({ onSelect(emptySet()) }) {
                    Icon(painterResource(R.drawable.ic_close), "Clear selection")
                }
            },
            actions = {
                if (tab == Tab.Trash) {
                    IconButton(::restore) {
                        Icon(painterResource(R.drawable.ic_restore), "Restore")
                    }
                    IconButton({ deleteForever(trashed()) }) {
                        Icon(painterResource(R.drawable.ic_delete), "Delete forever")
                    }
                    return@TopAppBar
                }
                Action(R.drawable.ic_copy, "Copy") { onClip(Clip(files, cut = false)) }
                Action(R.drawable.ic_move, "Move") { onClip(Clip(files, cut = true)) }
                Action(R.drawable.ic_share, "Share") {
                    Disk.share(context, files.filter { it.isFile })
                }
                Action(R.drawable.ic_delete, "Delete", ::delete)
                IconButton({ menu = true }) {
                    Icon(painterResource(R.drawable.ic_more), "More")
                }
                DropdownMenu(menu, { menu = false }) {
                    @Composable
                    fun item(label: String, action: () -> Unit) = DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menu = false
                            action()
                        }
                    )
                    item("Select all") { onSelect(entries.map { it.file }.toSet()) }
                    if (single != null) item("Rename") { rename(single) }
                    val allStarred = files.all { it.path in starred }
                    item(if (allStarred) "Unstar" else "Star") {
                        Store.star(files.map { it.path }, !allStarred)
                        onSelect(emptySet())
                    }
                    item("Compress") {
                        val selected = files
                        work {
                            Done(
                                listOf(Disk.compress(selected, selected[0].parentFile!!))
                            )
                        }
                    }
                    if (single != null && Disk.isZip(single)) {
                        item("Extract") { work { Done(listOf(Disk.extract(single))) } }
                    }
                    if (single?.isFile ==
                        true
                    ) {
                        item("Open with") { Disk.open(context, single) }
                    }
                    item("Properties") { onDialog(Dialog.Properties(files)) }
                }
            }
        )
    }
}

@Composable
private fun Action(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(painterResource(icon), label) }
}
