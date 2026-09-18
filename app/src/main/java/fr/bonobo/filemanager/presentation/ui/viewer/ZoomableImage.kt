package fr.bonobo.filemanager.presentation.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp

@Composable
fun ZoomableImage(
    bitmap: ImageBitmap? = null,
    model: Any? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {},
    showZoomControls: Boolean = false
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        if (scale <= 1.05f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
        onScaleChanged(scale)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Box(
        modifier = modifier
            .transformable(transformState)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        scale = 2.5f
                    }
                    onScaleChanged(scale)
                })
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )
        } else {
            coil.compose.AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )
        }

        if (showZoomControls) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                shape = androidx.compose.material3.MaterialTheme.shapes.medium,
                tonalElevation = 4.dp
            ) {
                Column {
                    IconButton(onClick = {
                        scale = (scale + 0.5f).coerceAtMost(6f)
                        onScaleChanged(scale)
                    }) { Icon(Icons.Default.Add, "Zoomer") }
                    IconButton(onClick = {
                        scale = (scale - 0.5f).coerceAtLeast(1f)
                        if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                        onScaleChanged(scale)
                    }) { Icon(Icons.Default.Remove, "Dézoomer") }
                    IconButton(onClick = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                        onScaleChanged(scale)
                    }) { Icon(Icons.Default.CenterFocusStrong, "Taille normale") }
                }
            }
        }
    }
}
