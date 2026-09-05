package fr.bonobo.filemanager.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun BreadcrumbNav(
    path: String,
    rootPath: String,
    onNavigate: (String) -> Unit
) {
    // On ne montre que la partie après rootPath pour simplifier
    val relativePath = if (path.startsWith(rootPath)) {
        path.substring(rootPath.length).trimStart(File.separatorChar)
    } else {
        path.trimStart(File.separatorChar)
    }

    val parts = relativePath.split(File.separator)
        .filter { it.isNotBlank() }

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icône Home pour revenir à la racine (ex: /storage/emulated/0)
        Icon(
            imageVector = Icons.Default.Home,
            contentDescription = "Racine",
            modifier = Modifier
                .clickable { onNavigate(rootPath) }
                .padding(4.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        var accumulatedPath = rootPath
        
        parts.forEachIndexed { index, part ->
            accumulatedPath = if (accumulatedPath.endsWith(File.separator)) {
                accumulatedPath + part
            } else {
                accumulatedPath + File.separator + part
            }
            
            val thisPath = accumulatedPath

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Text(
                text = part,
                modifier = Modifier
                    .clickable { onNavigate(thisPath) }
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == parts.lastIndex) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
