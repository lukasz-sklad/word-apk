package one.lmk.wordcraft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class RibbonTab(val title: String) {
    HOME("Narzędzia główne"),
    INSERT("Wstawianie"),
    LAYOUT("Układ"),
    REVIEW("Recenzja"),
    VIEW("Widok")
}

@Composable
fun TabletRibbon(
    activeTab: RibbonTab,
    onTabSelected: (RibbonTab) -> Unit,
    onCommand: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            // Zakładki Ribbona
            TabRow(
                selectedTabIndex = activeTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                RibbonTab.values().forEach { tab ->
                    Tab(
                        selected = activeTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = {
                            Text(
                                text = tab.title,
                                fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                        }
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Zawartość aktywnej zakładki wstążki
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (activeTab) {
                    RibbonTab.HOME -> {
                        RibbonButton(icon = Icons.Default.FormatBold, label = "Pogrubienie") {
                            onCommand("format.bold", null)
                        }
                        RibbonButton(icon = Icons.Default.FormatItalic, label = "Kursywa") {
                            onCommand("format.italic", null)
                        }
                        RibbonButton(icon = Icons.Default.FormatUnderlined, label = "Podkreślenie") {
                            onCommand("format.underline", null)
                        }
                        RibbonDivider()
                        RibbonButton(icon = Icons.Default.FormatAlignLeft, label = "Do lewej") {
                            onCommand("para.alignLeft", null)
                        }
                        RibbonButton(icon = Icons.Default.FormatAlignCenter, label = "Środek") {
                            onCommand("para.alignCenter", null)
                        }
                        RibbonButton(icon = Icons.Default.FormatAlignRight, label = "Do prawej") {
                            onCommand("para.alignRight", null)
                        }
                        RibbonDivider()
                        RibbonButton(icon = Icons.Default.FormatListBulleted, label = "Lista") {
                            onCommand("list.bullet", null)
                        }
                        RibbonButton(icon = Icons.Default.FormatListNumbered, label = "Numeracja") {
                            onCommand("list.number", null)
                        }
                    }
                    RibbonTab.INSERT -> {
                        RibbonButton(icon = Icons.Default.TableChart, label = "Tabela") {
                            onCommand("insert.table", null)
                        }
                        RibbonButton(icon = Icons.Default.Image, label = "Obraz") {
                            onCommand("insert.picture", null)
                        }
                        RibbonButton(icon = Icons.Default.InsertPageBreak, label = "Strona") {
                            onCommand("insert.pageBreak", null)
                        }
                    }
                    RibbonTab.LAYOUT -> {
                        RibbonButton(icon = Icons.Default.ViewColumn, label = "Kolumny") {
                            onCommand("layout.columns", null)
                        }
                        RibbonButton(icon = Icons.Default.AspectRatio, label = "Marginesy") {
                            onCommand("layout.margins", null)
                        }
                    }
                    RibbonTab.REVIEW -> {
                        RibbonButton(icon = Icons.Default.Spellcheck, label = "Pisownia") {
                            onCommand("tools.spelling", null)
                        }
                        RibbonButton(icon = Icons.Default.Comment, label = "Komentarz") {
                            onCommand("insert.comment", null)
                        }
                    }
                    RibbonTab.VIEW -> {
                        RibbonButton(icon = Icons.Default.ZoomIn, label = "Powiększ") {
                            onCommand("view.zoomIn", null)
                        }
                        RibbonButton(icon = Icons.Default.ZoomOut, label = "Pomniejsz") {
                            onCommand("view.zoomOut", null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RibbonButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun RibbonDivider() {
    Divider(
        modifier = Modifier
            .height(32.dp)
            .width(1.dp)
            .padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
