package fr.bonobo.filemanager.presentation.ui.main

import fr.bonobo.filemanager.domain.model.FileItem

data class ClipboardState(
    val file: FileItem,
    val isMove: Boolean
)
