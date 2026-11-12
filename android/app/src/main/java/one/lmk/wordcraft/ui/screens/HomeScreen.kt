package one.lmk.wordcraft.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.lmk.wordcraft.R
import one.lmk.wordcraft.ui.theme.AppThemeMode
import one.lmk.wordcraft.ui.theme.WordBluePrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isTablet: Boolean,
    currentTheme: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    onNewDocument: () -> Unit,
    onOpenDocumentUri: (Uri) -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onOpenDocumentUri(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = WordBluePrimary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val next = when (currentTheme) {
                            AppThemeMode.DARK -> AppThemeMode.LIGHT
                            AppThemeMode.LIGHT -> AppThemeMode.SEPIA
                            AppThemeMode.SEPIA -> AppThemeMode.DARK
                        }
                        onThemeChange(next)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Zmień motyw"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isTablet) {
            // Layout dla tabletu: Dwie kolumny, szeroki układ
            TabletHomeLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                onNewDocument = onNewDocument,
                onOpenFile = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword",
                            "application/vnd.oasis.opendocument.text",
                            "text/plain",
                            "text/markdown"
                        )
                    )
                }
            )
        } else {
            // Layout dla telefonu: Wzorem Worda mobilnego — duży, czytelny kafelek "Otwórz plik"
            PhoneHomeLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                onNewDocument = onNewDocument,
                onOpenFile = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword",
                            "application/vnd.oasis.opendocument.text",
                            "text/plain",
                            "text/markdown"
                        )
                    )
                }
            )
        }
    }
}

@Composable
fun PhoneHomeLayout(
    modifier: Modifier = Modifier,
    onNewDocument: () -> Unit,
    onOpenFile: () -> Unit
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Duży kafelek "Otwórz plik"
            LargeActionCard(
                title = stringResource(R.string.open_file),
                description = stringResource(R.string.open_file_desc),
                icon = Icons.Default.FolderOpen,
                accentColor = WordBluePrimary,
                onClick = onOpenFile
            )
        }

        item {
            // Kafelek "Nowy pusty dokument"
            LargeActionCard(
                title = stringResource(R.string.new_document),
                description = stringResource(R.string.new_file_desc),
                icon = Icons.Default.Add,
                accentColor = MaterialTheme.colorScheme.secondary,
                onClick = onNewDocument
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recent_documents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_recent_documents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun TabletHomeLayout(
    modifier: Modifier = Modifier,
    onNewDocument: () -> Unit,
    onOpenFile: () -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = "Rozpocznij pracę z WordCraft",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LargeActionCard(
                    title = stringResource(R.string.open_file),
                    description = stringResource(R.string.open_file_desc),
                    icon = Icons.Default.FolderOpen,
                    accentColor = WordBluePrimary,
                    onClick = onOpenFile
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                LargeActionCard(
                    title = stringResource(R.string.new_document),
                    description = stringResource(R.string.new_file_desc),
                    icon = Icons.Default.Add,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onClick = onNewDocument
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.recent_documents),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_recent_documents),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun LargeActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = accentColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}
