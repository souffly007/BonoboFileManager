package fr.bonobo.filemanager.presentation.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.bonobo.filemanager.presentation.ui.apps.AppManagerScreen
import fr.bonobo.filemanager.presentation.ui.cleaner.CleanerScreen
import fr.bonobo.filemanager.presentation.ui.dashboard.AboutScreen
import fr.bonobo.filemanager.presentation.ui.dashboard.LicensesScreen
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
import fr.bonobo.filemanager.presentation.ui.viewer.PdfViewerScreen
import fr.bonobo.filemanager.presentation.ui.viewer.EpubViewerScreen
import fr.bonobo.filemanager.presentation.ui.viewer.ArchivePreviewScreen
import fr.bonobo.filemanager.presentation.ui.viewer.OfficeDocumentViewerScreen

object Routes {
    const val VAULT = "vault"
    const val DASHBOARD = "dashboard"
    const val ABOUT = "about"

    const val LICENSES = "licenses"
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
    const val PDF = "pdf/{path}"
    const val EPUB = "epub/{path}"
    const val ARCHIVE = "archive/{path}"
    const val OFFICE_DOCUMENT = "office_document/{path}"

    fun category(type: String): String = "category/$type"
    fun image(path: String): String = "image/${Uri.encode(path)}"
    fun video(path: String): String = "video/${Uri.encode(path)}"
    fun audio(path: String): String = "audio/${Uri.encode(path)}"
    fun text(path: String): String = "text/${Uri.encode(path)}"
    fun transfer(path: String): String = "transfer/${Uri.encode(path)}"
    fun diff(path1: String, path2: String): String = "diff/${Uri.encode(path1)}/${Uri.encode(path2)}"
    fun pdf(path: String) = "pdf/${Uri.encode(path)}"
    fun epub(path: String) = "epub/${Uri.encode(path)}"
    fun archive(path: String) = "archive/${Uri.encode(path)}"
    fun officeDocument(path: String) = "office_document/${Uri.encode(path)}"
}

