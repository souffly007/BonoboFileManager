package fr.bonobo.filemanager.presentation.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(path: String, onNavigateBack: () -> Unit) {
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    val pagerState = rememberPagerState { pages.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) { withContext(Dispatchers.IO) { runCatching {
        val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(fd).use { renderer ->
            pages = (0 until renderer.pageCount).map { index -> renderer.openPage(index).let { page ->
                Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close()
                }
            } }
        }
    }.onFailure { error = it.message ?: "PDF illisible" } } }
    Scaffold(topBar = { TopAppBar(title = { Text(if (pages.isEmpty()) File(path).name else "${File(path).name}  •  ${pages.size} pages") }, navigationIcon = { IconButton(onNavigateBack) { Icon(Icons.Default.ArrowBack, "Retour") } }) }) { padding ->
        if (error != null) Text(error!!, Modifier.padding(padding).padding(16.dp))
        else if (pages.isEmpty()) CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
        else Box(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = currentScale <= 1.05f,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 0.dp
            ) { page ->
                val bitmap = pages[page]
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    ZoomableImage(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page du PDF",
                        modifier = Modifier.fillMaxSize(),
                        onScaleChanged = { scale -> currentScale = scale },
                        showZoomControls = true
                    )
                }
            }

            Surface(
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                tonalElevation = 4.dp
            ) {
                IconButton(
                    enabled = pagerState.currentPage > 0 && currentScale <= 1.05f,
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
                ) { Icon(Icons.Default.ArrowBack, "Page précédente") }
            }

            Surface(
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(end = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                tonalElevation = 4.dp
            ) {
                IconButton(
                    enabled = pagerState.currentPage < pages.lastIndex && currentScale <= 1.05f,
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }
                ) { Icon(Icons.Default.ArrowForward, "Page suivante") }
            }
        }
    }
}
