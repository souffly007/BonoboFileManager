package fr.bonobo.filemanager.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.bonobo.filemanager.domain.model.AppItem
import fr.bonobo.filemanager.domain.repository.IAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IAppRepository {

    override suspend fun getInstalledApps(): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        apps.map { info ->
            val file = File(info.publicSourceDir)
            AppItem(
                name = info.loadLabel(pm).toString(),
                packageName = info.packageName,
                icon = info.loadIcon(pm),
                size = file.length(),
                versionName = getVersionName(info.packageName),
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                apkPath = info.publicSourceDir
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun backupApp(app: AppItem, destinationPath: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(app.apkPath)
            val backupDir = if (destinationPath != null) {
                File(destinationPath)
            } else {
                File(Environment.getExternalStorageDirectory(), "Bonobo_Backups")
            }
            
            if (!backupDir.exists()) backupDir.mkdirs()
            
            val destination = File(backupDir, "${app.name}_${app.versionName}.apk")
            source.copyTo(destination, overwrite = true)
            destination.absolutePath
        }
    }

    private fun getVersionName(packageName: String): String {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }
}
