package com.example.ui.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.media.DetectedMediaType
import com.example.ui.components.CreatePackDialog
import com.example.ui.components.whatStickNavBarItemColors
import com.example.ui.components.whatStickNavRailItemColors
import com.example.ui.screens.AnimatedEditorScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MyStickersScreen
import com.example.ui.screens.PackDetailScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StaticEditorScreen
import com.example.ui.viewmodel.AnimatedEditorViewModel
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.StaticEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object MyStickersRoute

@Serializable
data object SettingsRoute

@Serializable
data class PackDetailRoute(val packId: String)

@Serializable
data class StaticEditorRoute(
    val mediaUriString: String? = null,
    val packId: String? = null,
    val stickerId: String? = null
)

@Serializable
data class AnimatedEditorRoute(
    val mediaUriString: String,
    val mediaTypeName: String,
    val targetPackId: String? = null
)

private data class TopTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: Any,
    val testTag: String
)

@Composable
fun WhatStickRoot(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val packs by mainViewModel.packs.collectAsStateWithLifecycle()
    val recentStickers by mainViewModel.recentStickers.collectAsStateWithLifecycle()
    val themeSettings by mainViewModel.themeSettings.collectAsStateWithLifecycle()

    var showCreatePackDialog by remember { mutableStateOf(false) }
    var pendingTargetPackId by remember { mutableStateOf<String?>(null) }

    fun handlePickedMedia(uri: Uri?) {
        if (uri == null) {
            pendingTargetPackId = null
            return
        }
        scope.launch {
            val res = mainViewModel.inspectMedia(uri)
            res.onSuccess { inspected ->
                val targetPack = pendingTargetPackId
                pendingTargetPackId = null
                withContext(Dispatchers.Main) {
                    when (inspected.type) {
                        DetectedMediaType.STATIC_IMAGE -> {
                            navController.navigate(
                                StaticEditorRoute(
                                    mediaUriString = inspected.uri.toString(),
                                    packId = targetPack
                                )
                            )
                        }
                        DetectedMediaType.ANIMATED_GIF,
                        DetectedMediaType.ANIMATED_WEBP,
                        DetectedMediaType.VIDEO -> {
                            navController.navigate(
                                AnimatedEditorRoute(
                                    mediaUriString = inspected.uri.toString(),
                                    mediaTypeName = inspected.type.name,
                                    targetPackId = targetPack
                                )
                            )
                        }
                        DetectedMediaType.UNSUPPORTED -> {
                            snackbarHostState.showSnackbar("Unsupported media format.")
                        }
                    }
                }
            }.onFailure { err ->
                pendingTargetPackId = null
                snackbarHostState.showSnackbar(err.message ?: "Could not open media file.")
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> handlePickedMedia(uri) }

    val gifSafPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> handlePickedMedia(uri) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val isTopLevel = currentDest == null ||
        currentDest.hasRoute<HomeRoute>() ||
        currentDest.hasRoute<MyStickersRoute>() ||
        currentDest.hasRoute<SettingsRoute>()

    val tabs = remember {
        listOf(
            TopTab("Home", Icons.Filled.Home, Icons.Outlined.Home, HomeRoute, "nav_tab_home"),
            TopTab(
                "My Stickers",
                Icons.Filled.Collections,
                Icons.Outlined.Collections,
                MyStickersRoute,
                "nav_tab_my_stickers"
            ),
            TopTab(
                "Settings",
                Icons.Filled.Settings,
                Icons.Outlined.Settings,
                SettingsRoute,
                "nav_tab_settings"
            )
        )
    }

    fun navigateTopTab(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (isTopLevel && !useRail) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = when (tab.route) {
                                HomeRoute -> currentDest?.hasRoute<HomeRoute>() == true
                                MyStickersRoute -> currentDest?.hasRoute<MyStickersRoute>() == true
                                SettingsRoute -> currentDest?.hasRoute<SettingsRoute>() == true
                                else -> false
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopTab(tab.route) },
                                colors = whatStickNavBarItemColors(),
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isTopLevel && useRail) {
                    NavigationRail {
                        tabs.forEach { tab ->
                            val selected = when (tab.route) {
                                HomeRoute -> currentDest?.hasRoute<HomeRoute>() == true
                                MyStickersRoute -> currentDest?.hasRoute<MyStickersRoute>() == true
                                SettingsRoute -> currentDest?.hasRoute<SettingsRoute>() == true
                                else -> false
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopTab(tab.route) },
                                colors = whatStickNavRailItemColors(),
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                packs = packs,
                                recentStickers = recentStickers,
                                storage = mainViewModel.repository.storage,
                                onPickAnyMedia = {
                                    pendingTargetPackId = null
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                onPickPhoto = {
                                    pendingTargetPackId = null
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickGif = {
                                    pendingTargetPackId = null
                                    gifSafPicker.launch(arrayOf("image/gif", "image/webp"))
                                },
                                onPickVideo = {
                                    pendingTargetPackId = null
                                    mediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                onCreatePackClick = { showCreatePackDialog = true },
                                onOpenPack = { packId ->
                                    navController.navigate(PackDetailRoute(packId))
                                },
                                onOpenAllPacks = { navigateTopTab(MyStickersRoute) },
                                onOpenSticker = { packId, _ ->
                                    navController.navigate(PackDetailRoute(packId))
                                }
                            )
                        }

                        composable<MyStickersRoute> {
                            MyStickersScreen(
                                packs = packs,
                                storage = mainViewModel.repository.storage,
                                onCreatePackClick = { showCreatePackDialog = true },
                                onOpenPack = { packId ->
                                    navController.navigate(PackDetailRoute(packId))
                                },
                                onDeletePack = { packId -> mainViewModel.deletePack(packId) }
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                themeSettings = themeSettings,
                                onAccentSelected = { mainViewModel.setAccentColor(it) },
                                onThemeModeSelected = { mainViewModel.setThemeMode(it) }
                            )
                        }

                        composable<PackDetailRoute> { entry ->
                            val args = entry.toRoute<PackDetailRoute>()
                            val pack by mainViewModel.observePack(args.packId)
                                .collectAsStateWithLifecycle(initialValue = null)
                            PackDetailScreen(
                                packWithStickers = pack,
                                storage = mainViewModel.repository.storage,
                                onBack = { navController.popBackStack() },
                                onAddStickerClick = { isAnimated ->
                                    pendingTargetPackId = args.packId
                                    if (isAnimated) {
                                        mediaPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                        )
                                    } else {
                                        mediaPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                },
                                onEditStaticSticker = { packId, stickerId ->
                                    navController.navigate(
                                        StaticEditorRoute(packId = packId, stickerId = stickerId)
                                    )
                                },
                                onRenamePack = { id, name, pub ->
                                    mainViewModel.renamePack(id, name, pub)
                                },
                                onDeleteSticker = { sticker ->
                                    mainViewModel.deleteSticker(sticker)
                                },
                                onSetAsTrayIcon = { packId, sticker ->
                                    mainViewModel.setStickerAsTrayIcon(packId, sticker)
                                },
                                onUpdateTrayIconFromUri = { packId, uri ->
                                    mainViewModel.updatePackTrayIconFromUri(packId, uri)
                                },
                                onUpdateStickerEmojis = { stickerId, emojis ->
                                    mainViewModel.updateStickerEmojis(stickerId, emojis)
                                },
                                onDeletePack = { packId ->
                                    mainViewModel.deletePack(packId)
                                }
                            )
                        }

                        composable<StaticEditorRoute> { entry ->
                            val args = entry.toRoute<StaticEditorRoute>()
                            val editorVm: StaticEditorViewModel = viewModel()
                            LaunchedEffect(args.mediaUriString, args.packId, args.stickerId) {
                                if (args.stickerId != null && args.packId != null) {
                                    editorVm.loadExistingSticker(args.packId, args.stickerId)
                                } else if (args.mediaUriString != null) {
                                    editorVm.loadNewImage(Uri.parse(args.mediaUriString))
                                }
                            }
                            StaticEditorScreen(
                                viewModel = editorVm,
                                packs = packs,
                                initialTargetPackId = args.packId,
                                onCreatePack = { name, publisher, isAnimated ->
                                    mainViewModel.createPack(name, publisher, isAnimated).id
                                },
                                onSavedToPack = { packId ->
                                    navController.navigate(PackDetailRoute(packId)) {
                                        popUpTo<HomeRoute>()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable<AnimatedEditorRoute> { entry ->
                            val args = entry.toRoute<AnimatedEditorRoute>()
                            val editorVm: AnimatedEditorViewModel = viewModel()
                            val mediaType = runCatching {
                                DetectedMediaType.valueOf(args.mediaTypeName)
                            }.getOrDefault(DetectedMediaType.ANIMATED_GIF)

                            LaunchedEffect(args.mediaUriString, mediaType) {
                                editorVm.loadMedia(Uri.parse(args.mediaUriString), mediaType)
                            }
                            AnimatedEditorScreen(
                                viewModel = editorVm,
                                packs = packs,
                                initialTargetPackId = args.targetPackId,
                                onCreatePack = { name, publisher, isAnimated ->
                                    mainViewModel.createPack(name, publisher, isAnimated).id
                                },
                                onSavedToPack = { packId ->
                                    navController.navigate(PackDetailRoute(packId)) {
                                        popUpTo<HomeRoute>()
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreatePackDialog) {
        CreatePackDialog(
            onDismiss = { showCreatePackDialog = false },
            onConfirm = { name, publisher, isAnimated ->
                showCreatePackDialog = false
                scope.launch {
                    val created = mainViewModel.createPack(name, publisher, isAnimated)
                    withContext(Dispatchers.Main) {
                        navController.navigate(PackDetailRoute(created.id))
                    }
                }
            }
        )
    }
}
