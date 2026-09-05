package fr.bonobo.filemanager.domain.model

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val size: Long,
    val versionName: String,
    val isSystemApp: Boolean,
    val apkPath: String
)