@Composable
fun AppNavigation(
    onExit: () -> Unit = {},
    receivedPath: String? = null,
    onReceivedConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    // Partage du ViewModel pour garder l'état entre les écrans si besoin
    val sharedViewModel: MainViewModel = hiltViewModel()
    val remoteViewModel: RemoteViewModel = hiltViewModel()

    // « Retour » never empties the NavHost: popping the last screen (Accueil) left a black screen
    // with the app still open. A second tap during the exit animation is ignored (the screen
    // below is not RESUMED yet), and « Retour » from the first screen closes the app.
    val goBack: () -> Unit = {
        val current = navController.currentBackStackEntry
        if (current?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
            if (navController.previousBackStackEntry != null) navController.popBackStack() else onExit()
        }
    }

    androidx.compose.runtime.LaunchedEffect(receivedPath) {
        receivedPath?.let { path ->
            val file = java.io.File(path)
            val route = when (file.extension.lowercase()) {
                "pdf" -> Routes.pdf(path)
                "epub" -> Routes.epub(path)
                "jpg", "png", "webp", "gif" -> Routes.image(path)
                "mp4" -> Routes.video(path)
                "mp3" -> Routes.audio(path)
                "zip", "rar", "7z" -> Routes.archive(path)
                "docx", "xlsx", "pptx", "odt", "ods", "odp" -> Routes.officeDocument(path)
                else -> if (file.isDirectory) Routes.FILES else Routes.text(path)
            }
            if (file.isDirectory) {
                sharedViewModel.openFolder(path, resetNavigationRoot = true)
                // Repeated USB plug-ins must not stack file screens.
                navController.navigate(route) { launchSingleTop = true }
            } else navController.navigate(route)
            onReceivedConsumed()
        }
    }
    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD
    ) {
        composable(Routes.DASHBOARD) {
            // « Retour » on Accueil really closes Bonobo (Android 12+ would only send it to the background).
            androidx.activity.compose.BackHandler { onExit() }
            DashboardScreen(
                viewModel = sharedViewModel,
                onNavigateToExplorer = {
                    sharedViewModel.resetToLocalRoot()
                    navController.navigate(Routes.FILES)
                },
                onNavigateToPath = { path ->
                    sharedViewModel.setActivePanel(1)
                    sharedViewModel.openFolder(path, resetNavigationRoot = true)
                    navController.navigate(Routes.FILES)
                },
                onNavigateToCategory = { type ->
                    if (type == "Coffre-fort") {
                        navController.navigate(Routes.VAULT)
                    } else {
                        sharedViewModel.loadCategory(type)
                        navController.navigate(Routes.category(type))
                    }
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

        composable(Routes.VAULT) {
            fr.bonobo.filemanager.presentation.ui.vault.VaultScreen(onBack = { goBack() })
        }

        composable(Routes.APPS) {
            AppManagerScreen(
                onNavigateBack = {
                    goBack()
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
                    goBack()
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
                    goBack()
                },
                onNavigateToLicenses = {
                    navController.navigate(Routes.LICENSES)
                }
            )
        }

        composable(Routes.LICENSES) {
            LicensesScreen(
                onNavigateBack = {
                    goBack()
                }
            )
        }

        composable(Routes.NETWORK) {
            NetworkScreen(
                onNavigateBack = {
                    goBack()
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
            val localState by sharedViewModel.uiState.collectAsStateWithLifecycle()
            RemoteExplorerScreen(
                viewModel = remoteViewModel,
                localClipboard = localState.clipboard,
                onLocalPasted = sharedViewModel::markPasted,
                onCancelLocalClipboard = sharedViewModel::clearClipboard,
                onNavigateBack = {
                    goBack()
                },
                onNavigateHome = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                onOpenText = { path -> navController.navigate("remote_text/${Uri.encode(path)}") },
                onOpenPdf = { path -> navController.navigate(Routes.pdf(path)) },
                onOpenOfficeDocument = { path -> navController.navigate(Routes.officeDocument(path)) },
                onOpenVideo = { path -> navController.navigate(Routes.video(path)) },
                onOpenAudio = { path -> navController.navigate(Routes.audio(path)) }
            )
        }

        composable("remote_text/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            TextEditorScreen(
                filePath = path,
                onNavigateBack = { goBack() },
                onSaveOverride = { text -> remoteViewModel.saveRemoteText(path, text) }
            )
        }

        composable(Routes.FILES) {
            MainScreen(
                viewModel = sharedViewModel,
                remoteViewModel = remoteViewModel,
                onOpenRemoteExplorer = { navController.navigate(Routes.REMOTE_FILES) },
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
                onOpenPdf = { path -> navController.navigate(Routes.pdf(path)) },
                onOpenEpub = { path -> navController.navigate(Routes.epub(path)) },
                onOpenArchive = { path -> navController.navigate(Routes.archive(path)) },
                onOpenText = { path ->
                    navController.navigate(Routes.text(path))
                },
                onOpenOfficeDocument = { path -> navController.navigate(Routes.officeDocument(path)) },
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
                remoteViewModel = remoteViewModel,
                onOpenRemoteExplorer = { navController.navigate(Routes.REMOTE_FILES) },
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
                onOpenPdf = { path -> navController.navigate(Routes.pdf(path)) },
                onOpenEpub = { path -> navController.navigate(Routes.epub(path)) },
                onOpenArchive = { path -> navController.navigate(Routes.archive(path)) },
                onOpenText = { path ->
                    navController.navigate(Routes.text(path))
                },
                onOpenOfficeDocument = { path -> navController.navigate(Routes.officeDocument(path)) },
                onOpenTransfer = { path ->
                    navController.navigate(Routes.transfer(path))
                },
                onOpenDiff = { p1, p2 ->
                    navController.navigate(Routes.diff(p1, p2))
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { goBack() })
        }

        composable(
            route = Routes.IMAGE,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            ImageViewerScreen(path = path, onNavigateBack = { goBack() })
        }

        composable(
            route = Routes.VIDEO,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            VideoPlayerScreen(videoPath = path, onNavigateBack = { goBack() })
        }

        composable(
            route = Routes.AUDIO,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            AudioPlayerScreen(audioPath = path, onNavigateBack = { goBack() })
        }

        composable(
            route = Routes.TEXT,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            TextEditorScreen(
                filePath = path,
                onNavigateBack = { goBack() }
            )
        }

        composable(
            route = Routes.TRANSFER,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            P2PTransferScreen(
                filePath = path,
                onNavigateBack = { goBack() }
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
                onNavigateBack = { goBack() }
            )
        }
        composable(Routes.PDF, arguments = listOf(navArgument("path") { type = NavType.StringType })) { e -> PdfViewerScreen(Uri.decode(e.arguments?.getString("path").orEmpty())) { goBack() } }
        composable(Routes.EPUB, arguments = listOf(navArgument("path") { type = NavType.StringType })) { e -> EpubViewerScreen(Uri.decode(e.arguments?.getString("path").orEmpty())) { goBack() } }
        composable(Routes.ARCHIVE, arguments = listOf(navArgument("path") { type = NavType.StringType })) { e -> ArchivePreviewScreen(Uri.decode(e.arguments?.getString("path").orEmpty())) { goBack() } }
        composable(Routes.OFFICE_DOCUMENT, arguments = listOf(navArgument("path") { type = NavType.StringType })) { e -> OfficeDocumentViewerScreen(Uri.decode(e.arguments?.getString("path").orEmpty())) { goBack() } }
    }
}
