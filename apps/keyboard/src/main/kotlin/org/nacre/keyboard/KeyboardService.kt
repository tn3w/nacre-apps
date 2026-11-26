package org.nacre.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nacre.core.NacreTheme
import kotlin.math.abs

enum class Shift { Off, Once, Locked }

enum class Panel { Keys, Emoji, Clipboard }

class KeyboardService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry

    private val scope = MainScope()
    private var suggesting: Job? = null
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            recordClip()
        }

    var page by mutableStateOf(Page.Letters)
    var shift by mutableStateOf(Shift.Off)
    var panel by mutableStateOf(Panel.Keys)
    var suggestions by mutableStateOf(Suggestions())
    var predictions by mutableStateOf(emptyList<String>())
    var action by mutableStateOf(EditorInfo.IME_ACTION_NONE)
    var comma by mutableStateOf(",")

    private val word = StringBuilder()
    private var composing = true
    private var correcting = true
    private var incognito = false
    private var reverting: Pair<String, String>? = null
    private var autoSpace = false
    private var resume = false
    private var lastShift = 0L
    private var lastSpace = 0L

    val language get() = Store.languages.value.first()

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        registry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        window.window?.isNavigationBarContrastEnforced = false
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setContent { NacreTheme { Keyboard(this@KeyboardService) } }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onShowInputRequested(
        flags: Int,
        configChange: Boolean,
    ) = onEvaluateInputViewShown() || super.onShowInputRequested(flags, configChange)

    override fun onStartInputView(
        info: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        Store.unlock()
        val type = info.inputType
        val kind = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val numberPassword =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val password =
            (variation in passwords && kind == InputType.TYPE_CLASS_TEXT) ||
                type == numberPassword
        val email =
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val uri = variation == InputType.TYPE_TEXT_VARIATION_URI
        val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

        page =
            when (kind) {
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Page.Numbers
                InputType.TYPE_CLASS_PHONE -> Page.Phone
                else -> Page.Letters
            }
        comma =
            if (email) {
                "@"
            } else if (uri) {
                "/"
            } else {
                ","
            }
        composing = kind == InputType.TYPE_CLASS_TEXT && !password && !noSuggestions
        correcting = composing && !email && !uri &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER
        incognito = password ||
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val multiLine = type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 || multiLine) {
            action = EditorInfo.IME_ACTION_NONE
        }

        panel = Panel.Keys
        shift = Shift.Off
        reset()
        updateShift()
        Store.expireClips()
        if (composing) preload()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        reset()
    }

    override fun onUpdateSelection(
        oldStart: Int,
        oldEnd: Int,
        start: Int,
        end: Int,
        composingStart: Int,
        composingEnd: Int,
    ) {
        super.onUpdateSelection(
            oldStart,
            oldEnd,
            start,
            end,
            composingStart,
            composingEnd,
        )
        val lost = composingStart < 0 || composingEnd != end
        if (start != end || (word.isNotEmpty() && lost && !composingIntact())) {
            currentInputConnection?.finishComposingText()
            reset()
        }
        if (resume && word.isEmpty() && start == end) resumeWord(start)
        resume = false
        if (word.isEmpty()) predictions = predict()
        updateShift()
    }

    private fun composingIntact() =
        currentInputConnection?.getTextBeforeCursor(word.length, 0)?.toString() ==
            word.toString()

    private fun reset() {
        word.clear()
        suggesting?.cancel()
        suggestions = Suggestions()
        predictions = emptyList()
        reverting = null
        autoSpace = false
    }

    fun type(raw: String) {
        val connection = currentInputConnection ?: return
        val text = if (shift == Shift.Off) raw else raw.uppercase()
        val wordChar =
            text.all(Char::isLetterOrDigit) || (word.isNotEmpty() && text in "'-")
        reverting = null
        if (wordChar && composing) {
            word.append(text)
            connection.setComposingText(word, 1)
            refresh()
        } else if (wordChar) {
            connection.commitText(text, 1)
        } else {
            separate(text)
        }
        autoSpace = autoSpace && !wordChar
        if (shift == Shift.Once) shift = Shift.Off
    }

    private fun separate(text: String) {
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        val typed = word.toString()
        val committed = commitWord(correct = true)
        val spaced = connection.getTextBeforeCursor(1, 0)?.toString() == " "
        if (autoSpace && spaced && text in ".,?!;:") {
            connection.deleteSurroundingText(1, 0)
            connection.commitText("$text ", 1)
        } else {
            connection.commitText(text, 1)
        }
        connection.endBatchEdit()
        if (committed != typed) reverting = typed to committed + text
        autoSpace = false
    }

    private fun commitWord(correct: Boolean): String {
        if (word.isEmpty()) return ""
        val typed = word.toString()
        val correction =
            if (correct && correcting && Store[Toggle.Autocorrect]) {
                suggestionsFor(typed).correction
            } else {
                null
            }
        val final = correction ?: typed
        remember(final, explicit = false)
        currentInputConnection?.commitText(final, 1)
        word.clear()
        suggesting?.cancel()
        suggestions = Suggestions()
        return final
    }

    private fun suggestionsFor(typed: String): Suggestions {
        if (suggestions.typed == typed) return suggestions
        return dictionary()?.suggest(typed, Store.trusted, correcting) ?: Suggestions()
    }

    private fun remember(
        final: String,
        explicit: Boolean,
    ) {
        if (incognito || !Store[Toggle.Learning]) return
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString()
        lastWord(before.orEmpty().dropLast(word.length))?.let { Store.follow(it, final) }
        if (explicit || dictionary()?.contains(final, emptyMap()) == false) {
            Store.learn(final, explicit)
        }
    }

    private fun refresh() {
        val typed = word.toString()
        suggesting?.cancel()
        suggesting =
            scope.launch {
                val result =
                    withContext(Dispatchers.Default) {
                        Dictionary
                            .of(this@KeyboardService, language)
                            .suggest(typed, Store.trusted, correcting)
                    }
                if (word.toString() == typed) suggestions = result
            }
    }

    fun pick(choice: String) {
        val connection = currentInputConnection ?: return
        val typed = word.toString()
        connection.beginBatchEdit()
        val unknown = dictionary()?.contains(choice, Store.trusted) == false
        remember(choice, explicit = choice == typed && unknown)
        connection.commitText("$choice ", 1)
        connection.endBatchEdit()
        word.clear()
        suggestions = Suggestions()
        reverting = null
        autoSpace = true
    }

    fun space() {
        val connection = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val recent = now - lastSpace < 1200
        lastSpace = now
        if (autoSpace && before.endsWith(" ")) {
            autoSpace = false
            return
        }
        if (recent && Store[Toggle.DoubleSpace] && word.isEmpty() && composing &&
            before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
        ) {
            connection.deleteSurroundingText(1, 0)
            connection.commitText(". ", 1)
            return
        }
        separate(" ")
        if (page == Page.Symbols || page == Page.More) page = Page.Letters
    }

    fun delete(repeats: Int) {
        val connection = currentInputConnection ?: return
        val revert = reverting
        reverting = null
        autoSpace = false
        if (revert != null && repeats == 0) {
            val (typed, committed) = revert
            if (connection.getTextBeforeCursor(committed.length, 0)?.toString() ==
                committed
            ) {
                connection.deleteSurroundingText(committed.length, 0)
                connection.commitText(typed, 1)
                remember(typed, explicit = true)
                return
            }
        }
        if (word.isNotEmpty()) {
            word.setLength(word.offsetByCodePoints(word.length, -1))
            connection.setComposingText(word, 1)
            if (word.isEmpty()) suggestions = Suggestions() else refresh()
            return
        }
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
            return
        }
        if (repeats > 12) return deleteWord()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        resume = composing && repeats == 0
    }

    private fun deleteWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(64, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val wordLength =
            trimmed
                .takeLastWhile(
                    Char::isLetterOrDigit,
                ).length
                .coerceAtLeast(1)
        val count =
            (before.length - trimmed.length + wordLength).coerceAtMost(
                before.length,
            )
        connection.deleteSurroundingText(count, 0)
    }

    private fun resumeWord(cursor: Int) {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(48, 0)?.toString() ?: return
        val after = connection.getTextAfterCursor(1, 0)?.toString().orEmpty()
        val tail = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (tail.isEmpty() || after.firstOrNull()?.isLetter() == true) return
        connection.setComposingRegion(cursor - tail.length, cursor)
        word.append(tail)
        refresh()
    }

    fun enter() {
        val connection = currentInputConnection ?: return
        commitWord(correct = true)
        reverting = null
        if (action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            sendKeyChar('\n')
        } else {
            connection.performEditorAction(action)
        }
    }

    fun press(key: Key) {
        when (key.action) {
            Action.Text -> type(key.text)
            Action.Space -> space()
            Action.Enter -> enter()
            Action.Shift -> toggleShift()
            Action.Symbols -> page = Page.Symbols
            Action.More -> page = Page.More
            Action.Letters -> page = Page.Letters
            Action.Language -> switchLanguage()
            Action.Emoji -> panel = Panel.Emoji
            Action.Delete -> delete(0)
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shift =
            when {
                shift == Shift.Locked -> Shift.Off
                now - lastShift < 350 -> Shift.Locked
                shift == Shift.Once -> Shift.Off
                else -> Shift.Once
            }
        lastShift = now
    }

    private fun updateShift() {
        if (shift == Shift.Locked) return
        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        val caps =
            Store[Toggle.AutoCaps] && word.isEmpty() && info.inputType != 0 &&
                connection.getCursorCapsMode(info.inputType) != 0
        shift = if (caps) Shift.Once else Shift.Off
    }

    fun switchLanguage() {
        commitWord(correct = false)
        Store.cycleLanguage()
        preload()
    }

    fun showPicker() =
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    fun moveCursor(steps: Int) {
        if (steps == 0) return
        commitWord(correct = false)
        val code =
            if (steps <
                0
            ) {
                KeyEvent.KEYCODE_DPAD_LEFT
            } else {
                KeyEvent.KEYCODE_DPAD_RIGHT
            }
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    fun insert(text: String) {
        val connection = currentInputConnection ?: return
        commitWord(correct = false)
        connection.commitText(text, 1)
        reverting = null
    }

    fun emoji(text: String) {
        insert(text)
        if (!incognito) Store.useEmoji(text)
    }

    fun feedback(
        view: View,
        key: Key?,
    ) {
        if (Store[Toggle.Haptics]) {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
            )
        }
        if (!Store[Toggle.Sound]) return
        val effect =
            when (key?.action) {
                Action.Delete -> AudioManager.FX_KEYPRESS_DELETE
                Action.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                Action.Enter -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
        getSystemService(AudioManager::class.java).playSoundEffect(effect, -1f)
    }

    private fun predict(): List<String> {
        if (!composing || incognito) return emptyList()
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString()
        return lastWord(before.orEmpty())?.let(Store::predict).orEmpty()
    }

    private fun recordClip() {
        if (incognito) return
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ==
            true
        ) {
            return
        }
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text ?: return
        Store.addClip(text.toString().take(5000))
    }

    private fun preload() {
        scope.launch(
            Dispatchers.Default,
        ) { Dictionary.of(this@KeyboardService, language) }
    }

    private fun dictionary() = Dictionary.loaded(language)

    companion object {
        private val passwords =
            setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
    }
}

fun lastWord(text: String): String? {
    if (!text.endsWith(" ")) return null
    val trimmed = text.trimEnd()
    val word = trimmed.takeLastWhile { it.isLetter() || it == '\'' }
    return word.lowercase().takeIf { it.isNotEmpty() }
}
