package one.lmk.wordcraft.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.lmk.wordcraft.bridge.WordCraftBridge
import one.lmk.wordcraft.ui.components.RibbonTab
import one.lmk.wordcraft.ui.components.TabletRibbon
import one.lmk.wordcraft.ui.theme.AppThemeMode
import one.lmk.wordcraft.ui.theme.WordBluePrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    sessionHandle: Long,
    documentTitle: String,
    isTablet: Boolean,
    currentTheme: AppThemeMode,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var activeRibbonTab by remember { mutableStateOf(RibbonTab.HOME) }
    var scale by remember { mutableStateOf(1.0f) }
    var pageCount by remember { mutableStateOf(1) }
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    var refreshTrigger by remember { mutableStateOf(0) }
    var activeCaret by remember { mutableStateOf<WordCraftBridge.CaretInfo?>(null) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var typingBuffer by remember { mutableStateOf(TextFieldValue("")) }
    var quickTextInput by remember { mutableStateOf("") }

    val systemInDark = isSystemInDarkTheme()
    val isDark = when (currentTheme) {
        AppThemeMode.SYSTEM -> systemInDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT, AppThemeMode.SEPIA -> false
    }

    // Animacja migania kursora
    val infiniteTransition = rememberInfiniteTransition(label = "caretTransition")
    val caretAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 500
                0f at 501
                0f at 1000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "caretAlpha"
    )

    // Renderowanie stron i pobieranie geometrii kursora
    LaunchedEffect(sessionHandle, refreshTrigger, isDark) {
        if (sessionHandle != 0L) {
            val count = withContext(Dispatchers.Default) {
                WordCraftBridge.getPageCount(sessionHandle).coerceAtLeast(1)
            }
            pageCount = count

            val caret = withContext(Dispatchers.Default) {
                WordCraftBridge.getCaretInfo(sessionHandle)
            }
            activeCaret = caret

            for (i in 0 until count) {
                val bmp = withContext(Dispatchers.Default) {
                    WordCraftBridge.renderPageToBitmap(
                        sessionHandle,
                        i,
                        scale = 1.5f,
                        darkMode = isDark
                    )
                }
                if (bmp != null) {
                    pageBitmaps[i] = bmp
                }
            }
        }
    }

    fun dispatchCommand(cmd: String, params: String?) {
        if (sessionHandle != 0L) {
            WordCraftBridge.runCommand(sessionHandle, cmd, params)
            refreshTrigger++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = documentTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    IconButton(onClick = { dispatchCommand("edit.undo", null) }) {
                        Icon(imageVector = Icons.Default.Undo, contentDescription = "Cofnij")
                    }
                    IconButton(onClick = { dispatchCommand("edit.redo", null) }) {
                        Icon(imageVector = Icons.Default.Redo, contentDescription = "Ponów")
                    }
                    IconButton(onClick = onSave) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "Zapisz")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (!isTablet) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Szybkie pole wprowadzania tekstu
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = quickTextInput,
                                onValueChange = { quickTextInput = it },
                                placeholder = { Text("Pisz tekst...", fontSize = 14.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Send
                                ),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (quickTextInput.isNotEmpty()) {
                                            WordCraftBridge.insertText(sessionHandle, quickTextInput)
                                            quickTextInput = ""
                                            refreshTrigger++
                                        }
                                    }
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(onClick = {
                                if (quickTextInput.isNotEmpty()) {
                                    WordCraftBridge.insertText(sessionHandle, quickTextInput)
                                    quickTextInput = ""
                                    refreshTrigger++
                                }
                            }) {
                                Icon(Icons.Default.Send, contentDescription = "Wstaw", tint = WordBluePrimary)
                            }
                        }

                        // Dolny pasek narzędzi formatowania
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }) {
                                Icon(Icons.Default.Keyboard, contentDescription = "Klawiatura")
                            }
                            IconButton(onClick = { dispatchCommand("format.bold", null) }) {
                                Icon(Icons.Default.FormatBold, contentDescription = "Pogrubienie")
                            }
                            IconButton(onClick = { dispatchCommand("format.italic", null) }) {
                                Icon(Icons.Default.FormatItalic, contentDescription = "Kursywa")
                            }
                            IconButton(onClick = { dispatchCommand("format.underline", null) }) {
                                Icon(Icons.Default.FormatUnderlined, contentDescription = "Podkreślenie")
                            }
                            IconButton(onClick = { dispatchCommand("list.bullet", null) }) {
                                Icon(Icons.Default.FormatListBulleted, contentDescription = "Lista")
                            }
                            IconButton(onClick = {
                                WordCraftBridge.insertParagraph(sessionHandle)
                                refreshTrigger++
                            }) {
                                Icon(Icons.Default.KeyboardReturn, contentDescription = "Nowy akapit")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Ukryte pole BasicTextField obsługujące wprowadzanie bezpośrednio z klawiatury
            BasicTextField(
                value = typingBuffer,
                onValueChange = { newVal ->
                    if (newVal.text.length > typingBuffer.text.length) {
                        val inserted = newVal.text.substring(typingBuffer.text.length)
                        WordCraftBridge.insertText(sessionHandle, inserted)
                        refreshTrigger++
                    } else if (newVal.text.length < typingBuffer.text.length) {
                        WordCraftBridge.deleteBackward(sessionHandle)
                        refreshTrigger++
                    }
                    typingBuffer = newVal
                    if (typingBuffer.text.length > 30) {
                        typingBuffer = TextFieldValue("")
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = true,
                    imeAction = ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        WordCraftBridge.insertParagraph(sessionHandle)
                        refreshTrigger++
                    }
                )
            )

            if (isTablet) {
                TabletRibbon(
                    activeTab = activeRibbonTab,
                    onTabSelected = { activeRibbonTab = it },
                    onCommand = { cmd, p -> dispatchCommand(cmd, p) }
                )
            }

            // Obszar roboczy dokumentu z obsługą gestu zoom i kliknięcia
            val deskColor = when (currentTheme) {
                AppThemeMode.SYSTEM -> if (systemInDark) Color(0xFF141417) else Color(0xFFEBEBF0)
                AppThemeMode.DARK -> Color(0xFF141417)
                AppThemeMode.LIGHT -> Color(0xFFEBEBF0)
                AppThemeMode.SEPIA -> Color(0xFFEADBCE)
            }

            val paperBgColor = when (currentTheme) {
                AppThemeMode.SYSTEM -> if (systemInDark) Color(0xFF1F1F24) else Color.White
                AppThemeMode.DARK -> Color(0xFF1F1F24)
                AppThemeMode.LIGHT -> Color.White
                AppThemeMode.SEPIA -> Color(0xFFFDF9F0)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(deskColor)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 3.0f)
                        }
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pageCount) { pageIdx ->
                        val bmp = pageBitmaps[pageIdx]
                        if (bmp != null) {
                            val density = LocalDensity.current
                            val pageW = WordCraftBridge.getPageWidth(sessionHandle, pageIdx).coerceAtLeast(1f)
                            val pageH = WordCraftBridge.getPageHeight(sessionHandle, pageIdx).coerceAtLeast(1f)

                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .shadow(8.dp, RoundedCornerShape(2.dp))
                                    .pointerInput(pageIdx) {
                                        detectTapGestures { offset ->
                                            val ptX = (offset.x / bmp.width) * pageW
                                            val ptY = (offset.y / bmp.height) * pageH
                                            WordCraftBridge.setCaret(sessionHandle, pageIdx, ptX, ptY)
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                            refreshTrigger++
                                        }
                                    },
                                shape = RoundedCornerShape(2.dp),
                                colors = CardDefaults.cardColors(containerColor = paperBgColor)
                            ) {
                                Box {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Strona ${pageIdx + 1}",
                                        modifier = Modifier.wrapContentSize()
                                    )

                                    // Kursor caret na aktywnej stronie
                                    val caret = activeCaret
                                    if (caret != null && caret.pageIdx == pageIdx && caretAlpha > 0.1f) {
                                        val caretLeftDp = with(density) {
                                            ((caret.x / pageW) * bmp.width).toDp()
                                        }
                                        val caretTopDp = with(density) {
                                            ((caret.top / pageH) * bmp.height).toDp()
                                        }
                                        val caretHeightDp = with(density) {
                                            ((caret.height / pageH) * bmp.height).toDp().coerceAtLeast(16.dp)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .offset(x = caretLeftDp, y = caretTopDp)
                                                .size(width = 2.dp, height = caretHeightDp)
                                                .background(if (isDark) Color(0xFF64B5F6) else WordBluePrimary)
                                        )
                                    }
                                }
                            }
                        } else {
                            Card(
                                modifier = Modifier
                                    .size(320.dp, 450.dp)
                                    .shadow(4.dp, RoundedCornerShape(2.dp)),
                                shape = RoundedCornerShape(2.dp),
                                colors = CardDefaults.cardColors(containerColor = paperBgColor)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = WordBluePrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
