package fr.bonobo.filemanager.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.bonobo.filemanager.domain.model.FileItem
import fr.bonobo.filemanager.util.FileUtils
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemCard(
    item: FileItem,
    isSelected: Boolean = false,
    isTrash: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onRestore: () -> Unit,
    onEncrypt: () -> Unit,
    onDecrypt: () -> Unit,
    onTransfer: () -> Unit,
    onProperties: () -> Unit = {},
    thumbnailSize: String = "MEDIUM",
    isGrid: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(value = false) }

    val preset = ThumbnailPreset.fromKey(thumbnailSize)
    val isMedia = item.mimeType?.startsWith("image/") == true || item.mimeType?.startsWith("video/") == true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isGrid) 0.dp else 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                item.isDirectory -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        @Composable
        fun preview(modifier: Modifier) {
            Box(modifier, contentAlignment = Alignment.Center) {
                // A themed placeholder remains visible while loading or on error.
                FileTypeIcon(item, size = if (isGrid) 56.dp else 44.dp)
                if (isMedia) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(File(item.path)).crossfade(true).build(),
                        contentDescription = "Aperçu de ${item.name}",
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )
                }
                if (isSelected) {
                    Surface(modifier = Modifier.align(if (isGrid) Alignment.TopStart else Alignment.TopEnd).padding(4.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.CheckCircle, "Sélectionné", tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(3.dp).size(22.dp))
                    }
                }
            }
        }
        @Composable
        fun details(modifier: Modifier = Modifier) {
            Column(modifier) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (isGrid) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (!item.isDirectory) Text(FileUtils.formatSize(item.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Composable
        fun actions() {
            if (item.isFavorite && !isTrash) {
                Icon(Icons.Default.Favorite, "Favori", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
            }
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options"
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (isTrash) {
                        DropdownMenuItem(
                            text = { Text("Restaurer") },
                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRestore()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer définitivement") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error
                            )
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Propriétés") },
                            onClick = {
                                menuExpanded = false
                                onProperties()
                            }
                        )
                        if (item.isDirectory || item.isFavorite) {
                        DropdownMenuItem(
                            text = { Text(if (item.isFavorite) "Retirer des favoris" else "Ajouter aux favoris") },
                            onClick = {
                                menuExpanded = false
                                onFavorite()
                            }
                        )
                        }
                        DropdownMenuItem(
                            text = { Text("Copier") },
                            onClick = {
                                menuExpanded = false
                                onCopy()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Déplacer") },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Renommer") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Partager") },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Transfert Direct (Wi-Fi)") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onTransfer()
                            }
                        )

                        if (item.name.endsWith(".crypt")) {
                            DropdownMenuItem(
                                text = { Text("Décrypter") },
                                leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDecrypt()
                                }
                            )
                        } else if (!item.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Crypter") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEncrypt()
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("Compresser") },
                            onClick = {
                                menuExpanded = false
                                onCompress()
                            }
                        )

                        if (fr.bonobo.filemanager.util.MimeTypeUtils.isArchive(File(item.path))) {
                            DropdownMenuItem(
                                text = { Text("Extraire vers…") },
                                onClick = {
                                    menuExpanded = false
                                    onExtract()
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("Mettre à la corbeille") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }
        }
        if (isGrid) {
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(10.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    preview(Modifier.fillMaxWidth().aspectRatio(1f))
                    Surface(Modifier.align(Alignment.BottomEnd), shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
                    }
                }
                details(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Leave room for the title and a 48 dp menu target, even in a narrow split panel.
                val mediaSize = minOf(preset.listSize.dp, maxWidth * 0.34f)
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    preview(Modifier.size(if (isMedia) mediaSize else 44.dp))
                    details(Modifier.weight(1f))
                    actions()
                }
            }
        }
    }
}
