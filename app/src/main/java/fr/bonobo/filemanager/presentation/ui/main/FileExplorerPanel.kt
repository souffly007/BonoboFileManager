package fr.bonobo.filemanager.presentation.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.bonobo.filemanager.presentation.components.BreadcrumbNav
import fr.bonobo.filemanager.presentation.components.FileItemCard
import fr.bonobo.filemanager.util.verticalGridScrollbar
import fr.bonobo.filemanager.util.verticalScrollbar
import java.io.File

@Composable
fun FileExplorerPanel(
    state: MainUiState,
    onNavigate: (String) -> Unit,
    onItemClick: (fr.bonobo.filemanager.domain.model.FileItem) -> Unit,
    onItemLongClick: (fr.bonobo.filemanager.domain.model.FileItem) -> Unit,
    onItemOption: (fr.bonobo.filemanager.domain.model.FileItem, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbNav(
            path = state.currentPath,
            rootPath = state.rootPath,
            onNavigate = onNavigate
        )

        if (state.isLoading) {
            Text(text = "Chargement…", modifier = Modifier.padding(16.dp))
        }

        if (state.isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize().verticalGridScrollbar(gridState),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.files) { item ->
                    FileItemCard(
                        item = item,
                        isSelected = state.selectedPaths.contains(item.path),
                        isTrash = state.categoryName == "Corbeille",
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                        onFavorite = { onItemOption(item, "FAVORITE") },
                        onCopy = { onItemOption(item, "COPY") },
                        onMove = { onItemOption(item, "MOVE") },
                        onRename = { onItemOption(item, "RENAME") },
                        onDelete = { onItemOption(item, "DELETE") },
                        onShare = { onItemOption(item, "SHARE") },
                        onCompress = { onItemOption(item, "COMPRESS") },
                        onExtract = { onItemOption(item, "EXTRACT") },
                        onRestore = { onItemOption(item, "RESTORE") },
                        onEncrypt = { onItemOption(item, "ENCRYPT") },
                        onDecrypt = { onItemOption(item, "DECRYPT") },
                        onTransfer = { onItemOption(item, "TRANSFER") },
                        thumbnailSize = state.thumbnailSize
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                state = listState
            ) {
                items(state.files) { item ->
                    FileItemCard(
                        item = item,
                        isSelected = state.selectedPaths.contains(item.path),
                        isTrash = state.categoryName == "Corbeille",
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                        onFavorite = { onItemOption(item, "FAVORITE") },
                        onCopy = { onItemOption(item, "COPY") },
                        onMove = { onItemOption(item, "MOVE") },
                        onRename = { onItemOption(item, "RENAME") },
                        onDelete = { onItemOption(item, "DELETE") },
                        onShare = { onItemOption(item, "SHARE") },
                        onCompress = { onItemOption(item, "COMPRESS") },
                        onExtract = { onItemOption(item, "EXTRACT") },
                        onRestore = { onItemOption(item, "RESTORE") },
                        onEncrypt = { onItemOption(item, "ENCRYPT") },
                        onDecrypt = { onItemOption(item, "DECRYPT") },
                        onTransfer = { onItemOption(item, "TRANSFER") },
                        thumbnailSize = state.thumbnailSize
                    )
                }
            }
        }
    }
}
