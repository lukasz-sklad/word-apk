package one.lmk.wordcraft.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
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
    // Domyślny zoom 1.6f = komfortowa czytelność na telefonie (bez konieczności lupy)
    var zoomFactor by remember { mutableStateOf(1.6f) }
    var pageCount by remember { mutableStateOf(1) }
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    var refreshTrigger by remember { mutableStateOf(0) }
    var activeCaret by remember { mutableStateOf<WordCraftBridge.CaretInfo?>(null) }

    val sentinel = "••••••••••"
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var typingBuffer by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length))) }
    var quickTextInput by remember { mutableStateOf("") }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val density = LocalDensity.current

    val systemInDark = isSystemInDarkTheme()
    val isDark = when (currentTheme) {
        AppThemeMode.SYSTEM -> systemInDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT, AppThemeMode.SEPIA -> false
    }

    // Animacja migania kursora karetki
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

    // Wyższa rozdzielczość renderowania z uwzględnieniem powiększenia (super-ostre fonty)
    val renderScale = remember(zoomFactor) {
        (2.2f * zoomFactor.coerceIn(0.8f, 2.5f)).coerceIn(1.8f, 4.0f)
    }

    LaunchedEffect(sessionHandle, refreshTrigger, isDark, renderScale) {
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
                        scale = renderScale,
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

    // Szerokość bazowa kartki (dopasowana do ekranu telefonu z marginesami 12dp)
    val basePageWidthDp = remember(screenWidthDp, isTablet) {
        if (isTablet) 600.dp else (screenWidthDp - 24.dp).coerceAtLeast(280.dp)
    }
    val currentCardWidthDp = basePageWidthDp * zoomFactor

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
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                actions = {
                    // Szybka kontrola powiększenia widoku (Zoom Out / Procent / Zoom In)
                    IconButton(onClick = {
                        zoomFactor = (zoomFactor - 0.25f).coerceAtLeast(0.75f)
                    }) {
                        Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }

                    TextButton(onClick = {
                        // Kliknięcie przełącza wygodne poziomy powiększenia: 100% -> 160% -> 220% -> 100%
                        zoomFactor = when {
                            zoomFactor < 1.3f -> 1.6f
                            zoomFactor < 1.9f -> 2.2f
                            else -> 1.0f
                        }
                    }) {
                        Text("${(zoomFactor * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    IconButton(onClick = {
                        zoomFactor = (zoomFactor + 0.25f).coerceAtMost(3.5f)
                    }) {
                        Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }

                    IconButton(onClick = { dispatchCommand("edit.undo", null) }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Undo, contentDescription = "Cofnij")
                    }
                    IconButton(onClick = { dispatchCommand("edit.redo", null) }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Redo, contentDescription = "Ponów")
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        // Szybkie pole wprowadzania tekstu (styl komunikatora, bez ucinania wysokości)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = quickTextInput,
                                onValueChange = { quickTextInput = it },
                                placeholder = { Text("Pisz tekst...", fontSize = 14.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(20.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = WordBluePrimary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                ),
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
                            Spacer(modifier = Modifier.width(6.dp))
                            FilledIconButton(
                                onClick = {
                                    if (quickTextInput.isNotEmpty()) {
                                        WordCraftBridge.insertText(sessionHandle, quickTextInput)
                                        quickTextInput = ""
                                        refreshTrigger++
                                    }
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = WordBluePrimary)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Wstaw", tint = Color.White)
                            }
                        }

                        // Dolny pasek narzędzi: Klawiatura, Usuń (Backspace), A+, A-, B, I, Zaznacz, Akapit
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }) {
                                Icon(Icons.Default.Keyboard, contentDescription = "Klawiatura")
                            }

                            // Dedykowany przycisk usuwania znaku (Backspace)
                            IconButton(onClick = {
                                WordCraftBridge.deleteBackward(sessionHandle)
                                refreshTrigger++
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Usuń znak")
                            }

                            // Powiększ czcionkę w dokumencie (A+)
                            TextButton(onClick = { dispatchCommand("format.growFont", null) }) {
                                Text("A+", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }

                            // Zmniejsz czcionkę w dokumencie (A-)
                            TextButton(onClick = { dispatchCommand("format.shrinkFont", null) }) {
                                Text("A-", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            IconButton(onClick = { dispatchCommand("format.bold", null) }) {
                                Icon(Icons.Default.FormatBold, contentDescription = "Pogrubienie")
                            }

                            IconButton(onClick = { dispatchCommand("format.italic", null) }) {
                                Icon(Icons.Default.FormatItalic, contentDescription = "Kursywa")
                            }

                            // Zaznacz słowo / akapit / rozszerz zaznaczenie
                            TextButton(onClick = { dispatchCommand("select.extend", null) }) {
                                Text("Zaznacz", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }

                            // Nowy akapit (Enter)
                            IconButton(onClick = {
                                WordCraftBridge.insertParagraph(sessionHandle)
                                refreshTrigger++
                            }) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "Nowy akapit", tint = WordBluePrimary)
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
            // Niewidoczne pole przechwytujące bezpośrednie wpisywanie z klawiatury fizycznej lub ekranowej
            // Używa bufora strażnika (sentinel), dzięki czemu Gboard nigdy nie ignoruje klawisza Backspace
            BasicTextField(
                value = typingBuffer,
                onValueChange = { newVal ->
                    if (newVal.text.length > sentinel.length) {
                        val inserted = newVal.text.substring(sentinel.length)
                        WordCraftBridge.insertText(sessionHandle, inserted)
                        refreshTrigger++
                    } else if (newVal.text.length < sentinel.length) {
                        val count = sentinel.length - newVal.text.length
                        repeat(count) {
                            WordCraftBridge.deleteBackward(sessionHandle)
                        }
                        refreshTrigger++
                    }
                    typingBuffer = TextFieldValue(sentinel, TextRange(sentinel.length))
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Backspace) {
                            WordCraftBridge.deleteBackward(sessionHandle)
                            refreshTrigger++
                            true
                        } else false
                    },
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

            // Tło obszaru roboczego
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

            val horizontalScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(deskColor)
                    .horizontalScroll(horizontalScrollState)
                    .pointerInput(Unit) {
                        // Płynny gest dwoma palcami (pinch-to-zoom)
                        detectTransformGestures { _, _, zoom, _ ->
                            zoomFactor = (zoomFactor * zoom).coerceIn(0.75f, 3.5f)
                        }
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) {
                                    zoomFactor = (zoomFactor * zoom).coerceIn(0.75f, 3.5f)
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pageCount) { pageIdx ->
                        val bmp = pageBitmaps[pageIdx]
                        if (bmp != null) {
                            val pageW = WordCraftBridge.getPageWidth(sessionHandle, pageIdx).coerceAtLeast(1f)
                            val pageH = WordCraftBridge.getPageHeight(sessionHandle, pageIdx).coerceAtLeast(1f)

                            val cardWidthPx = with(density) { currentCardWidthDp.toPx() }
                            val cardHeightPx = cardWidthPx * (pageH / pageW)
                            val cardHeightDp = with(density) { cardHeightPx.toDp() }

                            Card(
                                modifier = Modifier
                                    .width(currentCardWidthDp)
                                    .height(cardHeightDp)
                                    .shadow(8.dp, RoundedCornerShape(2.dp))
                                    .pointerInput(pageIdx, currentCardWidthDp) {
                                        detectTapGestures(
                                            onTap = { offset ->
                                                val ptX = (offset.x / cardWidthPx) * pageW
                                                val ptY = (offset.y / cardHeightPx) * pageH
                                                WordCraftBridge.setCaret(sessionHandle, pageIdx, ptX, ptY)
                                                focusRequester.requestFocus()
                                                keyboardController?.show()
                                                refreshTrigger++
                                            },
                                            onDoubleTap = { offset ->
                                                val ptX = (offset.x / cardWidthPx) * pageW
                                                val ptY = (offset.y / cardHeightPx) * pageH
                                                WordCraftBridge.setCaret(sessionHandle, pageIdx, ptX, ptY)
                                                WordCraftBridge.runCommand(sessionHandle, "select.word", null)
                                                refreshTrigger++
                                            },
                                            onLongPress = { offset ->
                                                val ptX = (offset.x / cardWidthPx) * pageW
                                                val ptY = (offset.y / cardHeightPx) * pageH
                                                WordCraftBridge.setCaret(sessionHandle, pageIdx, ptX, ptY)
                                                WordCraftBridge.runCommand(sessionHandle, "select.paragraph", null)
                                                refreshTrigger++
                                            }
                                        )
                                    },
                                shape = RoundedCornerShape(2.dp),
                                colors = CardDefaults.cardColors(containerColor = paperBgColor)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Strona ${pageIdx + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.FillBounds
                                    )

                                    // Kursor karetki (caret) precyzyjnie wyskalowany do bieżącego powiększenia
                                    val caret = activeCaret
                                    if (caret != null && caret.pageIdx == pageIdx && caretAlpha > 0.1f) {
                                        val caretLeftDp = with(density) {
                                            ((caret.x / pageW) * cardWidthPx).toDp()
                                        }
                                        val caretTopDp = with(density) {
                                            ((caret.top / pageH) * cardHeightPx).toDp()
                                        }
                                        val caretHeightDp = with(density) {
                                            ((caret.height / pageH) * cardHeightPx).toDp().coerceAtLeast(16.dp)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .offset(x = caretLeftDp, y = caretTopDp)
                                                .size(width = 2.5.dp, height = caretHeightDp)
                                                .background(if (isDark) Color(0xFF64B5F6) else WordBluePrimary)
                                        )
                                    }
                                }
                            }
                        } else {
                            val estimatedH = currentCardWidthDp * 1.414f
                            Card(
                                modifier = Modifier
                                    .width(currentCardWidthDp)
                                    .height(estimatedH)
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
