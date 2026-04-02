package com.winlator.cmod.steam.workshop

import android.content.Context
import com.winlator.cmod.container.Container
import com.winlator.cmod.steam.service.SteamService
import com.winlator.cmod.steam.utils.Net
import com.winlator.cmod.steam.utils.PrefManager
import com.winlator.cmod.steam.utils.SteamUtils
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.SteamID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import org.tukaani.xz.LZMAInputStream
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object WorkshopManager {
    private const val TAG = "WorkshopManager"
    private const val MAX_PAGES = 50
    private const val PAGE_SIZE = 100
    private const val COMPLETE_MARKER = ".workshop_complete"

    private var workshopTypesPatched = false

    private data class SubscribedFilesPage(
        val items: List<WorkshopItem>,
        val totalResults: Int,
    )

    private data class DownloadBatchResult(
        val completedCount: Int,
        val failedCount: Int,
    )

    suspend fun getSubscribedItems(
        appId: Int,
        steamClient: SteamClient,
        steamId: SteamID,
    ): WorkshopFetchResult {
        val unifiedMessages = steamClient.getHandler<SteamUnifiedMessages>() ?: return WorkshopFetchResult(
            items = emptyList(),
            succeeded = false,
        )
        val publishedFile = unifiedMessages.createService<PublishedFile>()

        val allItems = mutableListOf<WorkshopItem>()
        var fetchedAtLeastOnePage = false
        var allPagesSucceeded = false
        var page = 1

        while (page <= MAX_PAGES) {
            val result = fetchSubscribedFilesViaRpc(publishedFile, appId, steamId, page) ?: break
            fetchedAtLeastOnePage = true
            allItems += result.items.map { if (it.appId == 0) it.copy(appId = appId) else it }

            if (result.items.isEmpty() || allItems.size >= result.totalResults) {
                allPagesSucceeded = true
                break
            }
            page++
        }

        return WorkshopFetchResult(
            items = allItems,
            succeeded = fetchedAtLeastOnePage,
            isComplete = allPagesSucceeded,
        )
    }

    private suspend fun fetchSubscribedFilesViaRpc(
        publishedFile: PublishedFile,
        appId: Int,
        steamId: SteamID,
        page: Int,
    ): SubscribedFilesPage? = withContext(Dispatchers.IO) {
        try {
            val request = CPublishedFile_GetUserFiles_Request.newBuilder().apply {
                this.steamid = steamId.convertToUInt64()
                this.appid = appId
                this.page = page
                this.numperpage = PAGE_SIZE
                this.type = "mysubscriptions"
                this.filetype = 0xFFFFFFFF.toInt()
            }.build()

            val response = withTimeoutOrNull(30_000L) {
                publishedFile.getUserFiles(request).toFuture().await()
            } ?: return@withContext null

            if (response.result != EResult.OK) {
                return@withContext null
            }

            val body = response.body.build()
            val items = body.publishedfiledetailsList.map { details ->
                WorkshopItem(
                    publishedFileId = details.publishedfileid,
                    appId = if (details.consumerAppid != 0) details.consumerAppid else appId,
                    title = details.title.ifEmpty { details.publishedfileid.toString() },
                    fileSizeBytes = details.fileSize,
                    manifestId = details.hcontentFile,
                    timeUpdated = details.timeUpdated.toLong(),
                    fileUrl = details.fileUrl ?: "",
                    fileName = details.filename ?: "",
                    previewUrl = details.previewUrl ?: "",
                )
            }
            SubscribedFilesPage(items = items, totalResults = body.total)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GetUserFiles failed for appId=$appId page=$page")
            null
        }
    }

    fun parseEnabledIds(idsString: String?): Set<Long> =
        (idsString ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

    fun getWinePrefix(container: Container): File = File(container.rootDir, ".wine")

    fun getWorkshopContentDir(container: Container, appId: Int): File =
        File(getWinePrefix(container), "drive_c/Program Files (x86)/Steam/steamapps/workshop/content/$appId")

    fun cleanupUnsubscribedItems(subscribedItems: List<WorkshopItem>, workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        val subscribedIds = subscribedItems.map { it.publishedFileId }.toSet()
        val onDiskDirs = workshopContentDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            .orEmpty()

        onDiskDirs.forEach { dir ->
            val id = dir.name.toLong()
            if (id !in subscribedIds) {
                dir.deleteRecursively()
                File(workshopContentDir, "${dir.name}.partial").deleteRecursively()
            }
        }
    }

    fun getItemsNeedingSync(items: List<WorkshopItem>, workshopContentDir: File): List<WorkshopItem> {
        return items.filter { item ->
            if (item.fileUrl.isEmpty() && item.manifestId == 0L) return@filter false

            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            val partialDir = File(workshopContentDir, "${item.publishedFileId}.partial")
            val completeMarker = File(itemDir, COMPLETE_MARKER)

            if (completeMarker.exists() && partialDir.exists()) {
                partialDir.deleteRecursively()
            }
            if (!completeMarker.exists() || partialDir.exists()) {
                return@filter true
            }

            val savedTimestamp = runCatching { completeMarker.readText().trim().toLongOrNull() }.getOrNull()
            savedTimestamp == null || item.timeUpdated > savedTimestamp
        }
    }

    fun updateMarkerTimestamps(items: List<WorkshopItem>, workshopContentDir: File) {
        items.forEach { item ->
            val marker = File(File(workshopContentDir, item.publishedFileId.toString()), COMPLETE_MARKER)
            if (marker.exists() && item.timeUpdated > 0) {
                marker.writeText(item.timeUpdated.toString())
            }
        }
    }

    fun fixItemFileNames(items: List<WorkshopItem>, workshopContentDir: File) {
        items.forEach { item ->
            val baseName = item.fileName.substringAfterLast('/')
            if (baseName.isBlank()) return@forEach
            val itemDir = File(workshopContentDir, item.publishedFileId.toString())
            if (!itemDir.isDirectory) return@forEach

            val goodFile = File(itemDir, baseName)
            if (goodFile.exists()) return@forEach

            val contentFiles = itemDir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                .orEmpty()
            if (contentFiles.size != 1) return@forEach

            val badFile = contentFiles.first()
            val targetExt = baseName.substringAfterLast('.', "").lowercase()
            val currentExt = badFile.extension.lowercase()
            if (targetExt == "ckm" && currentExt in setOf("esp", "esm", "bsa", "bsl")) {
                return@forEach
            }
            runCatching { Files.move(badFile.toPath(), goodFile.toPath()) }
        }
    }

    fun extractCkmFiles(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach
            itemDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".ckm", ignoreCase = true) }
                ?.forEach { ckmFile ->
                    runCatching {
                        val baseName = ckmFile.nameWithoutExtension
                        val data = ckmFile.readBytes()
                        if (data.size < 8) return@runCatching

                        if (data[0] == 0x54.toByte() && data[1] == 0x45.toByte() &&
                            data[2] == 0x53.toByte() && data[3] == 0x34.toByte()
                        ) {
                            Files.move(ckmFile.toPath(), File(ckmFile.parentFile, "$baseName.esp").toPath(), StandardCopyOption.REPLACE_EXISTING)
                            return@runCatching
                        }

                        val bsaLen = (data[0].toInt() and 0xFF) or
                            ((data[1].toInt() and 0xFF) shl 8) or
                            ((data[2].toInt() and 0xFF) shl 16) or
                            ((data[3].toInt() and 0xFF) shl 24)

                        var offset = 4
                        if (bsaLen > 0 && offset + bsaLen <= data.size) {
                            File(itemDir, "$baseName.bsa").writeBytes(data.copyOfRange(offset, offset + bsaLen))
                            offset += bsaLen
                        }
                        if (offset + 4 <= data.size) offset += 4
                        if (offset < data.size) {
                            File(itemDir, "$baseName.esp").writeBytes(data.copyOfRange(offset, data.size))
                        }
                        ckmFile.delete()
                    }
                }
        }
    }

    fun fixFileExtensions(workshopContentDir: File) {
        if (!workshopContentDir.exists()) return
        workshopContentDir.listFiles()?.forEach { itemDir ->
            if (!itemDir.isDirectory) return@forEach
            itemDir.listFiles()?.forEach { file ->
                if (!file.isFile || file.name.startsWith(".")) return@forEach
                if (file.extension.lowercase() in WorkshopItem.KNOWN_EXTENSIONS) return@forEach
                val magic = ByteArray(4)
                val bytesRead = runCatching { file.inputStream().use { it.read(magic) } }.getOrDefault(-1)
                if (bytesRead < 4) return@forEach

                val detectedExt = detectExtension(magic) ?: return@forEach
                val currentExt = file.extension.lowercase()
                val newName = if (currentExt.isNotEmpty() && detectedExt.startsWith(currentExt) && currentExt != detectedExt) {
                    "${file.nameWithoutExtension}.$detectedExt"
                } else {
                    "${file.name}.$detectedExt"
                }
                val target = File(file.parentFile, newName)
                if (!target.exists()) {
                    runCatching { Files.move(file.toPath(), target.toPath()) }
                }
            }
        }
    }

    private fun detectExtension(magic: ByteArray): String? {
        if (magic[0] == 0x47.toByte() && magic[1] == 0x4D.toByte() &&
            magic[2] == 0x41.toByte() && magic[3] == 0x44.toByte()
        ) return "gma"
        if (magic[0] == 0x34.toByte() && magic[1] == 0x12.toByte() &&
            magic[2] == 0xAA.toByte() && magic[3] == 0x55.toByte()
        ) return "vpk"
        if (magic[0] == 0x56.toByte() && magic[1] == 0x42.toByte() &&
            magic[2] == 0x53.toByte() && magic[3] == 0x50.toByte()
        ) return "bsp"
        if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        ) return "zip"
        return null
    }

    suspend fun decompressLzmaFiles(
        workshopContentDir: File,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ) {
        if (!workshopContentDir.exists()) return
        val lzmaFiles = withContext(Dispatchers.IO) {
            workshopContentDir.listFiles()
                ?.filter { it.isDirectory }
                ?.flatMap { dir ->
                    dir.listFiles()
                        ?.filter { it.isFile && !it.name.startsWith(".") }
                        ?.filter {
                            runCatching { it.inputStream().use { input -> input.read() == 0x5D } }.getOrDefault(false)
                        }
                        .orEmpty()
                }
                .orEmpty()
        }
        if (lzmaFiles.isEmpty()) return

        val completed = AtomicInteger(0)
        val semaphore = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        coroutineScope {
            lzmaFiles.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runCatching {
                            val temp = File(file.parentFile, "${file.name}.tmp")
                            file.inputStream().use { raw ->
                                LZMAInputStream(raw, -1).use { input ->
                                    temp.outputStream().use { output ->
                                        input.copyTo(output, 262_144)
                                    }
                                }
                            }
                            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        val done = completed.incrementAndGet()
                        onProgress?.invoke(done, lzmaFiles.size)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadItems(
        items: List<WorkshopItem>,
        steamClient: SteamClient,
        licenses: List<License>,
        workshopContentDir: File,
        onItemProgress: (completed: Int, total: Int, currentTitle: String) -> Unit,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadBatchResult = coroutineScope {
        workshopContentDir.mkdirs()
        val totalItems = items.size
        val completedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val concurrentLimit = when (PrefManager.downloadSpeed) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 4
            else -> 2
        }
        val fixedTotalBytes = items.sumOf { it.fileSizeBytes }
        val bytesDownloadedMap = ConcurrentHashMap<Long, Long>()

        onItemProgress(0, totalItems, items.firstOrNull()?.title ?: "")
        val httpItems = items.filter { it.fileUrl.isNotEmpty() }
        val depotItems = items.filter { it.fileUrl.isEmpty() }
        val jobs = mutableListOf<Deferred<Boolean>>()
        val semaphore = Semaphore(concurrentLimit)

        if (httpItems.isNotEmpty()) {
            jobs += httpItems.map { item ->
                async {
                    val itemDir = File(workshopContentDir, item.publishedFileId.toString())
                    val completeMarker = File(itemDir, COMPLETE_MARKER)
                    try {
                        semaphore.withPermit {
                            ensureActive()
                            completeMarker.delete()
                            bytesDownloadedMap[item.publishedFileId] = 0L
                            downloadViaHttp(item, itemDir.absolutePath) { downloaded, _ ->
                                bytesDownloadedMap[item.publishedFileId] = downloaded
                                onBytesProgress(bytesDownloadedMap.values.sum(), fixedTotalBytes)
                            }
                            itemDir.mkdirs()
                            completeMarker.writeText(item.timeUpdated.toString())
                        }
                        val done = completedCount.incrementAndGet()
                        onItemProgress(done, totalItems, item.title)
                        downloadPreviewImage(item, itemDir)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed HTTP workshop download for ${item.publishedFileId}")
                        failedCount.incrementAndGet()
                        false
                    }
                }
            }
        }

        if (depotItems.isNotEmpty()) {
            patchSupportedWorkshopFileTypes()
            val (maxDownloads, maxDecompress) = computeDownloadThreads()

            jobs += async {
                for (item in depotItems) {
                    ensureActive()
                    val itemDir = File(workshopContentDir, item.publishedFileId.toString())
                    val completeMarker = File(itemDir, COMPLETE_MARKER)
                    var downloader: DepotDownloader? = null
                    var failed = false
                    try {
                        downloader = DepotDownloader(
                            steamClient,
                            licenses,
                            debug = false,
                            androidEmulation = true,
                            maxDownloads = maxDownloads,
                            maxDecompress = maxDecompress,
                            parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
                        )
                        downloader.addListener(object : IDownloadListener {
                            override fun onDownloadStarted(dl: DownloadItem) = Unit
                            override fun onDownloadCompleted(dl: DownloadItem) = Unit
                            override fun onDownloadFailed(dl: DownloadItem, error: Throwable) = Unit
                            override fun onStatusUpdate(message: String) = Unit
                            override fun onChunkCompleted(
                                depotId: Int,
                                depotPercentComplete: Float,
                                compressedBytes: Long,
                                uncompressedBytes: Long,
                            ) {
                                bytesDownloadedMap[item.publishedFileId] = uncompressedBytes
                                onBytesProgress(bytesDownloadedMap.values.sum(), fixedTotalBytes)
                            }

                            override fun onDepotCompleted(
                                depotId: Int,
                                compressedBytes: Long,
                                uncompressedBytes: Long,
                            ) = Unit
                        })
                        downloader.add(
                            PubFileItem(
                                appId = item.appId,
                                pubFile = item.publishedFileId,
                                installDirectory = itemDir.absolutePath,
                            )
                        )
                        completeMarker.delete()
                        downloader.finishAdding()
                        withTimeout(computeDownloadTimeout(item.fileSizeBytes)) {
                            downloader.getCompletion().await()
                        }
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Depot workshop item failed/timed out: ${item.publishedFileId}")
                        failed = true
                    } finally {
                        runCatching { downloader?.close() }
                    }

                    val hasContent = itemDir.exists() && (itemDir.listFiles()?.any { !it.name.startsWith(".") } == true)
                    if (hasContent) {
                        completeMarker.writeText(item.timeUpdated.toString())
                        File(workshopContentDir, "${item.publishedFileId}.partial").deleteRecursively()
                        val done = completedCount.incrementAndGet()
                        onItemProgress(done, totalItems, item.title)
                        launch { downloadPreviewImage(item, itemDir) }
                    } else if (failed) {
                        failedCount.incrementAndGet()
                    }
                }
                true
            }
        }

        jobs.awaitAll()
        DownloadBatchResult(
            completedCount = completedCount.get(),
            failedCount = failedCount.get(),
        )
    }

    private fun computeDownloadTimeout(fileSizeBytes: Long): Long {
        val baseMinutes = 3L
        val sizeMb = fileSizeBytes / (1024 * 1024)
        val extraMinutes = (sizeMb * 30) / 60
        return ((baseMinutes + extraMinutes).coerceIn(3, 120)) * 60 * 1000
    }

    private fun computeDownloadThreads(): Pair<Int, Int> {
        var downloadRatio = 1.5
        var decompressRatio = 0.5
        when (PrefManager.downloadSpeed) {
            8 -> {
                downloadRatio = 0.6
                decompressRatio = 0.2
            }
            16 -> {
                downloadRatio = 1.2
                decompressRatio = 0.4
            }
            24 -> {
                downloadRatio = 1.5
                decompressRatio = 0.5
            }
            32 -> {
                downloadRatio = 2.4
                decompressRatio = 0.8
            }
        }
        val cpuCores = Runtime.getRuntime().availableProcessors()
        return (cpuCores * downloadRatio).toInt().coerceAtLeast(1) to
            (cpuCores * decompressRatio).toInt().coerceAtLeast(1)
    }

    private suspend fun downloadPreviewImage(item: WorkshopItem, itemDir: File) {
        if (item.previewUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val ext = item.previewUrl.substringAfterLast('.').substringBefore('?').lowercase()
                    .let { if (it in listOf("jpg", "jpeg", "png", "gif")) it else "jpg" }
                val previewFile = File(itemDir, "preview.$ext")
                if (previewFile.exists()) return@runCatching

                val request = Request.Builder().url(item.previewUrl).build()
                Net.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    body.byteStream().use { input ->
                        previewFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private suspend fun downloadViaHttp(
        item: WorkshopItem,
        installDirectory: String,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val itemDir = File(installDirectory)
        itemDir.mkdirs()

        val fileName = item.fileName.substringAfterLast('/').ifEmpty {
            item.fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { item.publishedFileId.toString() }
        }
        val outputFile = File(itemDir, fileName)
        var existingBytes = 0L
        if (outputFile.isFile && outputFile.length() > 0) {
            existingBytes = outputFile.length()
        }

        val requestBuilder = Request.Builder().url(item.fileUrl)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        Net.http.newCall(requestBuilder.build()).execute().use { response ->
            val isResuming = response.code == 206 && existingBytes > 0
            if (response.code == 416 && existingBytes > 0) {
                onBytesProgress(existingBytes, existingBytes)
                return@withContext
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for ${item.title}")
            }
            val body = response.body ?: throw IllegalStateException("Empty response body")
            val resumeOffset = if (isResuming) existingBytes else 0L
            val totalBytes = if (isResuming) {
                existingBytes + (body.contentLength().takeIf { it > 0 } ?: (item.fileSizeBytes - existingBytes))
            } else {
                body.contentLength().takeIf { it > 0 } ?: item.fileSizeBytes
            }

            body.byteStream().use { input ->
                BufferedOutputStream(java.io.FileOutputStream(outputFile, isResuming)).use { output ->
                    val buffer = ByteArray(262_144)
                    var downloaded = resumeOffset
                    var lastProgressUpdate = 0L

                    while (true) {
                        ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100) {
                            onBytesProgress(downloaded, totalBytes)
                            lastProgressUpdate = now
                        }
                    }
                    onBytesProgress(downloaded, totalBytes)
                }
            }
        }
    }

    private fun buildModsJson(modDirs: List<File>, items: List<WorkshopItem>): JSONObject {
        val itemsById = items.associateBy { it.publishedFileId }
        val modsObj = JSONObject()
        modDirs.forEach { itemDir ->
            val id = itemDir.name.toLongOrNull() ?: return@forEach
            val item = itemsById[id]
            val entry = JSONObject()
            entry.put("title", item?.title ?: itemDir.name)
            itemDir.listFiles()
                ?.firstOrNull { it.isFile && !it.name.startsWith(".") }
                ?.let { contentFile ->
                    entry.put("primary_filename", contentFile.name)
                    entry.put("primary_filesize", contentFile.length())
                }
            entry.put(
                "total_files_sizes",
                itemDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.sumOf { it.length() },
            )
            if (item != null && item.timeUpdated > 0) {
                entry.put("time_updated", item.timeUpdated)
            }
            toWindowsPath(itemDir.absolutePath)?.let { entry.put("path", it) }
            findPreviewImage(itemDir)?.let { entry.put("preview_filename", it.name) }
            modsObj.put(itemDir.name, entry)
        }
        return modsObj
    }

    private fun toWindowsPath(linuxPath: String): String? {
        val marker = "/drive_c/"
        val idx = linuxPath.indexOf(marker)
        if (idx < 0) return null
        return "C:\\" + linuxPath.substring(idx + marker.length).replace('/', '\\')
    }

    private fun findPreviewImage(itemDir: File): File? =
        itemDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("preview.", ignoreCase = true) &&
                it.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif")
        }

    private fun materializeWorkshopFile(source: File, destination: File): Boolean {
        if (!source.isFile) return false
        destination.parentFile?.mkdirs()
        val destPath = destination.toPath()
        val srcPath = source.toPath()

        if (Files.exists(destPath)) {
            if (Files.isSymbolicLink(destPath)) {
                Files.deleteIfExists(destPath)
            } else {
                if (destination.isFile && destination.length() == source.length()) return false
                Files.deleteIfExists(destPath)
            }
        }

        return try {
            Files.createLink(destPath, srcPath)
            true
        } catch (_: Exception) {
            Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING)
            true
        }
    }

    private fun copyPreviewImages(modDirs: List<File>, settingsDir: File) {
        val modImagesDir = File(settingsDir, "mod_images")
        modDirs.forEach { itemDir ->
            val previewFile = findPreviewImage(itemDir) ?: return@forEach
            val itemImagesDir = File(modImagesDir, itemDir.name)
            itemImagesDir.mkdirs()
            materializeWorkshopFile(previewFile, File(itemImagesDir, previewFile.name))
        }
    }

    private fun wineUserHome(winePrefix: File): File {
        val usersDir = File(winePrefix, "drive_c/users")
        if (!usersDir.isDirectory) return File(usersDir, "xuser")
        return usersDir.listFiles()
            ?.firstOrNull { it.isDirectory && !it.name.equals("Public", ignoreCase = true) }
            ?: File(usersDir, "xuser")
    }

    private fun detectStrategy(gameRootDir: File, container: Container, gameName: String, developerName: String): WorkshopModPathDetector.DetectionResult {
        val detector = WorkshopModPathDetector()
        val winePrefix = getWinePrefix(container)
        val userHome = wineUserHome(winePrefix)
        return detector.detect(
            gameInstallDir = gameRootDir,
            appDataRoaming = File(userHome, "AppData/Roaming"),
            appDataLocal = File(userHome, "AppData/Local"),
            appDataLocalLow = File(userHome, "AppData/LocalLow"),
            documentsMyGames = File(userHome, "Documents/My Games"),
            documentsDir = File(userHome, "Documents"),
            gameName = gameName,
            developerName = developerName,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun syncSelectedItems(
        context: Context,
        appId: Int,
        enabledIds: Set<Long>,
        container: Container,
        gameRootDir: File = File(SteamService.getAppDirPath(appId)),
        gameName: String = SteamService.getAppInfoOf(appId)?.name.orEmpty(),
        developerName: String = SteamService.getAppInfoOf(appId)?.developer.orEmpty(),
        onStatus: ((String) -> Unit)? = null,
        onBytesProgress: ((Long, Long) -> Unit)? = null,
    ): WorkshopSyncResult {
        val steamClient = SteamService.instance?.steamClient ?: throw IllegalStateException("Steam client unavailable")
        val steamId = SteamService.userSteamId ?: throw IllegalStateException("Steam user unavailable")
        val workshopContentDir = getWorkshopContentDir(container, appId)

        if (enabledIds.isEmpty()) {
            deleteWorkshopMods(appId, container, gameRootDir, gameName, developerName)
            return WorkshopSyncResult(emptyList(), 0)
        }

        onStatus?.invoke("Fetching workshop subscriptions")
        val fetchResult = getSubscribedItems(appId, steamClient, steamId)
        if (!fetchResult.succeeded) {
            throw IllegalStateException("Failed to fetch Steam Workshop subscriptions")
        }

        val items = fetchResult.items.filter { it.publishedFileId in enabledIds }
        if (fetchResult.isComplete) {
            cleanupUnsubscribedItems(items, workshopContentDir)
        }

        val itemsToSync = getItemsNeedingSync(items, workshopContentDir)
        if (itemsToSync.isNotEmpty()) {
            val totalBytes = itemsToSync.sumOf { it.fileSizeBytes }
            checkDiskSpace(workshopContentDir, totalBytes * 2)?.let { error ->
                throw IllegalStateException(error)
            }

            onStatus?.invoke("Downloading workshop content")
            val licenses = SteamService.getLicensesFromDb()
            val downloadResult = downloadItems(
                items = itemsToSync,
                steamClient = steamClient,
                licenses = licenses,
                workshopContentDir = workshopContentDir,
                onItemProgress = { completed, total, currentTitle ->
                    onStatus?.invoke("Downloading $currentTitle ($completed/$total)")
                },
                onBytesProgress = { downloaded, total ->
                    onBytesProgress?.invoke(downloaded, total)
                },
            )

            if (downloadResult.failedCount > 0) {
                throw IllegalStateException(
                    "Failed to download ${downloadResult.failedCount} workshop item(s)",
                )
            }
        }

        onStatus?.invoke("Processing workshop content")
        if (itemsToSync.isNotEmpty()) {
            fixItemFileNames(itemsToSync, workshopContentDir)
        }
        extractCkmFiles(workshopContentDir)
        decompressLzmaFiles(workshopContentDir) { completed, total ->
            onStatus?.invoke("Decompressing workshop content ($completed/$total)")
        }
        fixFileExtensions(workshopContentDir)
        updateMarkerTimestamps(items, workshopContentDir)

        onStatus?.invoke("Configuring workshop mods")
        configureModSymlinks(
            appId = appId,
            gameRootDir = gameRootDir,
            workshopContentDir = workshopContentDir,
            items = items,
            container = container,
            gameName = gameName,
            developerName = developerName,
        )

        return WorkshopSyncResult(
            items = items,
            downloadedCount = itemsToSync.size,
            failedCount = 0,
        )
    }

    fun deleteWorkshopMods(
        appId: Int,
        container: Container,
        gameRootDir: File = File(SteamService.getAppDirPath(appId)),
        gameName: String = SteamService.getAppInfoOf(appId)?.name.orEmpty(),
        developerName: String = SteamService.getAppInfoOf(appId)?.developer.orEmpty(),
    ) {
        val workshopDir = getWorkshopContentDir(container, appId)
        val detectorResult = runCatching { detectStrategy(gameRootDir, container, gameName, developerName) }.getOrNull()
        val workshopBase = workshopDir.parentFile ?: workshopDir

        if (detectorResult?.strategy != null && detectorResult.strategy !is WorkshopModPathStrategy.Standard) {
            WorkshopSymlinker().sync(detectorResult.strategy, emptyMap(), workshopBase)
        }

        clearGbeWorkshopEntries(gameRootDir)
        getGlobalSettingsDir(container, appId)?.let(::clearGlobalWorkshopEntries)
        if (workshopDir.exists()) {
            workshopDir.deleteRecursively()
        }
    }

    private fun configureModSymlinks(
        appId: Int,
        gameRootDir: File,
        workshopContentDir: File,
        items: List<WorkshopItem>,
        container: Container,
        gameName: String,
        developerName: String,
    ) {
        if (!workshopContentDir.exists()) return

        val enabledIdSet = items.map { it.publishedFileId.toString() }.toSet()
        workshopContentDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name.toLongOrNull() != null && dir.name !in enabledIdSet) {
                dir.deleteRecursively()
                File(workshopContentDir, "${dir.name}.partial").deleteRecursively()
            }
        }

        val modDirs = workshopContentDir.listFiles()
            ?.filter { dir ->
                val completeMarker = File(dir, COMPLETE_MARKER)
                dir.isDirectory &&
                    dir.name.toLongOrNull() != null &&
                    dir.name in enabledIdSet &&
                    completeMarker.exists() &&
                    dir.listFiles()?.any { !it.name.startsWith(".") } == true
            }
            .orEmpty()
        if (modDirs.isEmpty()) {
            clearGbeWorkshopEntries(gameRootDir)
            getGlobalSettingsDir(container, appId)?.let(::clearGlobalWorkshopEntries)
            return
        }

        val titlesByItemId = items.associate { it.publishedFileId to it.title }
        val detection = runCatching { detectStrategy(gameRootDir, container, gameName, developerName) }
            .getOrElse {
                Timber.tag(TAG).w(it, "Workshop path detection failed for $gameName")
                WorkshopModPathDetector.DetectionResult(
                    strategy = WorkshopModPathStrategy.Standard,
                    confidence = WorkshopModPathDetector.Confidence.LOW,
                    reason = "Detection failed",
                )
            }

        val shouldUseFilesystemPaths =
            detection.strategy !is WorkshopModPathStrategy.Standard &&
                detection.confidence == WorkshopModPathDetector.Confidence.HIGH

        configurePerDllWorkshopSettings(
            appId = appId,
            gameRootDir = gameRootDir,
            modDirs = modDirs,
            items = items,
            shouldClearGbeEntries = shouldUseFilesystemPaths,
        )

        getGlobalSettingsDir(container, appId)?.let { settingsDir ->
            SteamUtils.writeCompleteSettingsDir(settingsDir.parentFile ?: settingsDir, appId)
            val modsJson = File(settingsDir, "mods.json")
            if (shouldUseFilesystemPaths) {
                clearModsDir(File(settingsDir, "mods"))
                modsJson.writeText("{}")
                File(settingsDir, "mod_images").deleteRecursively()
            } else {
                modsJson.writeText(buildModsJson(modDirs, items).toString(2))
                copyPreviewImages(modDirs, settingsDir)
                clearModsDir(File(settingsDir, "mods"))
            }
        }

        if (detection.strategy !is WorkshopModPathStrategy.Standard) {
            val activeItemDirs = modDirs.associate { (it.name.toLongOrNull() ?: 0L) to it }
            val result = WorkshopSymlinker().sync(
                detection.strategy,
                activeItemDirs = activeItemDirs,
                workshopContentBase = workshopContentDir,
                itemTitles = titlesByItemId,
            )
            if (result.hasErrors) {
                result.errors.forEach { (entry, message) ->
                    Timber.tag(TAG).w("Workshop symlink error [%s]: %s", entry, message)
                }
            }
        }
    }

    private fun configurePerDllWorkshopSettings(
        appId: Int,
        gameRootDir: File,
        modDirs: List<File>,
        items: List<WorkshopItem>,
        shouldClearGbeEntries: Boolean,
    ) {
        val dllNames = setOf("steam_api.dll", "steam_api64.dll", "steamclient.dll", "steamclient64.dll")
        val modsJsonText = buildModsJson(modDirs, items).toString(2)

        gameRootDir.walkTopDown().maxDepth(10).forEach { file ->
            if (!file.isFile || file.name.lowercase() !in dllNames) return@forEach
            val parentDir = file.parentFile ?: return@forEach
            SteamUtils.writeCompleteSettingsDir(parentDir, appId)

            val settingsDir = File(parentDir, "steam_settings")
            val modsDir = File(settingsDir, "mods")
            val modsJson = File(settingsDir, "mods.json")

            if (shouldClearGbeEntries) {
                clearModsDir(modsDir)
                modsJson.writeText("{}")
                File(settingsDir, "mod_images").deleteRecursively()
                return@forEach
            }

            clearModsDir(modsDir)
            modsDir.mkdirs()
            modDirs.forEach { itemDir ->
                val linkPath = modsDir.toPath().resolve(itemDir.name)
                runCatching {
                    Files.deleteIfExists(linkPath)
                    Files.createSymbolicLink(linkPath, itemDir.toPath())
                }
            }
            modsJson.writeText(modsJsonText)
            copyPreviewImages(modDirs, settingsDir)
        }
    }

    private fun getGlobalSettingsDir(container: Container, appId: Int): File? {
        val steamRoot = File(getWinePrefix(container), "drive_c/Program Files (x86)/Steam")
        if (!steamRoot.exists()) return null
        SteamUtils.writeCompleteSettingsDir(steamRoot, appId)
        return File(steamRoot, "steam_settings")
    }

    private fun clearGbeWorkshopEntries(gameRootDir: File) {
        val dllNames = setOf("steam_api.dll", "steam_api64.dll", "steamclient.dll", "steamclient64.dll")
        gameRootDir.walkTopDown().maxDepth(10).forEach { file ->
            if (!file.isFile || file.name.lowercase() !in dllNames) return@forEach
            clearGlobalWorkshopEntries(File(file.parentFile, "steam_settings"))
        }
    }

    private fun clearGlobalWorkshopEntries(settingsDir: File) {
        settingsDir.mkdirs()
        clearModsDir(File(settingsDir, "mods"))
        File(settingsDir, "mods.json").writeText("{}")
        File(settingsDir, "mod_images").deleteRecursively()
    }

    private fun clearModsDir(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { entry ->
            if (Files.isSymbolicLink(entry.toPath())) {
                Files.deleteIfExists(entry.toPath())
            } else if (entry.isDirectory) {
                entry.deleteRecursively()
            }
        }
        if (dir.listFiles().isNullOrEmpty()) {
            dir.delete()
        }
    }

    fun checkDiskSpace(dir: File, requiredBytes: Long): String? {
        val spaceDir = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }
        val availableBytes = spaceDir?.usableSpace ?: -1L
        if (requiredBytes > 0 && availableBytes >= 0 && requiredBytes > availableBytes) {
            val requiredMb = "%.0f".format(java.util.Locale.US, requiredBytes / 1_048_576.0)
            val availableMb = "%.0f".format(java.util.Locale.US, availableBytes / 1_048_576.0)
            return "Not enough space (need $requiredMb MB, have $availableMb MB)"
        }
        return null
    }

    @Synchronized
    private fun patchSupportedWorkshopFileTypes() {
        if (workshopTypesPatched) return
        try {
            val field = DepotDownloader::class.java.getDeclaredField("SupportedWorkshopFileTypes")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val existingSet = field.get(null) as Set<EWorkshopFileType>
            if (!existingSet.contains(EWorkshopFileType.First)) {
                val patchedSet = LinkedHashSet(existingSet)
                patchedSet.add(EWorkshopFileType.First)
                field.set(null, patchedSet)
            }
            workshopTypesPatched = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to patch SupportedWorkshopFileTypes")
        }
    }
}
