package com.winlator.cmod.steam.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.steam.service.SteamService
import com.winlator.cmod.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorkshopManagerDialog(
    visible: Boolean,
    appId: Int,
    gameName: String,
    currentEnabledIds: Set<Long>,
    onSave: suspend (Set<Long>) -> Result<String>,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val workshopItems = remember { mutableStateListOf<WorkshopItem>() }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }
    var isLoading by remember { mutableStateOf(true) }
    var fetchFailed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Fetching subscribed workshop items") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible, appId) {
        isLoading = true
        fetchFailed = false
        searchQuery = ""
        errorText = null
        statusText = "Fetching subscribed workshop items"
        workshopItems.clear()
        selectedIds.clear()

        val steamClient = SteamService.instance?.steamClient
        val steamId = SteamService.userSteamId
        if (steamClient != null && steamId != null) {
            val result = withContext(Dispatchers.IO) {
                WorkshopManager.getSubscribedItems(appId, steamClient, steamId)
            }
            if (result.succeeded) {
                workshopItems += result.items.sortedBy { it.title.lowercase() }
                result.items.forEach { item ->
                    selectedIds[item.publishedFileId] = item.publishedFileId in currentEnabledIds
                }
            } else {
                fetchFailed = true
                errorText = "Failed to fetch subscribed workshop items from Steam."
            }
        } else {
            fetchFailed = true
            errorText = "Steam is not connected."
        }
        isLoading = false
    }

    val allSelected by remember(selectedIds.toMap(), workshopItems.toList()) {
        derivedStateOf {
            workshopItems.isNotEmpty() && workshopItems.all { selectedIds[it.publishedFileId] == true }
        }
    }

    val filteredItems = remember(workshopItems.toList(), searchQuery) {
        if (searchQuery.isBlank()) {
            workshopItems.toList()
        } else {
            workshopItems.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    val selectedCount = selectedIds.count { it.value }
    val selectedBytes = workshopItems
        .filter { selectedIds[it.publishedFileId] == true }
        .sumOf { it.fileSizeBytes }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Steam Workshop",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        gameName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1.5f),
                        placeholder = { Text("Search workshop items") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = null)
                                }
                            }
                        },
                        singleLine = true,
                        enabled = !isSaving && !isLoading && !fetchFailed,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val summary = when {
                        isLoading -> "Loading workshop items"
                        fetchFailed -> (errorText ?: "Workshop items unavailable")
                        else -> "$selectedCount of ${workshopItems.size} selected (${StorageUtils.formatBinarySize(selectedBytes)})"
                    }
                    Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                ) {
                    when {
                        isLoading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(statusText)
                                }
                            }
                        }

                        fetchFailed -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(errorText ?: "Failed to fetch workshop items")
                            }
                        }

                        workshopItems.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No subscribed workshop items were found for this game.")
                            }
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                items(filteredItems, key = { it.publishedFileId }) { item ->
                                    val checked = selectedIds[item.publishedFileId] == true
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isSaving) {
                                                selectedIds[item.publishedFileId] = !checked
                                            }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            enabled = !isSaving,
                                            onCheckedChange = { selectedIds[item.publishedFileId] = it },
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                if (item.fileSizeBytes > 0) {
                                                    Text(StorageUtils.formatBinarySize(item.fileSizeBytes), style = MaterialTheme.typography.bodySmall)
                                                }
                                                if (item.timeUpdated > 0) {
                                                    val updated = remember(item.timeUpdated) {
                                                        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                                                            .format(Date(item.timeUpdated * 1000L))
                                                    }
                                                    Text("Updated $updated", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                if (isSaving) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .background(Color.Transparent),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(statusText)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !isSaving,
                        onClick = onDismissRequest,
                    ) {
                        Text("Close")
                    }

                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isLoading && !fetchFailed && workshopItems.isNotEmpty()) {
                            Button(
                                enabled = !isSaving,
                                onClick = {
                                    val newValue = !allSelected
                                    workshopItems.forEach { item -> selectedIds[item.publishedFileId] = newValue }
                                },
                            ) {
                                Text(if (allSelected) "Select All" else "Deselect All")
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Button(
                            enabled = !isLoading && !fetchFailed && !isSaving,
                            onClick = {
                                val enabledIds = selectedIds.filterValues { it }.keys
                                isSaving = true
                                statusText = if (enabledIds.isEmpty()) {
                                    "Removing workshop configuration"
                                } else {
                                    "Downloading and configuring workshop items"
                                }
                                scope.launch {
                                    val result = onSave(enabledIds)
                                    isSaving = false
                                    result.onSuccess {
                                        onDismissRequest()
                                    }.onFailure {
                                        errorText = it.message ?: "Workshop update failed"
                                    }
                                }
                            },
                        ) {
                            Text(if (isSaving) "Working..." else "Save")
                        }
                    }
                }
            }
        }
    }
}
