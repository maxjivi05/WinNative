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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.battlenet.*
import com.winlator.cmod.shared.ui.FourByTwoGridView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedActivity.BattleNetStoreTab(searchQuery: String) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var games by remember { mutableStateOf<List<BattleNetGame>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
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
    val signIn = { login.launch(Intent(this, BattleNetLoginActivity::class.java)) }
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
            installed = state.installs.filter { it.installed && it.playable }.map { it.product }.toSet()
            val local = BattleNetCatalog.games.filter { it.product in installed }
            games = local
            try {
                games = (BattleNetAccount.games() + local).distinctBy { it.product }
            } catch (_: BattleNetAccount.SignInRequired) {
                if (signedIn) error = "The account library could not be refreshed. Reconnect Battle.net to renew website access."
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

    selected?.let { game ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(game.title) },
            text = { Text(stringResource(R.string.battlenet_client_session_hint)) },
            confirmButton = {
                TextButton(onClick = { open(game, game.product !in installed) }, enabled = !busy) {
                    Text(stringResource(if (game.product in installed) R.string.battlenet_play else R.string.battlenet_install))
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }, enabled = !busy) { Text(stringResource(R.string.common_ui_close)) } },
        )
    }
}

@Composable
internal fun UnifiedActivity.BattleNetDownloads(): Boolean {
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
    return transfers.isNotEmpty()
}
