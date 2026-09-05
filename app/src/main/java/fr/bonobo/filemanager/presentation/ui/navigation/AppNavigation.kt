package fr.bonobo.filemanager.presentation.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.bonobo.filemanager.presentation.ui.apps.AppManagerScreen
import fr.bonobo.filemanager.presentation.ui.cleaner.CleanerScreen
import fr.bonobo.filemanager.presentation.ui.dashboard.AboutScreen
import fr.bonobo.filemanager.presentation.ui.dashboard.DashboardScreen
import fr.bonobo.filemanager.presentation.ui.main.MainScreen
import fr.bonobo.filemanager.presentation.ui.main.MainViewModel
import fr.bonobo.filemanager.presentation.ui.network.NetworkScreen
import fr.bonobo.filemanager.presentation.ui.network.P2PTransferScreen
import fr.bonobo.filemanager.presentation.ui.network.RemoteExplorerScreen
import fr.bonobo.filemanager.presentation.ui.network.RemoteViewModel
import fr.bonobo.filemanager.presentation.ui.settings.SettingsScreen
import fr.bonobo.filemanager.presentation.ui.viewer.AudioPlayerScreen
import fr.bonobo.filemanager.presentation.ui.viewer.ImageViewerScreen
import fr.bonobo.filemanager.presentation.ui.viewer.TextDiffScreen
import fr.bonobo.filemanager.presentation.ui.viewer.TextEditorScreen
import fr.bonobo.filemanager.presentation.ui.viewer.VideoPlayerScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val ABOUT = "about"
    const val NETWORK = "network"
    const val APPS = "apps"
    const val CLEANER = "cleaner"
    const val FILES = "files"
    const val REMOTE_FILES = "remote_files"
    const val CATEGORY = "category/{type}"
    const val SETTINGS = "settings"
    const val IMAGE = "image/{path}"
    const val VIDEO = "video/{path}"
    const val AUDIO = "audio/{path}"
    const val TEXT = "text/{path}"
    const val TRANSFER = "transfer/{path}"
    const val DIFF = "diff/{path1}/{path2}"

    fun category(type: String): String = "category/$type"
    fun image(path: String): String = "image/${Uri.encode(path)}"
    fun video(path: String): String = "video/${Uri.encode(path)}"
    fun audio(path: String): String = "audio/${Uri.encode(path)}"
    fun text(path: String): String = "text/${Uri.encode(path)}"
    fun transfer(path: String): String = "transfer/${Uri.encode(path)}"
    fun diff(path1: String, path2: String): String = "diff/${Uri.encode(path1)}/${Uri.encode(path2)}"
}

@Composable
fun AppNavigation(
    onExit: () -> Unit = {}
) {
    val navController = rememberNavController()
    // Partage du ViewModel pour garder l'état entre les écrans si besoin
    val sharedViewModel: MainViewModel = hiltViewModel()
    val remoteViewModel: RemoteViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD
    ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                viewModel = sharedViewModel,
                onNavigateToExplorer = {
                    sharedViewModel.resetToLocalRoot()
                    navController.navigate(Routes.FILES)
                },
                onNavigateToCategory = { type ->
                    sharedViewModel.loadCategory(type)
                    navController.navigate(Routes.category(type))
                },
                onNavigateToNetwork = {
                    navController.navigate(Routes.NETWORK)
                },
                onNavigateToApps = {
                    navController.navigate(Routes.APPS)
                },
                onNavigateToCleaner = {
                    navController.navigate(Routes.CLEANER)
                },
                onNavigateToAbout = {
                    navController.navigate(Routes.ABOUT)
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onExit = onExit
            )
        }

        composable(Routes.APPS) {
            AppManagerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                onBackupTo = { app ->
                    sharedViewModel.setPendingAppBackup(app)
                    navController.navigate(Routes.FILES)
                }
            )
        }

        composable(Routes.CLEANER) {
            CleanerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.NETWORK) {
            NetworkScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                onConnect = { connection ->
                    remoteViewModel.connect(connection)
                    navController.navigate(Routes.REMOTE_FILES)
                }
            )
        }

        composable(Routes.REMOTE_FILES) {
            RemoteExplorerScreen(
                viewModel = remoteViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.FILES) {
            MainScreen(
                viewModel = sharedViewModel,
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                onOpenImage = { path ->
                    navController.navigate(Routes.image(path))
                },
                onOpenVideo = { path ->
                    navController.navigate(Routes.video(path))
                },
                onOpenAudio = { path ->
                    navController.navigate(Routes.audio(path))
                },
                onOpenText = { path ->
                    navController.navigate(Routes.text(path))
                },
                onOpenTransfer = { path ->
                    navController.navigate(Routes.transfer(path))
                },
                onOpenDiff = { p1, p2 ->
                    navController.navigate(Routes.diff(p1, p2))
                }
            )
        }

        composable(
            route = Routes.CATEGORY,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) {
            MainScreen(
                viewModel = sharedViewModel,
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                onOpenImage = { path ->
                    navController.navigate(Routes.image(path))
                },
                onOpenVideo = { path ->
                    navController.navigate(Routes.video(path))
                },
                onOpenAudio = { path ->
                    navController.navigate(Routes.audio(path))
                },
                onOpenText = { path ->
                    navController.navigate(Routes.text(path))
                },
                onOpenTransfer = { path ->
                    navController.navigate(Routes.transfer(path))
                },
                onOpenDiff = { p1, p2 ->
                    navController.navigate(Routes.diff(p1, p2))
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }

        composable(
            route = Routes.IMAGE,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            ImageViewerScreen(path = path, onNavigateBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.VIDEO,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            VideoPlayerScreen(videoPath = path, onNavigateBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.AUDIO,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            AudioPlayerScreen(audioPath = path, onNavigateBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.TEXT,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            TextEditorScreen(
                filePath = path,
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.TRANSFER,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            P2PTransferScreen(
                filePath = path,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.DIFF,
            arguments = listOf(
                navArgument("path1") { type = NavType.StringType },
                navArgument("path2") { type = NavType.StringType }
            )
        ) { entry ->
            val p1 = Uri.decode(entry.arguments?.getString("path1").orEmpty())
            val p2 = Uri.decode(entry.arguments?.getString("path2").orEmpty())
            TextDiffScreen(
                file1Path = p1,
                file2Path = p2,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
