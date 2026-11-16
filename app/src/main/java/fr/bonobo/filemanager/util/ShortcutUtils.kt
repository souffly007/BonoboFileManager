package fr.bonobo.filemanager.util

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import fr.bonobo.filemanager.MainActivity
import fr.bonobo.filemanager.R

object ShortcutUtils {

    fun createCategoryShortcut(context: Context, id: String, label: String, iconRes: Int? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)

            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("shortcut_category", id)
                }

                val pinShortcutInfo = ShortcutInfo.Builder(context, id)
                    .setShortLabel(label)
                    .setIcon(Icon.createWithResource(context, iconRes ?: R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .build()

                shortcutManager.requestPinShortcut(pinShortcutInfo, null)
            }
        }
    }
}
