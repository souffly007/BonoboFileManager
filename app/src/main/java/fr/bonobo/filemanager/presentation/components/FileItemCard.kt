package fr.bonobo.filemanager.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
    thumbnailSize: String = "MEDIUM"
) {
    var menuExpanded by remember { mutableStateOf(value = false) }

    val iconSize = when(thumbnailSize) {
        "SMALL" -> 32.dp
        "LARGE" -> 64.dp
        else -> 40.dp
    }
    
    val isMedia = (item.mimeType?.startsWith("image/") == true) || 
                  (item.mimeType?.startsWith("video/") == true)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Sélectionné",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize)
                )
            } else if (isMedia) {
                AsyncImage(
                    model = File(item.path),
                    contentDescription = null,
                    modifier = Modifier
                        .size(iconSize)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                    error = remember { null }
                )
            } else {
                Icon(
                    imageVector = if (item.isDirectory) {
                        Icons.Default.Folder
                    } else {
                        Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = if (item.isDirectory) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.size(iconSize)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                if (!item.isDirectory) {
                    Text(
                        text = FileUtils.formatSize(item.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.isFavorite && !isSelected && !isTrash) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Favori",
                    tint = Color(0xFFE91E63)
                )
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
                            text = { Text(if (item.isFavorite) "Retirer des favoris" else "Ajouter aux favoris") },
                            onClick = {
                                menuExpanded = false
                                onFavorite()
                            }
                        )
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

                        if (item.name.lowercase().endsWith(".zip")) {
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
    }
}
