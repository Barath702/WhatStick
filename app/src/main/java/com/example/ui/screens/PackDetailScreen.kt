package com.example.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.StickerFileStorage
import com.example.data.model.StickerEntity
import com.example.data.model.StickerPackWithStickers
import com.example.ui.components.CheckerboardBackground
import com.example.ui.components.StickerFileImage
import com.example.whatsapp.WhatsAppInstallOutcome
import com.example.whatsapp.WhatsAppInstaller
import com.example.whatsapp.WhatsAppTarget
import com.example.whatsapp.WhatsAppValidator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    packWithStickers: StickerPackWithStickers?,
    storage: StickerFileStorage,
    onBack: () -> Unit,
    onAddStickerClick: (isAnimated: Boolean) -> Unit,
    onEditStaticSticker: (packId: String, stickerId: String) -> Unit,
    onRenamePack: (packId: String, newName: String, newPublisher: String) -> Unit,
    onDeleteSticker: (StickerEntity) -> Unit,
    onSetAsTrayIcon: (packId: String, sticker: StickerEntity) -> Unit,
    onUpdateTrayIconFromUri: suspend (packId: String, uri: android.net.Uri) -> Result<Unit>,
    onUpdateStickerEmojis: (stickerId: String, emojis: String) -> Unit,
    onDeletePack: (packId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeletePackDialog by remember { mutableStateOf(false) }
    var previewSticker by remember { mutableStateOf<StickerEntity?>(null) }
    var stickerToDelete by remember { mutableStateOf<StickerEntity?>(null) }
    var whatsappTargetChooser by remember { mutableStateOf<List<WhatsAppTarget>?>(null) }
    var installBannerMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val trayPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val packId = packWithStickers?.pack?.id ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                val res = onUpdateTrayIconFromUri(packId, uri)
                res.onSuccess {
                    snackbarHostState.showSnackbar("Pack tray icon updated.")
                }.onFailure {
                    snackbarHostState.showSnackbar(it.message ?: "Failed to update tray icon.")
                }
            }
        }
    }

    val whatsappLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val packName = packWithStickers?.pack?.name ?: "Sticker Pack"
        when (val outcome = WhatsAppInstaller.parseActivityResult(activityResult.resultCode, activityResult.data, packName)) {
            is WhatsAppInstallOutcome.Success -> {
                installBannerMessage = true to "\"${outcome.packName}\" was added to WhatsApp!"
            }
            is WhatsAppInstallOutcome.Error -> {
                installBannerMessage = false to outcome.message
            }
            WhatsAppInstallOutcome.Cancelled -> {
                scope.launch { snackbarHostState.showSnackbar("WhatsApp installation cancelled.") }
            }
        }
    }

    fun launchWhatsAppInstall(pack: StickerPackWithStickers, targetPackage: String) {
        try {
            val intent = WhatsAppInstaller.buildInstallIntent(
                packId = pack.pack.id,
                packName = pack.pack.name,
                targetPackage = targetPackage
            )
            whatsappLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            installBannerMessage = false to "Installed WhatsApp version does not support third-party sticker packs."
        } catch (e: Exception) {
            installBannerMessage = false to "Unable to open WhatsApp: ${e.message ?: "Unknown error"}"
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        packWithStickers?.pack?.name ?: "Sticker Pack",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("pack_detail_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (packWithStickers != null) {
                        IconButton(
                            onClick = { showRenameDialog = true },
                            modifier = Modifier.testTag("pack_detail_rename_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename pack")
                        }
                        IconButton(
                            onClick = { showDeletePackDialog = true },
                            modifier = Modifier.testTag("pack_detail_delete_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete pack")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (packWithStickers == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Pack not found.")
            }
            return@Scaffold
        }

        val pack = packWithStickers.pack
        val stickers = packWithStickers.sortedStickers
        val validation = remember(packWithStickers) {
            WhatsAppValidator.validatePack(packWithStickers, storage)
        }
        val trayFile = storage.getAssetFile(pack.id, pack.trayIconFileName)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
            ) {
                // Header card
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            trayPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                        .testTag("pack_tray_icon_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    StickerFileImage(
                                        file = trayFile,
                                        versionKey = pack.imageDataVersion,
                                        contentDescription = "Tap to change pack tray icon",
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pack.name, style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "by ${pack.publisher} • Tap icon to change tray image",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    if (pack.isAnimated) Icons.Outlined.Animation else Icons.Outlined.Image,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    if (pack.isAnimated) "Animated Pack" else "Static Pack",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.surfaceContainer
                                        ) {
                                            Text(
                                                "${stickers.size}/30",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            installBannerMessage?.let { (isSuccess, msg) ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSuccess) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            if (isSuccess) Icons.Default.CheckCircle else Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            tint = if (isSuccess) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onErrorContainer
                                            }
                                        )
                                        Text(
                                            msg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSuccess) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onErrorContainer
                                            }
                                        )
                                    }
                                }
                            }

                            if (!validation.isValid) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            "WhatsApp Checklist",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        validation.issues.forEach { issue ->
                                            Text(
                                                "• $issue",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val sideBySide = maxWidth >= (365.dp * fontScale)
                                val labelMaxLines = if (!sideBySide && fontScale > 1.45f) 2 else 1
                                val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

                                val addStickerButton: @Composable (Modifier) -> Unit = { btnModifier ->
                                    FilledTonalButton(
                                        onClick = { onAddStickerClick(pack.isAnimated) },
                                        enabled = stickers.size < 30,
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = buttonPadding,
                                        modifier = btnModifier
                                            .heightIn(min = 50.dp)
                                            .testTag("pack_add_sticker_button")
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Add Sticker",
                                            maxLines = labelMaxLines,
                                            softWrap = labelMaxLines > 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                val addToWhatsAppButton: @Composable (Modifier) -> Unit = { btnModifier ->
                                    Button(
                                        onClick = {
                                            if (!validation.isValid) {
                                                installBannerMessage = false to validation.issues.first()
                                                return@Button
                                            }
                                            val targets = WhatsAppInstaller.getInstalledTargets(context)
                                            when {
                                                targets.isEmpty() -> {
                                                    installBannerMessage = false to
                                                        "WhatsApp is not installed on this device. Install WhatsApp or WhatsApp Business and try again."
                                                }
                                                targets.size == 1 -> {
                                                    launchWhatsAppInstall(packWithStickers, targets.first().packageName)
                                                }
                                                else -> {
                                                    whatsappTargetChooser = targets
                                                }
                                            }
                                        },
                                        enabled = validation.isValid,
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = buttonPadding,
                                        modifier = btnModifier
                                            .heightIn(min = 50.dp)
                                            .testTag("pack_add_to_whatsapp_button")
                                    ) {
                                        Icon(
                                            Icons.Default.Send,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Add to WhatsApp",
                                            fontWeight = FontWeight.Bold,
                                            maxLines = labelMaxLines,
                                            softWrap = labelMaxLines > 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (sideBySide) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        addStickerButton(Modifier.weight(1f).fillMaxHeight())
                                        addToWhatsAppButton(Modifier.weight(1.32f).fillMaxHeight())
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        addStickerButton(Modifier.fillMaxWidth())
                                        addToWhatsAppButton(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }

                if (stickers.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("This pack is empty", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (pack.isAnimated) {
                                        "Tap \"Add Sticker\" to import a GIF or video and create animated stickers."
                                    } else {
                                        "Tap \"Add Sticker\" to import a photo and craft static stickers."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(stickers, key = { it.id }) { sticker ->
                        val file = storage.getAssetFile(pack.id, sticker.fileName)
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { previewSticker = sticker }
                                .testTag("pack_sticker_tile_${sticker.id}")
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                StickerFileImage(
                                    file = file,
                                    versionKey = sticker.createdAt,
                                    contentDescription = "Sticker ${sticker.emojis}",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        "${(sticker.fileSizeBytes / 1024).coerceAtLeast(1)} KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Individual Sticker Preview & Actions Dialog
    previewSticker?.let { sticker ->
        val packId = packWithStickers?.pack?.id ?: return@let
        val file = storage.getAssetFile(packId, sticker.fileName)
        var emojiText by remember(sticker.id) { mutableStateOf(sticker.emojis) }

        AlertDialog(
            onDismissRequest = { previewSticker = null },
            title = { Text("Sticker Preview") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        CheckerboardBackground(modifier = Modifier.fillMaxSize(), cellSize = 12.dp)
                        StickerFileImage(
                            file = file,
                            versionKey = sticker.createdAt,
                            contentDescription = "Full sticker preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    }
                    Text(
                        "${(sticker.fileSizeBytes / 1024).coerceAtLeast(1)} KB • 512×512 WebP" +
                            if (sticker.isAnimated) " • ${(sticker.durationMs / 1000f)}s" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = emojiText,
                        onValueChange = { if (it.length <= 24) emojiText = it },
                        label = { Text("Emoji tags") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!sticker.isAnimated) {
                            OutlinedButton(
                                onClick = {
                                    previewSticker = null
                                    onEditStaticSticker(packId, sticker.id)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("preview_edit_sticker_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                onSetAsTrayIcon(packId, sticker)
                                previewSticker = null
                                scope.launch { snackbarHostState.showSnackbar("Set as pack tray icon.") }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tray Icon")
                        }
                    }
                    TextButton(
                        onClick = {
                            stickerToDelete = sticker
                            previewSticker = null
                        },
                        modifier = Modifier.testTag("preview_delete_sticker_button")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete Sticker", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (emojiText != sticker.emojis) {
                        onUpdateStickerEmojis(sticker.id, emojiText)
                    }
                    previewSticker = null
                }) {
                    Text("Done")
                }
            }
        )
    }

    // Rename Pack Dialog
    if (showRenameDialog && packWithStickers != null) {
        var newName by remember { mutableStateOf(packWithStickers.pack.name) }
        var newPublisher by remember { mutableStateOf(packWithStickers.pack.publisher) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Pack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { if (it.length <= 64) newName = it },
                        label = { Text("Pack name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPublisher,
                        onValueChange = { if (it.length <= 64) newPublisher = it },
                        label = { Text("Publisher") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRenamePack(packWithStickers.pack.id, newName, newPublisher)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete Sticker Confirmation
    stickerToDelete?.let { sticker ->
        AlertDialog(
            onDismissRequest = { stickerToDelete = null },
            title = { Text("Delete sticker?") },
            text = { Text("This sticker will be removed from the pack.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSticker(sticker)
                        stickerToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_sticker_button")
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { stickerToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Delete Pack Confirmation
    if (showDeletePackDialog && packWithStickers != null) {
        AlertDialog(
            onDismissRequest = { showDeletePackDialog = false },
            title = { Text("Delete \"${packWithStickers.pack.name}\"?") },
            text = { Text("All stickers inside this pack will also be deleted.") },
            confirmButton = {
                Button(onClick = {
                    val id = packWithStickers.pack.id
                    showDeletePackDialog = false
                    onDeletePack(id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePackDialog = false }) { Text("Cancel") }
            }
        )
    }

    // WhatsApp vs WhatsApp Business Chooser
    whatsappTargetChooser?.let { targets ->
        val pack = packWithStickers ?: return@let
        AlertDialog(
            onDismissRequest = { whatsappTargetChooser = null },
            title = { Text("Choose WhatsApp App") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { target ->
                        FilledTonalButton(
                            onClick = {
                                whatsappTargetChooser = null
                                launchWhatsAppInstall(pack, target.packageName)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(target.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { whatsappTargetChooser = null }) { Text("Cancel") }
            }
        )
    }
}
