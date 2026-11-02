package com.vis.expressivekeyboard.keyboard

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vis.expressivekeyboard.data.ClipboardStore
import com.vis.expressivekeyboard.ui.KeyboardRoot

class ExpressiveIME : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    lateinit var controller: KeyboardController
        private set

    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        runCatching {
            if (ClipboardStore.suppressNext) { ClipboardStore.suppressNext = false; return@runCatching }
            val clip = clipboard?.primaryClip ?: return@runCatching
            if (clip.itemCount == 0) return@runCatching
            val item = clip.getItemAt(0)
            val uri = item.uri
            val desc = clip.description
            val imageMime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
                ?: uri?.let { runCatching { contentResolver.getType(it) }.getOrNull() }?.takeIf { it.startsWith("image/") }
            if (uri != null && imageMime != null) {
                Thread {
                    val imported = ClipboardStore.importImage(uri, imageMime)
                    if (imported != null) android.os.Handler(mainLooper).post { ClipboardStore.addImageItem(imported) }
                }.start()
            } else {
                val text = item.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) ClipboardStore.add(text)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller = KeyboardController(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onCreateInputView(): View {
        val win = window.window!!
        WindowCompat.setDecorFitsSystemWindows(win, false)
        val decor = win.decorView
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeViewModelStoreOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ExpressiveIME)
            setViewTreeViewModelStoreOwner(this@ExpressiveIME)
            setViewTreeSavedStateRegistryOwner(this@ExpressiveIME)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { KeyboardRoot(controller) }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller.onStartInputView(info, restarting)
        com.vis.expressivekeyboard.update.Updater.checkIfDue()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.onFinishInputView()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Also show when a hardware keyboard is attached if the user enabled "show virtual keyboard". */
    override fun onEvaluateInputViewShown(): Boolean {
        if (super.onEvaluateInputViewShown()) return true
        return android.provider.Settings.Secure.getInt(contentResolver, "show_ime_with_hard_keyboard", 0) != 0
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }
}
