package fr.bonobo.filemanager.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.domain.model.FileItem

@Composable
fun FileTypeIcon(item: FileItem, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val icon = when {
        item.isDirectory -> Icons.Outlined.Folder
        item.mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
        item.mimeType?.startsWith("video/") == true -> Icons.Outlined.Movie
        item.mimeType?.startsWith("audio/") == true -> Icons.Outlined.MusicNote
        item.name.endsWith(".crypt", true) -> Icons.Outlined.Lock
        item.name.substringAfterLast('.').lowercase() in listOf("zip", "rar", "7z", "gz", "tar") -> Icons.Outlined.Inventory2
        item.name.endsWith(".apk", true) -> Icons.Outlined.Android
        item.name.endsWith(".pdf", true) -> Icons.Outlined.PictureAsPdf
        else -> Icons.Outlined.Description
    }
    IconTile(icon, modifier, size, if (item.isDirectory) 0 else 1)
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 48.dp, tone: Int = 0) {
    val colors = MaterialTheme.colorScheme
    val background = when (tone) { 1 -> colors.secondaryContainer; 2 -> colors.tertiaryContainer; else -> colors.primaryContainer }
    val foreground = when (tone) { 1 -> colors.onSecondaryContainer; 2 -> colors.onTertiaryContainer; else -> colors.onPrimaryContainer }
    Surface(modifier = modifier.size(size), shape = RoundedCornerShape(14.dp), color = background) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(size * 0.56f))
        }
    }
}
