package com.winlator.cmod.app.shell

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.battlenet.*
import com.winlator.cmod.shared.ui.FourByTwoGridView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedActivity.BattleNetStoreTab(searchQuery: String) {
    val scope = rememberCoroutineScope()
    val nativeState by BattleNetDownloads.state.collectAsState()
    var nativePreview by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var nativeInstalled by remember { mutableStateOf(false) }
    var nativePath by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { BattleNetDownloads.restore(applicationContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var games by remember { mutableStateOf<List<BattleNetGame>>(emptyList()) }
    var installs by remember { mutableStateOf<List<BattleNetInstall>>(emptyList()) }
    val installed = installs.filter { it.installed && it.playable }.map { it.product }.toSet()
    var availableBytes by remember { mutableLongStateOf(0L) }
    val signedIn by BattleNetAccount.authenticated.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<BattleNetGame?>(null) }
    val gridState = rememberLazyGridState()
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val client = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
        libraryRefreshSignal++
    }
    val signIn = { login.launch(Intent(this, BattleNetLoginActivity::class.java).putExtra("libraryOnly", signedIn)) }
    val open: (BattleNetGame?, Boolean) -> Unit = { game, install ->
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                try {
                    client.launch(BattleNetRuntime.prepareLaunch(applicationContext, game, install))
                    selected = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: getString(R.string.battlenet_failed)
                    if (selected != null) android.widget.Toast.makeText(this@BattleNetStoreTab, error, android.widget.Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            BattleNetAccount.refreshSession(applicationContext)
            val state = BattleNetRuntime.importInstalled(applicationContext)
            error = state.sessionProblem
            installs = state.installs
            val local = BattleNetCatalog.games.filter { game ->
                BattleNetDownloads.installed(applicationContext, game.product) ||
                    state.installs.any { it.product == game.product && it.installed && it.playable }
            }
            games = local
            try {
                games = (BattleNetAccount.games(applicationContext) + local).distinctBy { it.product }
            } catch (_: BattleNetAccount.SignInRequired) {
                if (signedIn) error = getString(R.string.battlenet_library_session_hint)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: getString(R.string.battlenet_failed)
        } finally {
            loading = false
        }
    }

    val filtered = games.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
    DisposableEffect(filtered, gridState) {
        val click: (Int) -> Unit = { index -> selected = filtered.getOrNull(index) }
        storeItemCount = filtered.size
        storeItemClickCallback = click
        storeGridState = gridState
        onDispose {
            if (storeItemClickCallback === click) {
                storeItemClickCallback = null
                storeGridState = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().tabScreenPadding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Battle.net", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
            TextButton(onClick = {
                if (signedIn) {
                    scope.launch {
                        try { BattleNetAccount.signOut(applicationContext); refresh++ }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    }
                } else signIn()
            }, enabled = !busy) {
                Text(stringResource(if (signedIn) R.string.battlenet_sign_out else R.string.battlenet_sign_in))
            }
            if (signedIn) TextButton(onClick = signIn, enabled = !busy) { Text(stringResource(R.string.battlenet_reconnect)) }
            TextButton(onClick = { refresh++ }, enabled = !loading && !busy) { Text(stringResource(R.string.battlenet_refresh)) }
            Button(onClick = { open(null, false) }, enabled = !busy) { Text(stringResource(R.string.battlenet_open_client)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !signedIn && games.isEmpty() -> LoginRequiredScreen("Battle.net", signIn)
            games.isEmpty() && error != null -> Unit
            games.isEmpty() -> Text(stringResource(R.string.battlenet_no_games), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            else -> FourByTwoGridView(
                items = filtered,
                modifier = Modifier.tabScreenPadding(top = TabGridTopPadding),
                gridState = gridState,
                keyOf = { it.product },
            ) { game, _, rowHeight ->
                Card(Modifier.height(rowHeight).clickable { selected = game }) {
                    AsyncImage(game.coverUrl, game.title, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Crop)
                    Text(game.title, Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2)
                }
            }
        }
    }

    LaunchedEffect(selected?.product, nativeState.stage) {
        nativePreview = null
        nativeInstalled = false
        nativePath = ""
        previewLoading = selected != null
        availableBytes = 0L
        val product = selected?.product
        if (product != null) {
            try {
                availableBytes = withContext(Dispatchers.IO) { applicationContext.filesDir.usableSpace }
                nativeInstalled = BattleNetDownloads.installed(applicationContext, product)
                if (nativeInstalled) nativePath = BattleNetDownloads.installPath(applicationContext, product)
                nativePreview = BattleNetDownloads.preview(applicationContext, product)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = getString(R.string.battlenet_native_unavailable) }
            finally { previewLoading = false }
        }
    }

    selected?.let { game ->
        val install = installs.firstOrNull { it.product == game.product }
        BattleNetGameDetailDialog(
            game = game,
            isInstalled = game.product in installed || nativeInstalled,
            installPath = nativePath.ifEmpty { install?.path.orEmpty() },
            downloadSize = nativePreview?.optLong("downloadBytes") ?: install?.totalBytes ?: 0L,
            availableBytes = availableBytes,
            busy = busy,
            onDismiss = { if (!busy) selected = null },
            installEnabled = !previewLoading && nativePreview != null && nativeState.done,
            detailStatus = if (previewLoading) getString(R.string.downloads_queue_preparing_download) else if (nativePreview == null) getString(R.string.battlenet_native_unavailable) else getString(R.string.battlenet_native_selection),
            onVerify = if (nativeInstalled) ({
                scope.launch {
                    try { BattleNetDownloads.verify(applicationContext, game.product); selected = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                }
            }) else null,
            onDownload = {
                nativePreview?.let { preview ->
                    scope.launch {
                        try { BattleNetDownloads.start(applicationContext, game.product, preview); selected = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    }
                }
            },
            onPlay = { open(game, false) },
            checkForUpdate = if (nativeInstalled) suspend { BattleNetDownloads.hasUpdate(applicationContext, game.product) }
                else if (install != null) suspend { BattleNetRuntime.hasUpdate(applicationContext, install) } else null,
        )
    }
}

@Composable
internal fun BattleNetGameDetailDialog(
    game: BattleNetGame,
    isInstalled: Boolean,
    installPath: String,
    downloadSize: Long,
    availableBytes: Long,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    checkForUpdate: (suspend () -> Boolean)? = null,
    installEnabled: Boolean = true,
    detailStatus: String = "",
    onVerify: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var checking by remember(game.product) { mutableStateOf(false) }
    var updateAvailable by remember(game.product) { mutableStateOf(false) }
    var updateStatus by remember(game.product) { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), shape = RectangleShape, color = Color.Black) {
            StoreGameDetailScreen(
                title = game.title,
                subtitle = detailStatus,
                sourceLabel = stringResource(R.string.battlenet_launcher_name),
                heroImageUrl = game.coverUrl,
                isLoading = busy,
                isInstalled = isInstalled,
                installPathDisplay = installPath,
                downloadSize = downloadSize,
                installSize = 0L,
                availableBytes = availableBytes,
                isInstallEnabled = !busy && installEnabled,
                customPathLabel = "",
                showCustomPath = false,
                showUninstall = false,
                showPlay = true,
                onBack = onDismiss,
                onInstall = onDownload,
                onPlay = onPlay,
                showVerifyFiles = onVerify != null,
                onVerifyFiles = { onVerify?.invoke() },
                showUpdateCheck = isInstalled && checkForUpdate != null,
                isCheckingForUpdate = checking,
                isUpdateAvailable = updateAvailable,
                updateStatusText = updateStatus,
                isUpdateActionEnabled = !busy && !checking,
                areSteamActionsEnabled = !busy && !checking,
                onDownloadUpdate = onDownload,
                onCheckForUpdate = {
                    if (!checking && checkForUpdate != null) {
                        checking = true
                        scope.launch {
                            try {
                                updateAvailable = checkForUpdate()
                                updateStatus = context.getString(if (updateAvailable) R.string.store_game_update_available else R.string.store_game_no_update_available)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                updateAvailable = false
                                updateStatus = context.getString(R.string.battlenet_failed)
                            } finally {
                                checking = false
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun UnifiedActivity.BattleNetDownloads(): Boolean {
    val native by BattleNetDownloads.state.collectAsState()
    LaunchedEffect(Unit) { BattleNetDownloads.restore(applicationContext) }
    data class Transfer(val install: BattleNetInstall, val speed: Long?)
    var transfers by remember { mutableStateOf<List<Transfer>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val samples = mutableMapOf<String, BattleNetTransferSample>()
        while (true) {
            try {
                val state = BattleNetRuntime.snapshot(applicationContext)
                val active = state.installs.filter { !it.complete && it.progress != null && BattleNetCatalog.byProduct(it.product) != null }
                val now = SystemClock.elapsedRealtime()
                transfers = active.map { Transfer(it, samples.getOrPut(it.uid) { BattleNetTransferSample() }.update(it, now)) }
                samples.keys.retainAll(active.map { it.uid }.toSet())
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                transfers = emptyList()
                samples.clear()
                error = failure.message
            }
            delay(2000)
        }
    }
    if (native.product.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(BattleNetCatalog.byProduct(native.product)?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(native.label(this@BattleNetDownloads))
            LinearProgressIndicator(progress = { native.fraction }, modifier = Modifier.fillMaxWidth())
            if (native.stage == "downloading") Text("${android.text.format.Formatter.formatFileSize(this@BattleNetDownloads, native.current)} / ${android.text.format.Formatter.formatFileSize(this@BattleNetDownloads, native.total)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!native.done && !native.paused) TextButton(onClick = { BattleNetDownloads.command("pause") }) { Text(stringResource(R.string.session_drawer_pause)) }
                if (native.paused || native.stage in setOf("cancelled", "failed")) TextButton(onClick = {
                    scope.launch {
                        try { BattleNetDownloads.resume(applicationContext) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    }
                }) { Text(stringResource(R.string.session_drawer_resume)) }
                if (!native.done) TextButton(onClick = { scope.launch { BattleNetDownloads.cancel(applicationContext) } }) { Text(stringResource(R.string.common_ui_cancel)) }
            }
        }
    }
    if (transfers.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battlenet_downloads), style = MaterialTheme.typography.titleMedium)
            transfers.forEach { transfer ->
                Text(BattleNetCatalog.byProduct(transfer.install.product)?.title.orEmpty(), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { transfer.install.progress!!.toFloat() }, modifier = Modifier.fillMaxWidth())
                val speed = transfer.speed?.let { android.text.format.Formatter.formatFileSize(this@BattleNetDownloads, it) + "/s" } ?: "—"
                Text(stringResource(R.string.battlenet_download_progress, (transfer.install.progress!! * 100).toInt(), speed), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        startActivity(BattleNetRuntime.prepareLaunch(applicationContext))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.battlenet_manage_downloads)) }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    return transfers.isNotEmpty() || native.product.isNotEmpty()
}
