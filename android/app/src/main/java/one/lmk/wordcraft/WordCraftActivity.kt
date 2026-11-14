package one.lmk.wordcraft

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.TextUtils
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import com.google.androidgamesdk.GameActivity
import com.google.androidgamesdk.gametextinput.InputConnection as GameTextInputConnection
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID

class WordCraftActivity : GameActivity() {

    companion object {
        private const val TTS_INIT_NONE = 0
        private const val TTS_INIT_PENDING = 1
        private const val TTS_INIT_READY = 2
        private const val TTS_INIT_FAILED = -1

        private const val TTS_STATE_SPEAKING = 0
        private const val TTS_STATE_DONE = 1
        private const val TTS_STATE_STOPPED = 2
        private const val TTS_STATE_FAILED = -1

        private const val TTS_SPEAK_STARTED = 0
        private const val TTS_SPEAK_INITIALIZING = 1
        private const val TTS_SPEAK_FAILED = -1

        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"

        init {
            System.loadLibrary("wordcraft_android")
        }
    }

    private lateinit var imeProxy: ImeProxyView
    private var imeWanted = false
    @Volatile
    private var imeSuppressed = false
    private var lastImeVisible = false
    private var lastSentInsets: SurfaceInsets? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private val imeSessionLock = Any()
    private val imeSession = ImeEditSession(KeyEvent.KEYCODE_DEL)
    private var lastReportedSelStart = -1
    private var lastReportedSelEnd = -1
    private var lastReportedCompStart = -1
    private var lastReportedCompEnd = -1

    private var activeConnection: WordCraftInputConnection? = null
    private var pendingSaveSuggestedName = "Document.docx"

    private val ttsLock = Any()
    private var tts: TextToSpeech? = null
    private var ttsInitState = TTS_INIT_NONE
    private var ttsUsingGoogleFallback = false
    @Volatile
    private var ttsLastErrorMsg = ""
    @Volatile
    private var activeUtteranceId = ""
    @Volatile
    private var activeUtteranceState = TTS_STATE_STOPPED

