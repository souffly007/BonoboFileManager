package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.util.MimeTypeUtils
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    path: String,
    onNavigateBack: () -> Unit
) {
    val currentFile = remember(path) { File(path) }
    val parentDir = remember(path) { currentFile.parentFile }

    // The viewer is a real gallery: every image in the current local folder can
    // be reached without returning to the explorer.
    val imageFiles = remember(path) {
        parentDir?.listFiles()?.filter { file ->
            val mime = MimeTypeUtils.fromFile(file)
            mime?.startsWith("image/") == true
        }?.sortedBy { it.name.lowercase() } ?: listOf(currentFile)
    }

    val initialIndex = remember(path, imageFiles) {
        imageFiles.indexOfFirst { it.absolutePath == path }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex) { imageFiles.size }
    val scope = rememberCoroutineScope()
    var currentScale by remember { mutableFloatStateOf(1f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = imageFiles.getOrNull(pagerState.currentPage)?.name ?: "Visionneuse",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (imageFiles.size > 1) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${imageFiles.size}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
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
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 12.dp,
                // When zoomed, horizontal gestures pan the picture instead of
                // unexpectedly changing image.
                userScrollEnabled = currentScale <= 1.05f,
                beyondViewportPageCount = 1
            ) { page ->
                val file = imageFiles[page]
                ZoomableImage(
                    model = file,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    onScaleChanged = { scale ->
                        if (page == pagerState.currentPage) currentScale = scale
                    }
                )
            }

            if (imageFiles.size > 1 && currentScale <= 1.05f) {
                if (pagerState.currentPage > 0) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        modifier = Modifier.align(Alignment.CenterStart).padding(10.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Image précédente")
                    }
                }
                if (pagerState.currentPage < imageFiles.lastIndex) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(10.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Image suivante")
                    }
                }
            }
        }
    }
}
