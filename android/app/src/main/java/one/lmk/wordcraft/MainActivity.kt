package one.lmk.wordcraft

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import one.lmk.wordcraft.bridge.WordCraftBridge
import one.lmk.wordcraft.ui.screens.EditorScreen
import one.lmk.wordcraft.ui.screens.HomeScreen
import one.lmk.wordcraft.ui.theme.AppThemeMode
import one.lmk.wordcraft.ui.theme.WordCraftTheme

enum class Screen {
    HOME, EDITOR
}

class MainActivity : ComponentActivity() {

    private var activeSessionHandle by mutableStateOf(0L)
    private var activeDocumentTitle by mutableStateOf("Bez tytułu.docx")
    private var activeDocumentUri: Uri? = null

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri: Uri? ->
        if (uri != null && activeSessionHandle != 0L) {
            val bytes = WordCraftBridge.saveDocx(activeSessionHandle)
            if (bytes != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
                activeDocumentUri = uri
                Toast.makeText(this, "Zapisano dokument", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Obsługa Intent otwarcia pliku z innej aplikacji
        intent?.data?.let { uri ->
            openDocumentFromUri(uri)
        }

        setContent {
            var currentTheme by remember { mutableStateOf(AppThemeMode.SYSTEM) }
            var currentScreen by remember {
                mutableStateOf(if (activeSessionHandle != 0L) Screen.EDITOR else Screen.HOME)
            }

            val configuration = LocalConfiguration.current
            val isTablet = configuration.screenWidthDp >= 600

            WordCraftTheme(themeMode = currentTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.HOME -> {
                            HomeScreen(
                                isTablet = isTablet,
                                currentTheme = currentTheme,
                                onThemeChange = { currentTheme = it },
                                onNewDocument = {
                                    if (activeSessionHandle != 0L) {
                                        WordCraftBridge.destroySession(activeSessionHandle)
                                    }
                                    activeSessionHandle = WordCraftBridge.createSession(null, null)
                                    activeDocumentTitle = "Nowy dokument.docx"
                                    activeDocumentUri = null
                                    currentScreen = Screen.EDITOR
                                },
                                onOpenDocumentUri = { uri ->
                                    openDocumentFromUri(uri)
                                    currentScreen = Screen.EDITOR
                                }
                            )
                        }
                        Screen.EDITOR -> {
                            EditorScreen(
                                sessionHandle = activeSessionHandle,
                                documentTitle = activeDocumentTitle,
                                isTablet = isTablet,
                                currentTheme = currentTheme,
                                onBack = {
                                    currentScreen = Screen.HOME
                                },
                                onSave = {
                                    val currentUri = activeDocumentUri
                                    if (currentUri != null) {
                                        val bytes = WordCraftBridge.saveDocx(activeSessionHandle)
                                        if (bytes != null) {
                                            contentResolver.openOutputStream(currentUri)?.use { it.write(bytes) }
                                            Toast.makeText(this, "Zapisano pomyślnie", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        saveFileLauncher.launch(activeDocumentTitle)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openDocumentFromUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                if (activeSessionHandle != 0L) {
                    WordCraftBridge.destroySession(activeSessionHandle)
                }
                val fileName = getFileName(uri) ?: "Dokument.docx"
                activeSessionHandle = WordCraftBridge.createSession(bytes, fileName)
                activeDocumentTitle = fileName
                activeDocumentUri = uri
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Błąd podczas otwierania pliku", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name ?: uri.lastPathSegment
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeSessionHandle != 0L) {
            WordCraftBridge.destroySession(activeSessionHandle)
            activeSessionHandle = 0L
        }
    }
}