    private val openSingleLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handlePickedOpenUris(if (uri != null) listOf(uri) else emptyList())
        }

    private val openMultiLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handlePickedOpenUris(uris)
        }

    private val createDocLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            handleCreatedSaveUri(uri)
        }

    override fun createSurfaceView(): InputEnabledSurfaceView {
        // Keep GameActivity's SurfaceView from claiming View focus or spawning its own
        // GameTextInput connection so all IME composition and suggestions go solely through imeProxy.
        return object : InputEnabledSurfaceView(this) {
            override fun setFocusable(focusable: Boolean) {
                super.setFocusable(false)
            }

            override fun setFocusable(focusable: Int) {
                super.setFocusable(View.NOT_FOCUSABLE)
            }

            override fun setFocusableInTouchMode(focusableInTouchMode: Boolean) {
                super.setFocusableInTouchMode(false)
            }

            override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean = false

            override fun onCheckIsTextEditor(): Boolean = false

            override fun onCreateInputConnection(outAttrs: EditorInfo): GameTextInputConnection? = null
        }.apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        configureCutoutMode()
        super.onCreate(savedInstanceState)

        imeProxy = ImeProxyView(this)
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        root?.addView(imeProxy, ViewGroup.LayoutParams(1, 1))

        installInsetsListener()
        registerDisplayListener()
        installFocusGuard()
        syncSystemNightMode(resources.configuration)
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setLightSystemBars(if (isNight) 0 else 1)
        ensureTtsInitializedAsync(useGoogleFallback = false)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
        unregisterDisplayListener()
        synchronized(ttsLock) {
            activeUtteranceId = ""
            activeUtteranceState = TTS_STATE_STOPPED
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsInitState = TTS_INIT_NONE
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncSystemNightMode(newConfig)
        requestSafeInsets()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        syncSystemNightMode(newConfig)
        requestSafeInsets()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestSafeInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            requestSafeInsets()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        nativeOnPointerIsMouse(if (isMousePointerEvent(ev)) 1 else 0)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        nativeOnPointerIsMouse(if (isMousePointerEvent(ev)) 1 else 0)
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                if (ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                    nativeOnMouseHover(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                if (ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                    val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    nativeOnMouseWheel(h, v)
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun syncSystemNightMode(config: Configuration) {
        val night = when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> 1
            Configuration.UI_MODE_NIGHT_NO -> 0
            else -> -1
        }
        nativeOnSystemNightMode(night)
    }

    private fun isMousePointerEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) || ev.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            return true
        }
        return ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    override fun onResume() {
        super.onResume()
        requestSafeInsets()
        if (imeWanted) {
            imeProxy.post {
                if (imeWanted) {
                    showImeNow()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureTransparentSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun configureCutoutMode() {
        val attrs = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = attrs
    }

    private fun installInsetsListener() {
        val contentRoot = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val insetHost: View = contentRoot ?: window.decorView

        ViewCompat.setOnApplyWindowInsetsListener(insetHost) { view, insets ->
            val targetSurface = findFirstSurfaceView(contentRoot) ?: view
            val surfaceInsets = extractSurfaceInsets(window.decorView, targetSurface, insets)
            if (surfaceInsets != lastSentInsets) {
                lastSentInsets = surfaceInsets
                nativeOnInsets(
                    surfaceInsets.left,
                    surfaceInsets.top,
                    surfaceInsets.right,
                    surfaceInsets.bottom,
                    surfaceInsets.imeBottom,
                )
            }

            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val wasVisible = lastImeVisible
            lastImeVisible = imeVisible
            if (wasVisible && !imeVisible) {
                imeWanted = false
                clearImeComposingState()
                nativeOnImeHidden()
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        insetHost.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                v.post { requestSafeInsets() }
            }
        }
        ViewCompat.requestApplyInsets(insetHost)
    }

    private fun requestSafeInsets() {
        val host = window.decorView.findViewById<View>(android.R.id.content) ?: window.decorView
        ViewCompat.requestApplyInsets(host)
    }

    private fun findFirstSurfaceView(root: View?): SurfaceView? {
        if (root is SurfaceView) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstSurfaceView(root.getChildAt(i))
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val manager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                window.decorView.post { requestSafeInsets() }
            }

            override fun onDisplayRemoved(displayId: Int) {
                window.decorView.post { requestSafeInsets() }
            }

            override fun onDisplayChanged(displayId: Int) {
                window.decorView.post { requestSafeInsets() }
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        val manager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        manager.unregisterDisplayListener(listener)
    }

    private fun installFocusGuard() {
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (imeWanted && newFocus != null && newFocus !== imeProxy) {
                imeProxy.post {
                    if (imeWanted) {
                        imeProxy.requestFocus()
                    }
                }
            }
        }
    }

    private fun imm(): InputMethodManager? =
        getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun showImeNow() {
        if (imeSuppressed) {
            return
        }
        val mgr = imm() ?: return
        val needsFocus = !imeProxy.hasFocus()
        if (needsFocus) {
            imeProxy.requestFocus()
        }
        val show = {
            if (imeWanted && !imeSuppressed) {
                if (!mgr.isActive(imeProxy)) {
                    mgr.restartInput(imeProxy)
                }
                mgr.showSoftInput(imeProxy, 0)
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.ime())
            }
        }
        if (needsFocus) {
            imeProxy.post { show() }
        } else {
            show()
        }
    }

    private fun hideImeNow() {
        clearImeComposingState()
        if (imeProxy.hasFocus()) {
            imeProxy.clearFocus()
        }
        imm()?.hideSoftInputFromWindow(imeProxy.windowToken ?: window.decorView.windowToken, 0)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    }

    @Suppress("unused")
    fun setImeWanted(wanted: Int) {
        runOnUiThread {
            val want = (wanted != 0) && !imeSuppressed
            imeWanted = want
            if (want) {
                showImeNow()
            } else {
                hideImeNow()
            }
        }
    }

    @Suppress("unused")
    fun setImeSuppressed(suppressed: Int) {
        runOnUiThread {
            val sup = suppressed != 0
            imeSuppressed = sup
            window.setSoftInputMode(
                (if (sup) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            if (sup) {
                imeWanted = false
                hideImeNow()
            }
        }
    }

    @Suppress("unused")
    fun setLightSystemBars(light: Int) {
        runOnUiThread {
            val isLight = light != 0
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }
    }

    private fun clearImeComposingState() {
        synchronized(imeSessionLock) {
            imeSession.resetConnectionState()
        }
        lastReportedSelStart = -1
        lastReportedSelEnd = -1
        lastReportedCompStart = -1
        lastReportedCompEnd = -1
        activeConnection?.clearMonitor()
    }

    private fun syncSelectionAndExtractToImm(snap: ImeSnapshot) {
        val mgr = imm() ?: return
        val view = imeProxy
        if (
            snap.selStart != lastReportedSelStart ||
                snap.selEnd != lastReportedSelEnd ||
                snap.compStart != lastReportedCompStart ||
                snap.compEnd != lastReportedCompEnd
        ) {
            lastReportedSelStart = snap.selStart
            lastReportedSelEnd = snap.selEnd
            lastReportedCompStart = snap.compStart
            lastReportedCompEnd = snap.compEnd
            mgr.updateSelection(view, snap.selStart, snap.selEnd, snap.compStart, snap.compEnd)
        }
        activeConnection?.notifyExtractedTextIfMonitored(mgr, view, snap)
    }

    private fun reportSnapshotToImm(snap: ImeSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            syncSelectionAndExtractToImm(snap)
        } else {
            runOnUiThread {
                val (inBatch, latest) = synchronized(imeSessionLock) {
                    imeSession.isInBatch() to imeSession.virtualSnapshot()
                }
                if (!inBatch) {
                    syncSelectionAndExtractToImm(latest)
                }
            }
        }
    }

    private fun flushImeBatch(flush: ImeBatchFlush?) {
        if (flush == null || flush.ops.isEmpty()) {
            return
        }
        nativeOnImeBatchStart()
        for (op in flush.ops) {
            when (op) {
                is ImeOp.Preedit -> nativeOnImeStagePreedit(op.text, op.cursorStartCp, op.cursorEndCp)
                is ImeOp.Commit -> nativeOnImeStageCommit(op.text)
                is ImeOp.DeleteSurrounding -> nativeOnImeStageDeleteSurrounding(op.beforeCp, op.afterCp)
                is ImeOp.SetSelectionRelative -> nativeOnImeStageSetSelectionRelative(op.deltaAnchorCp, op.deltaFocusCp)
                is ImeOp.TapKeyCode -> {
                    nativeOnImeStageKey(op.keyCode, 1)
                    nativeOnImeStageKey(op.keyCode, 0)
                }
            }
        }
        nativeOnImeBatchCommit(flush.seq)
    }

    @Suppress("unused")
    fun updateImeSurrounding(text: String, selStart: Int, selEnd: Int, seq: Long) {
        val changed = synchronized(imeSessionLock) {
            imeSession.syncFromRust(text, selStart, selEnd, seq)
        }
        if (changed) {
            runOnUiThread {
                val (shouldReport, snap) = synchronized(imeSessionLock) {
                    val ok = !imeSession.isInBatch() && imeSession.currentSeq() == seq
                    ok to imeSession.virtualSnapshot()
                }
                if (shouldReport && activeConnection != null) {
                    syncSelectionAndExtractToImm(snap)
                }
            }
        }
    }

    @Suppress("unused")
    fun launchOpenPicker(purpose: String, multiple: Int) {
        runOnUiThread {
            val mimeTypes = when (purpose) {
                "picture" -> arrayOf("image/*")
                "font" -> arrayOf("font/*", "application/font-sfnt", "application/x-font-ttf", "*/*")
                else -> arrayOf("*/*")
            }
            try {
                if (multiple != 0) {
                    openMultiLauncher.launch(mimeTypes)
                } else {
                    openSingleLauncher.launch(mimeTypes)
                }
            } catch (e: Exception) {
                nativeOnFilePickerResult("", "", e.message ?: "Cannot open picker")
            }
        }
    }

    @Suppress("unused")
    fun launchSavePicker(suggestedName: String) {
        runOnUiThread {
            pendingSaveSuggestedName = sanitizeFilename(suggestedName)
            try {
                createDocLauncher.launch(pendingSaveSuggestedName)
            } catch (e: Exception) {
                nativeOnFilePickerResult("", "", e.message ?: "Cannot open save picker")
            }
        }
    }

    @Suppress("unused")
    fun flushStagingToUri(stagingPath: String, uriString: String): String {
        return try {
            val file = File(stagingPath)
            if (!file.exists()) {
                return "Staged file missing"
            }
            val uri = Uri.parse(uriString)
            val stream = contentResolver.openOutputStream(uri, "wt")
                ?: contentResolver.openOutputStream(uri, "w")
                ?: return "Permission denied writing document"
            stream.use { out ->
                FileInputStream(file).use { input ->
                    input.copyTo(out)
                }
                out.flush()
            }
            ""
        } catch (e: Exception) {
            e.message ?: "Failed saving to Android document"
        }
    }

    @Suppress("unused")
    fun getClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) {
            return null
        }
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    @Suppress("unused")
    fun setClipboardText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("WordCraft", text))
    }

    private fun ensureTtsInitializedAsync(useGoogleFallback: Boolean) {
        synchronized(ttsLock) {
            if (isDestroyed || isFinishing) {
                ttsInitState = TTS_INIT_FAILED
                return
            }
            if (!useGoogleFallback && (ttsInitState == TTS_INIT_READY || ttsInitState == TTS_INIT_PENDING)) {
                return
            }
            ttsInitState = TTS_INIT_PENDING
            ttsUsingGoogleFallback = useGoogleFallback
            ttsLastErrorMsg = ""
        }
        runOnUiThread {
            synchronized(ttsLock) {
                if (isDestroyed || isFinishing) {
                    ttsInitState = TTS_INIT_FAILED
                    return@runOnUiThread
                }
                tts?.stop()
                tts?.shutdown()
                tts = null
                lateinit var created: TextToSpeech
                val listener = TextToSpeech.OnInitListener { status ->
                    onTtsInitStatus(created, status)
                }
                created = if (useGoogleFallback) {
                    TextToSpeech(applicationContext, listener, GOOGLE_TTS_ENGINE)
                } else {
                    TextToSpeech(applicationContext, listener)
                }
                tts = created
            }
        }
    }

    private fun onTtsInitStatus(instance: TextToSpeech, status: Int) {
        var retryWithGoogle = false
        synchronized(ttsLock) {
            if (tts !== instance) {
                return
            }
            if (status == TextToSpeech.SUCCESS) {
                instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null && utteranceId == activeUtteranceId) {
                            activeUtteranceState = TTS_STATE_DONE
                        }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId != null && utteranceId == activeUtteranceId) {
                            activeUtteranceState = TTS_STATE_STOPPED
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null && utteranceId == activeUtteranceId) {
                            ttsLastErrorMsg = "Android TextToSpeech synthesis error"
                            activeUtteranceState = TTS_STATE_FAILED
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId != null && utteranceId == activeUtteranceId) {
                            ttsLastErrorMsg = "Android TextToSpeech synthesis error ($errorCode)"
                            activeUtteranceState = TTS_STATE_FAILED
                        }
                    }
                })
                ttsInitState = TTS_INIT_READY
                ttsLastErrorMsg = ""
            } else if (!ttsUsingGoogleFallback) {
                ttsInitState = TTS_INIT_PENDING
                retryWithGoogle = true
            } else {
                ttsLastErrorMsg = "Android TextToSpeech initialization failed ($status)"
                ttsInitState = TTS_INIT_FAILED
            }
        }
        if (retryWithGoogle) {
            ensureTtsInitializedAsync(useGoogleFallback = true)
        }
    }

    private fun applyTtsLocale(engine: TextToSpeech, langTag: String) {
        val candidates = if (langTag.isNotEmpty()) {
            val requested = Locale.forLanguageTag(langTag)
            if (requested.language.isNotEmpty()) {
                listOf(requested, Locale.forLanguageTag(requested.language), Locale.getDefault())
            } else {
                listOf(Locale.getDefault())
            }
        } else {
            listOf(Locale.getDefault())
        }
        for (loc in candidates) {
            if (engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = loc
                return
            }
        }
    }

    @Suppress("unused")
    fun ttsSpeak(utteranceId: String, text: String, langTag: String, rate: Float): Int {
        synchronized(ttsLock) {
            when (ttsInitState) {
                TTS_INIT_NONE -> {
                    ensureTtsInitializedAsync(useGoogleFallback = false)
                    return TTS_SPEAK_INITIALIZING
                }
                TTS_INIT_PENDING -> return TTS_SPEAK_INITIALIZING
                TTS_INIT_FAILED -> {
                    if (ttsLastErrorMsg.isEmpty()) {
                        ttsLastErrorMsg = "Android TextToSpeech unavailable"
                    }
                    return TTS_SPEAK_FAILED
                }
            }
            val engine = tts ?: return TTS_SPEAK_FAILED
            applyTtsLocale(engine, langTag)
            engine.setSpeechRate(rate.coerceIn(0.5f, 3.0f))
            activeUtteranceId = utteranceId
            activeUtteranceState = TTS_STATE_SPEAKING
            val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res == TextToSpeech.ERROR) {
                activeUtteranceState = TTS_STATE_FAILED
                ttsLastErrorMsg = "Android TextToSpeech could not queue utterance"
                return TTS_SPEAK_FAILED
            }
            return TTS_SPEAK_STARTED
        }
    }

    @Suppress("unused")
    fun ttsStatus(utteranceId: String): Int =
        if (activeUtteranceId == utteranceId) activeUtteranceState else TTS_STATE_STOPPED

    @Suppress("unused")
    fun ttsStop() {
        synchronized(ttsLock) {
            activeUtteranceId = ""
            activeUtteranceState = TTS_STATE_STOPPED
            tts?.stop()
        }
    }

    @Suppress("unused")
    fun ttsLastError(): String = ttsLastErrorMsg

    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) {
            return
        }
        val uri = intent.data ?: return
        val staged = stageReadableUri(uri)
        if (staged != null) {
            nativeOnOpenFilePath(staged.absolutePath, uri.toString())
        }
    }

    private fun handlePickedOpenUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            nativeOnFilePickerResult("", "", "")
            return
        }
        val stagedPaths = ArrayList<String>(uris.size)
        val stagedUris = ArrayList<String>(uris.size)
        for (uri in uris) {
            tryTakePersistable(uri)
            val staged = stageReadableUri(uri)
            if (staged == null) {
                nativeOnFilePickerResult("", "", "Cannot read selected file")
                return
            }
            stagedPaths.add(staged.absolutePath)
            stagedUris.add(uri.toString())
        }
        nativeOnFilePickerResult(stagedPaths.joinToString("\n"), stagedUris.joinToString("\n"), "")
    }

    private fun handleCreatedSaveUri(uri: Uri?) {
        if (uri == null) {
            nativeOnFilePickerResult("", "", "")
            return
        }
        tryTakePersistable(uri)
        val workDir = File(File(filesDir, "saf_work"), UUID.randomUUID().toString())
        workDir.mkdirs()
        val displayName = queryDisplayName(uri) ?: pendingSaveSuggestedName
        val safeName = ensureTargetExtension(sanitizeFilename(displayName), pendingSaveSuggestedName)
        val stagingFile = File(workDir, safeName)
        nativeOnFilePickerResult(stagingFile.absolutePath, uri.toString(), "")
    }

    private fun tryTakePersistable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun stageReadableUri(uri: Uri): File? {
        return try {
            val workDir = File(File(filesDir, "saf_work"), UUID.randomUUID().toString())
            workDir.mkdirs()
            val name = sanitizeFilename(queryDisplayName(uri) ?: "document.docx")
            val dest = File(workDir, name)
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { src ->
                dest.outputStream().use { out ->
                    src.copyTo(out)
                }
            }
            dest
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFilename(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val sb = StringBuilder(base.length)
        for (ch in base) {
            sb.append(
                if (ch.isISOControl() || ch in "/\\:*?\"<>|\n\r") '_' else ch
            )
        }
        val trimmed = sb.toString().trimStart('.').trim()
        return if (trimmed.isEmpty()) "document.docx" else trimmed.take(120)
    }

    private fun ensureTargetExtension(pickedName: String, suggested: String): String {
        if (pickedName.contains('.')) {
            return pickedName
        }
        val ext = suggested.substringAfterLast('.', "")
        return if (ext.isNotEmpty() && ext.length <= 10) "$pickedName.$ext" else pickedName
    }

    internal fun handleHardwareOrImeKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        val mappedKey = when (code) {
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_ESCAPE -> true
            else -> false
        }
        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (mappedKey) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                nativeOnImeKey(code, if (pressed) 1 else 0)
            }
            return true
        }
        val uni = event.unicodeChar
        if (pressed && uni != 0 && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            val ch = String(Character.toChars(uni))
            val (flush, snap) = synchronized(imeSessionLock) {
                imeSession.commitText(ch, 1) to imeSession.virtualSnapshot()
            }
            flushImeBatch(flush)
            reportSnapshotToImm(snap)
            return true
        }
        return false
    }

    private inner class ImeProxyView(private val owner: WordCraftActivity) : View(owner) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onCheckIsTextEditor(): Boolean = owner.imeWanted && !owner.imeSuppressed

        override fun onTouchEvent(event: MotionEvent?): Boolean = false

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
            val snap = synchronized(owner.imeSessionLock) {
                owner.imeSession.resetConnectionState()
                owner.imeSession.virtualSnapshot()
            }
            outAttrs.initialSelStart = snap.selStart
            outAttrs.initialSelEnd = snap.selEnd
            EditorInfoCompat.setInitialSurroundingText(outAttrs, snap.text)
            owner.lastReportedSelStart = snap.selStart
            owner.lastReportedSelEnd = snap.selEnd
            owner.lastReportedCompStart = -1
            owner.lastReportedCompEnd = -1
            val conn = WordCraftInputConnection(this, owner)
            owner.activeConnection = conn
            return conn
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
            if (event != null && owner.handleHardwareOrImeKey(event)) {
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
            if (event != null && owner.handleHardwareOrImeKey(event)) {
                return true
            }
            return super.onKeyUp(keyCode, event)
        }
    }

    private inner class WordCraftInputConnection(
        view: View,
        private val owner: WordCraftActivity
    ) : BaseInputConnection(view, false) {
        @Volatile
        private var extractedTextMonitor: ExtractedTextRequest? = null

        fun clearMonitor() {
            extractedTextMonitor = null
        }

        private fun snapshotForIc(): ImeSnapshot = synchronized(owner.imeSessionLock) {
            owner.imeSession.virtualSnapshot()
        }

        private fun buildExtractedText(snap: ImeSnapshot, req: ExtractedTextRequest?): ExtractedText =
            ExtractedText().apply {
                val hintMax = req?.hintMaxChars ?: 0
                val maxChars = if (hintMax > 0) hintMax else Int.MAX_VALUE
                val rawText = if (snap.text.length > maxChars) {
                    snap.text.substring(0, ImeEditSession.clampU16BoundaryDown(snap.text, maxChars))
                } else {
                    snap.text
                }
                text = rawText
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = snap.selStart.coerceIn(0, rawText.length)
                selectionEnd = snap.selEnd.coerceIn(selectionStart, rawText.length)
                flags = if (selectionStart != selectionEnd) ExtractedText.FLAG_SELECTING else 0
            }

        fun notifyExtractedTextIfMonitored(mgr: InputMethodManager, view: View, snap: ImeSnapshot) {
            val req = extractedTextMonitor ?: return
            mgr.updateExtractedText(view, req.token, buildExtractedText(snap, req))
        }

        private inline fun applySessionEdit(edit: (ImeEditSession) -> ImeBatchFlush?): Boolean {
            val (flush, inBatch, snap) = synchronized(owner.imeSessionLock) {
                val batchFlush = edit(owner.imeSession)
                val batchActive = owner.imeSession.isInBatch()
                Triple(batchFlush, batchActive, if (!batchActive) owner.imeSession.virtualSnapshot() else null)
            }
            owner.flushImeBatch(flush)
            if (!inBatch && snap != null) {
                owner.reportSnapshotToImm(snap)
            }
            return true
        }

        override fun closeConnection() {
            extractedTextMonitor = null
            synchronized(owner.imeSessionLock) {
                owner.imeSession.resetConnectionState()
            }
            super.closeConnection()
        }

        override fun beginBatchEdit(): Boolean {
            synchronized(owner.imeSessionLock) {
                owner.imeSession.beginBatchEdit()
            }
            return true
        }

        override fun endBatchEdit(): Boolean {
            val (stillInBatch, flush, snap) = synchronized(owner.imeSessionLock) {
                val (inBatch, batchFlush) = owner.imeSession.endBatchEdit()
                Triple(inBatch, batchFlush, if (!inBatch) owner.imeSession.virtualSnapshot() else null)
            }
            owner.flushImeBatch(flush)
            if (!stillInBatch && snap != null) {
                owner.reportSnapshotToImm(snap)
            }
            return stillInBatch
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            if (n <= 0) {
                return ""
            }
            val snap = snapshotForIc()
            val end = snap.selStart.coerceIn(0, snap.text.length)
            val start = ImeEditSession.clampU16BoundaryUp(snap.text, (end - n).coerceAtLeast(0)).coerceAtMost(end)
            return snap.text.substring(start, end)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            if (n <= 0) {
                return ""
            }
            val snap = snapshotForIc()
            val start = snap.selEnd.coerceIn(0, snap.text.length)
            val end = ImeEditSession.clampU16BoundaryDown(snap.text, (start + n).coerceAtMost(snap.text.length)).coerceAtLeast(start)
            return snap.text.substring(start, end)
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val snap = snapshotForIc()
            val start = snap.selStart.coerceIn(0, snap.text.length)
            val end = snap.selEnd.coerceIn(start, snap.text.length)
            return if (start == end) null else snap.text.substring(start, end)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
            val snap = snapshotForIc()
            val selStart = snap.selStart.coerceIn(0, snap.text.length)
            val selEnd = snap.selEnd.coerceIn(selStart, snap.text.length)
            val beforeStart = ImeEditSession.clampU16BoundaryUp(
                snap.text,
                (selStart - beforeLength.coerceAtLeast(0)).coerceAtLeast(0),
            ).coerceAtMost(selStart)
            val afterEnd = ImeEditSession.clampU16BoundaryDown(
                snap.text,
                (selEnd + afterLength.coerceAtLeast(0)).coerceAtMost(snap.text.length),
            ).coerceAtLeast(selEnd)
            return SurroundingText(
                snap.text.substring(beforeStart, afterEnd),
                selStart - beforeStart,
                selEnd - beforeStart,
                beforeStart,
            )
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (request != null) {
                extractedTextMonitor = if ((flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR) != 0) request else null
            }
            return buildExtractedText(snapshotForIc(), request)
        }

        override fun getCursorCapsMode(reqModes: Int): Int {
            if (reqModes == 0) {
                return 0
            }
            val snap = snapshotForIc()
            return TextUtils.getCapsMode(snap.text, snap.selStart.coerceIn(0, snap.text.length), reqModes)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            applySessionEdit { it.setComposingText(text, newCursorPosition) }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            setComposingText(text, newCursorPosition)

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            applySessionEdit { it.setComposingRegion(start, end) }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
            setComposingRegion(start, end)

        override fun finishComposingText(): Boolean =
            applySessionEdit { it.finishComposingText() }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            applySessionEdit { it.commitText(text, newCursorPosition) }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            commitText(text, newCursorPosition)

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun replaceText(
            start: Int,
            end: Int,
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?,
        ): Boolean = applySessionEdit { it.replaceText(start, end, text, newCursorPosition) }

        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            applySessionEdit { it.deleteSurroundingText(beforeLength, afterLength) }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            applySessionEdit { it.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }

        override fun setSelection(start: Int, end: Int): Boolean =
            applySessionEdit { it.setSelection(start, end) }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (owner.handleHardwareOrImeKey(event)) {
                return true
            }
            return super.sendKeyEvent(event)
        }
    }

    private external fun nativeOnInsets(left: Int, top: Int, right: Int, bottom: Int, imeBottom: Int)
    private external fun nativeOnImeBatchStart()
    private external fun nativeOnImeStagePreedit(text: String, cursorStart: Int, cursorEnd: Int)
    private external fun nativeOnImeStageCommit(text: String)
    private external fun nativeOnImeStageDeleteSurrounding(before: Int, after: Int)
    private external fun nativeOnImeStageSetSelectionRelative(deltaAnchorCp: Int, deltaFocusCp: Int)
    private external fun nativeOnImeStageKey(keycode: Int, pressed: Int)
    private external fun nativeOnImeBatchCommit(seq: Long)
    private external fun nativeOnImeKey(keycode: Int, pressed: Int)
    private external fun nativeOnImeHidden()
    private external fun nativeOnFilePickerResult(pathsJoined: String, urisJoined: String, errorMsg: String)
    private external fun nativeOnOpenFilePath(stagingPath: String, contentUri: String)
    private external fun nativeOnSystemNightMode(night: Int)
    private external fun nativeOnPointerIsMouse(isMouse: Int)
    private external fun nativeOnMouseWheel(hScroll: Float, vScroll: Float)
    private external fun nativeOnMouseHover(xPx: Float, yPx: Float)
}
