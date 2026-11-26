package org.nacre.launcher

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.View
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.nacre.core.Palette

private const val ICON_DP = 56
private const val MENU_SHORTCUTS = 4
private val ICONS = mapOf("com.android.settings" to R.drawable.settings_icon)

data class App(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val serial: Long,
    val info: LauncherActivityInfo,
) {
    val key get() = key(component.flattenToString(), serial)
    val searchable = label.searchable()
}

data class Profile(
    val user: UserHandle,
    val serial: Long,
    val type: String,
    val quiet: Boolean,
) {
    val main get() = user == Process.myUserHandle()

    val label
        get() = when (type) {
            UserManager.USER_TYPE_PROFILE_PRIVATE -> "Private"
            UserManager.USER_TYPE_PROFILE_MANAGED -> "Work"
            UserManager.USER_TYPE_PROFILE_CLONE -> "Clone"
            else -> "Personal"
        }

    val hidesApps get() = quiet && type == UserManager.USER_TYPE_PROFILE_PRIVATE
}

fun key(target: String, serial: Long) = "$target#$serial"

fun String.searchable() = Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")

object Apps {
    private lateinit var context: Context
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private var loading: Job? = null

    val apps = MutableStateFlow(emptyList<App>())
    val profiles = MutableStateFlow(emptyList<Profile>())
    val revision = MutableStateFlow(0)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            Store.removePackage(packageName, serial(user))
            changed(packageName)
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            changed(packageName) { found ->
                found.firstOrNull {
                    it.component.packageName == packageName && it.user == user
                }?.let(Store::addNew)
            }
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) =
            changed(packageName)

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = changed(*packageNames)

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = changed(*packageNames)

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle,
        ) {
            val pinned = shortcuts.filter { it.isPinned }.map { it.id }.toSet()
            Store.keepShortcuts(packageName, serial(user), pinned)
            changed(packageName)
        }
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    fun init(application: Context) {
        context = application
        launcherApps = context.getSystemService(LauncherApps::class.java)
        userManager = context.getSystemService(UserManager::class.java)
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
        }
        context.registerReceiver(profileReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        reload()
    }

    private fun changed(vararg packages: String, then: (List<App>) -> Unit = {}) {
        icons.keys.removeAll { key -> packages.any { key.startsWith("$it/") } }
        reload(then)
    }

    fun reload(then: (List<App>) -> Unit = {}) {
        loading?.cancel()
        loading = scope.launch {
            val found = launcherApps.profiles.map {
                Profile(
                    user = it,
                    serial = serial(it),
                    type = launcherApps.getLauncherUserInfo(it)?.userType.orEmpty(),
                    quiet = userManager.isQuietModeEnabled(it),
                )
            }
            val visible = found.filterNot { it.hidesApps }.flatMap { profile ->
                launcherApps.getActivityList(null, profile.user).map {
                    val label = it.label.toString().trim()
                    App(label, it.componentName, profile.user, profile.serial, it)
                }
            }.filter { it.component.packageName != context.packageName }
            profiles.value = found
            apps.value =
                visible.sortedWith(compareBy(Collator.getInstance()) { it.label })
            revision.value++
            then(visible)
        }
    }

    fun serial(user: UserHandle) = userManager.getSerialNumberForUser(user)

    fun user(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun find(target: String, serial: Long) = apps.value.find {
        it.serial == serial && it.component.flattenToString() == target
    }

    fun profile(serial: Long) = profiles.value.find { it.serial == serial }

    fun isQuiet(serial: Long) = profile(serial)?.quiet == true

    fun icon(app: App, themed: Boolean, dark: Boolean) =
        cached("${app.key}|$themed|$dark") {
            val custom = ICONS[app.component.packageName]?.let(context::getDrawable)
            render(custom ?: app.info.getIcon(density()), app.user, themed, dark)
        }

    fun icon(shortcut: ShortcutInfo, themed: Boolean, dark: Boolean): ImageBitmap? {
        val target = "${shortcut.`package`}/${shortcut.id}"
        return cached("${key(target, serial(shortcut.userHandle))}|$themed|$dark") {
            val drawable = launcherApps.getShortcutIconDrawable(shortcut, density())
                ?: return@cached null
            render(drawable, shortcut.userHandle, themed, dark)
        }
    }

    private fun cached(name: String, load: () -> ImageBitmap?): ImageBitmap? {
        val key = "$name|${density()}"
        return icons[key] ?: runCatching(load).getOrNull()?.also { icons[key] = it }
    }

    private fun density() = context.resources.displayMetrics.densityDpi

    private fun render(
        drawable: Drawable,
        user: UserHandle,
        themed: Boolean,
        dark: Boolean,
    ): ImageBitmap {
        val styled = if (themed) monochrome(drawable, dark) else drawable
        val badged = context.packageManager.getUserBadgedIcon(styled, user)
        val size = (context.resources.displayMetrics.density * ICON_DP).toInt()
        return badged.toBitmap(size, size).asImageBitmap()
    }

    private fun monochrome(drawable: Drawable, dark: Boolean): Drawable {
        val glyph = (drawable as? AdaptiveIconDrawable)?.monochrome ?: return drawable
        val background = if (dark) Palette.Graphite else Palette.Mist
        val foreground = if (dark) Palette.Paper else Palette.Ink
        glyph.mutate().setTint(foreground.toArgb())
        return AdaptiveIconDrawable(ColorDrawable(background.toArgb()), glyph)
    }

    private fun canUseShortcuts() = runCatching {
        launcherApps.hasShortcutHostPermission()
    }
        .getOrDefault(false)

    fun shortcuts(app: App): List<ShortcutInfo> {
        if (!canUseShortcuts()) return emptyList()
        val query = ShortcutQuery()
            .setPackage(app.component.packageName)
            .setActivity(app.component)
            .setQueryFlags(
                ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST,
            )
        return runCatching { launcherApps.getShortcuts(query, app.user).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.isEnabled }
            .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
            .take(MENU_SHORTCUTS)
    }

    fun pinned(packageName: String, id: String, serial: Long): ShortcutInfo? {
        val user = user(serial) ?: return null
        if (!canUseShortcuts()) return null
        val query = ShortcutQuery()
            .setPackage(packageName)
            .setShortcutIds(listOf(id))
            .setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED)
        return runCatching { launcherApps.getShortcuts(query, user)?.firstOrNull() }
            .getOrNull()
    }

    fun unpin(packageName: String, id: String, serial: Long) {
        val user = user(serial) ?: return
        if (!canUseShortcuts()) return
        val query = ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED)
        runCatching {
            val kept = launcherApps.getShortcuts(query, user).orEmpty()
                .map { it.id }
                .filter { it != id }
            launcherApps.pinShortcuts(packageName, kept, user)
        }
    }

    private fun options(view: View, bounds: Rect) = ActivityOptions.makeScaleUpAnimation(
        view,
        bounds.left,
        bounds.top,
        bounds.width(),
        bounds.height(),
    ).toBundle()

    fun launch(view: View, bounds: Rect, app: App) {
        if (unpause(app.user)) return
        runCatching {
            launcherApps.startMainActivity(
                app.component,
                app.user,
                bounds,
                options(view, bounds),
            )
        }.onFailure { toast("Can't open ${app.label}") }
    }

    fun launch(view: View, bounds: Rect, shortcut: ShortcutInfo) {
        if (unpause(shortcut.userHandle)) return
        runCatching {
            launcherApps.startShortcut(shortcut, bounds, options(view, bounds))
        }.onFailure { toast("Can't open ${shortcut.shortLabel}") }
    }

    private fun unpause(user: UserHandle): Boolean {
        if (!userManager.isQuietModeEnabled(user)) return false
        setActive(user, true)
        return true
    }

    fun setActive(user: UserHandle, active: Boolean) {
        runCatching { userManager.requestQuietModeEnabled(!active, user) }
            .onFailure { toast("Can't change this profile") }
        reload()
    }

    fun showInfo(bounds: Rect, app: App) = runCatching {
        launcherApps.startAppDetailsActivity(app.component, app.user, bounds, null)
    }

    fun isSystem(app: App) =
        app.info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    fun uninstall(app: App) {
        val uri = Uri.fromParts("package", app.component.packageName, null)
        val intent = Intent(Intent.ACTION_DELETE, uri)
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
