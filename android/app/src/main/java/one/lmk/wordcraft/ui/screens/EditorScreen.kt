package one.lmk.wordcraft.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.lmk.wordcraft.bridge.WordCraftBridge
import one.lmk.wordcraft.ui.components.RibbonTab
import one.lmk.wordcraft.ui.components.TabletRibbon
import one.lmk.wordcraft.ui.theme.AppThemeMode

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

    // Oblicz liczbę stron i wyrenderuj bitmapy w tle
    LaunchedEffect(sessionHandle, refreshTrigger, currentTheme) {
        if (sessionHandle != 0L) {
            val count = withContext(Dispatchers.Default) {
                WordCraftBridge.getPageCount(sessionHandle).coerceAtLeast(1)
            }
            pageCount = count

            for (i in 0 until count) {
                val bmp = withContext(Dispatchers.Default) {
                    WordCraftBridge.renderPageToBitmap(
                        sessionHandle,
                        i,
                        scale = 1.5f,
                        darkMode = currentTheme == AppThemeMode.DARK
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
                // Dolny pasek narzędziowy dla telefonu (Bottom Action Bar)
                PhoneBottomActionBar(
                    onCommand = { cmd -> dispatchCommand(cmd, null) }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isTablet) {
                // Ribbon na górze dla tabletu
                TabletRibbon(
                    activeTab = activeRibbonTab,
                    onTabSelected = { activeRibbonTab = it },
                    onCommand = { cmd, p -> dispatchCommand(cmd, p) }
                )
            }

            // Scrollowalna lista stron dokumentu
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
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
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .shadow(6.dp, RoundedCornerShape(4.dp)),
                                shape = RoundedCornerShape(4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (currentTheme == AppThemeMode.DARK) Color(0xFF1E1E22) else Color.White
                                )
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Strona ${pageIdx + 1}",
                                    modifier = Modifier.wrapContentSize()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(300.dp, 420.dp)
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhoneBottomActionBar(
    onCommand: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onCommand("format.bold") }) {
                Icon(Icons.Default.FormatBold, contentDescription = "Pogrubienie")
            }
            IconButton(onClick = { onCommand("format.italic") }) {
                Icon(Icons.Default.FormatItalic, contentDescription = "Kursywa")
            }
            IconButton(onClick = { onCommand("format.underline") }) {
                Icon(Icons.Default.FormatUnderlined, contentDescription = "Podkreślenie")
            }
            IconButton(onClick = { onCommand("list.bullet") }) {
                Icon(Icons.Default.FormatListBulleted, contentDescription = "Lista punktowana")
            }
            IconButton(onClick = { onCommand("para.alignCenter") }) {
                Icon(Icons.Default.FormatAlignCenter, contentDescription = "Wyśrodkuj")
            }
        }
    }
}
