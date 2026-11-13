package one.lmk.wordcraft

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import com.google.androidgamesdk.GameActivity
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

    @Volatile
    private var currentSurroundingText = ""

    @Volatile
    private var currentSelStart = 0

    @Volatile
    private var currentSelEnd = 0

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

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureCutoutMode()
        super.onCreate(savedInstanceState)

        imeProxy = ImeProxyView(this)
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        root?.addView(imeProxy, ViewGroup.LayoutParams(1, 1))

        installInsetsListener()
        installFocusGuard()
        syncSystemNightMode(resources.configuration)
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setLightSystemBars(if (isNight) 0 else 1)
        ensureTtsInitializedAsync(useGoogleFallback = false)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
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
        ViewCompat.requestApplyInsets(window.decorView)
        if (imeWanted) {
            imeProxy.post {
                if (imeWanted) {
                    showImeNow()
                }
            }
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
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = if (imeVisible) ime.bottom.coerceAtLeast(1) else 0
            nativeOnInsets(safe.left, safe.top, safe.right, safe.bottom, imeBottom)

            val wasVisible = lastImeVisible
            lastImeVisible = imeVisible
            if (wasVisible && !imeVisible) {
                imeWanted = false
                activeConnection?.clearComposingState()
                nativeOnImeHidden()
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        ViewCompat.requestApplyInsets(window.decorView)
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
        activeConnection?.clearComposingState()
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

    @Suppress("unused")
    fun updateImeSurrounding(text: String, selStart: Int, selEnd: Int) {
        val clampedStart = selStart.coerceIn(0, text.length)
        val clampedEnd = selEnd.coerceIn(clampedStart, text.length)
        currentSurroundingText = text
        currentSelStart = clampedStart
        currentSelEnd = clampedEnd
        runOnUiThread {
            val conn = activeConnection ?: return@runOnUiThread
            if (!conn.isBusyWithImeEdit()) {
                imm()?.updateSelection(imeProxy, clampedStart, clampedEnd, -1, -1)
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
            nativeOnImeCommit(String(Character.toChars(uni)))
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

        override fun onCheckIsTextEditor(): Boolean = !owner.imeSuppressed

        override fun onTouchEvent(event: MotionEvent?): Boolean = false

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
            val text = owner.currentSurroundingText
            val start = owner.currentSelStart.coerceIn(0, text.length)
            val end = owner.currentSelEnd.coerceIn(start, text.length)
            outAttrs.initialSelStart = start
            outAttrs.initialSelEnd = end
            EditorInfoCompat.setInitialSurroundingText(outAttrs, text)
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
        private var composingText = ""
        private var batchDepth = 0

        fun clearComposingState() {
            composingText = ""
        }

        fun isBusyWithImeEdit(): Boolean = composingText.isNotEmpty() || batchDepth > 0

        override fun beginBatchEdit(): Boolean {
            batchDepth += 1
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0) {
                batchDepth -= 1
            }
            return batchDepth > 0
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            if (n <= 0) {
                return ""
            }
            val text = owner.currentSurroundingText
            val end = owner.currentSelStart.coerceIn(0, text.length)
            val start = (end - n).coerceAtLeast(0)
            return text.substring(start, end)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            if (n <= 0) {
                return ""
            }
            val text = owner.currentSurroundingText
            val start = owner.currentSelEnd.coerceIn(0, text.length)
            val end = (start + n).coerceAtMost(text.length)
            return text.substring(start, end)
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val text = owner.currentSurroundingText
            val start = owner.currentSelStart.coerceIn(0, text.length)
            val end = owner.currentSelEnd.coerceIn(start, text.length)
            return if (start == end) null else text.substring(start, end)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString().orEmpty()
            composingText = s
            val cursor = if (newCursorPosition > 0) s.codePointCount(0, s.length) else 0
            owner.nativeOnImePreedit(s, cursor, cursor)
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = start == end

        override fun finishComposingText(): Boolean {
            val pending = composingText
            composingText = ""
            if (pending.isNotEmpty()) {
                owner.nativeOnImeCommit(pending)
            } else {
                owner.nativeOnImePreedit("", 0, 0)
            }
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString().orEmpty()
            composingText = ""
            owner.nativeOnImeCommit(s)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength <= 0 && afterLength <= 0) {
                return true
            }
            val text = owner.currentSurroundingText
            val selStart = owner.currentSelStart.coerceIn(0, text.length)
            val selEnd = owner.currentSelEnd.coerceIn(selStart, text.length)
            val beforeChars = if (beforeLength <= 0) {
                0
            } else if (beforeLength <= selStart) {
                Character.codePointCount(text, selStart - beforeLength, selStart).coerceAtLeast(1)
            } else {
                beforeLength
            }
            val afterChars = if (afterLength <= 0) {
                0
            } else if (selEnd + afterLength <= text.length) {
                Character.codePointCount(text, selEnd, selEnd + afterLength).coerceAtLeast(1)
            } else {
                afterLength
            }
            owner.nativeOnImeDeleteSurrounding(beforeChars, afterChars)
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0 || afterLength > 0) {
                owner.nativeOnImeDeleteSurrounding(beforeLength.coerceAtLeast(0), afterLength.coerceAtLeast(0))
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (owner.handleHardwareOrImeKey(event)) {
                return true
            }
            return super.sendKeyEvent(event)
        }
    }

    private external fun nativeOnInsets(left: Int, top: Int, right: Int, bottom: Int, imeBottom: Int)
    private external fun nativeOnImePreedit(text: String, cursorStart: Int, cursorEnd: Int)
    private external fun nativeOnImeCommit(text: String)
    private external fun nativeOnImeDeleteSurrounding(before: Int, after: Int)
    private external fun nativeOnImeKey(keycode: Int, pressed: Int)
    private external fun nativeOnImeHidden()
    private external fun nativeOnFilePickerResult(pathsJoined: String, urisJoined: String, errorMsg: String)
    private external fun nativeOnOpenFilePath(stagingPath: String, contentUri: String)
    private external fun nativeOnSystemNightMode(night: Int)
    private external fun nativeOnPointerIsMouse(isMouse: Int)
    private external fun nativeOnMouseWheel(hScroll: Float, vScroll: Float)
    private external fun nativeOnMouseHover(xPx: Float, yPx: Float)
}
