package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.bonobo.filemanager.util.MimeTypeUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    path: String,
    onNavigateBack: () -> Unit
) {
    val currentFile = remember { File(path) }
    val parentDir = remember { currentFile.parentFile }
    
    // Lister toutes les images dans le même dossier pour permettre le "slide"
    val imageFiles = remember {
        parentDir?.listFiles()?.filter { file ->
            val mime = MimeTypeUtils.fromFile(file)
            mime?.startsWith("image/") == true
        }?.sortedBy { it.name.lowercase() } ?: listOf(currentFile)
    }
    
    val initialIndex = remember { 
        val index = imageFiles.indexOfFirst { it.absolutePath == path }
        if (index >= 0) index else 0
    }
    
    val pagerState = rememberPagerState(initialPage = initialIndex) {
        imageFiles.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (imageFiles.isNotEmpty()) imageFiles[pagerState.currentPage].name else "Visionneuse",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp
            ) { page ->
                val file = imageFiles[page]
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
