package com.winlator.cmod.steam.workshop

import com.winlator.cmod.steam.data.DownloadInfo
import com.winlator.cmod.steam.enums.DownloadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object WorkshopDownloadRegistry {
    data class Metadata(
        val appId: Int,
        val gameName: String,
    )

    private data class Entry(
        val info: DownloadInfo,
        var metadata: Metadata,
        var runner: (suspend (DownloadInfo) -> Result<String>)? = null,
        @Volatile var completion: CompletableDeferred<Result<String>>? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<Int, Entry>()

    fun getAllDownloads(): Map<Int, DownloadInfo> =
        downloads.mapValues { it.value.info }

    fun getMetadata(appId: Int): Metadata? =
        downloads[appId]?.metadata

    suspend fun runTrackedSync(
        appId: Int,
        gameName: String,
        runner: suspend (DownloadInfo) -> Result<String>,
    ): Result<String> {
        val entry = downloads.compute(appId) { _, existing ->
            val info = existing?.info ?: DownloadInfo(
                jobCount = 1,
                gameId = appId,
                downloadingAppIds = CopyOnWriteArrayList(listOf(appId)),
            )
            Entry(
                info = info,
                metadata = Metadata(appId = appId, gameName = gameName),
                runner = runner,
            )
        } ?: error("Failed to create workshop download entry")

        entry.metadata = Metadata(appId = appId, gameName = gameName)
        entry.runner = runner

        val completion = startEntry(entry)
        return completion.await()
    }

    fun pauseAll() {
        downloads.values.forEach { pauseEntry(it.info.gameId) }
    }

    fun pauseDownload(appId: Int) {
        pauseEntry(appId)
    }

    fun resumeAll() {
        downloads.keys.forEach(::resumeDownload)
    }

    fun resumeDownload(appId: Int) {
        val entry = downloads[appId] ?: return
        val status = entry.info.getStatusFlow().value
        if (entry.info.isActive()) return
        if (status != DownloadPhase.PAUSED && status != DownloadPhase.FAILED) return
        startEntry(entry)
    }

    fun cancelAll() {
        downloads.keys.forEach(::cancelDownload)
    }

    fun cancelDownload(appId: Int) {
        val entry = downloads[appId] ?: return
        val info = entry.info
        if (info.getStatusFlow().value == DownloadPhase.COMPLETE ||
            info.getStatusFlow().value == DownloadPhase.CANCELLED
        ) {
            return
        }

        info.isCancelling = true
        info.updateStatus(DownloadPhase.CANCELLED, "Workshop download cancelled")
        info.cancel("Workshop download cancelled")
    }

    fun clearCompletedDownloads() {
        val idsToRemove = downloads.filterValues {
            val status = it.info.getStatusFlow().value
            status == DownloadPhase.COMPLETE || status == DownloadPhase.CANCELLED
        }.keys
        idsToRemove.forEach(downloads::remove)
    }

    private fun pauseEntry(appId: Int) {
        val entry = downloads[appId] ?: return
        val info = entry.info
        val status = info.getStatusFlow().value
        if (status == DownloadPhase.COMPLETE || status == DownloadPhase.CANCELLED) return

        info.isCancelling = false
        info.updateStatus(DownloadPhase.PAUSED, "Workshop download paused")
        info.cancel("Workshop download paused")
    }

    private fun startEntry(entry: Entry): CompletableDeferred<Result<String>> {
        val existingCompletion = entry.completion
        if (entry.info.isActive() && existingCompletion != null && !existingCompletion.isCompleted) {
            return existingCompletion
        }

        val runner = entry.runner ?: return CompletableDeferred(
            Result.failure(IllegalStateException("Workshop download runner unavailable"))
        )

        val info = entry.info
        val completion = CompletableDeferred<Result<String>>()
        entry.completion = completion

        info.clearError()
        info.isCancelling = false
        info.setActive(true)
        info.initializeBytesDownloaded(0L)
        info.setTotalExpectedBytes(0L)
        info.setProgress(0f)
        info.updateCurrentFileName(null)
        if (info.getStatusFlow().value != DownloadPhase.DOWNLOADING) {
            info.updateStatus(DownloadPhase.PREPARING, "Preparing workshop sync")
        }

        val job = scope.launch {
            val result = try {
                runner(info)
            } catch (e: CancellationException) {
                val status = info.getStatusFlow().value
                when {
                    status == DownloadPhase.PAUSED ->
                        Result.failure(IllegalStateException("Workshop download paused"))
                    info.isCancelling || status == DownloadPhase.CANCELLED -> {
                        info.updateStatus(DownloadPhase.CANCELLED, "Workshop download cancelled")
                        Result.failure(CancellationException("Workshop download cancelled"))
                    }
                    else -> {
                        info.markError(e.message ?: "Workshop download interrupted")
                        info.updateStatus(DownloadPhase.FAILED, e.message ?: "Workshop download interrupted")
                        Result.failure(e)
                    }
                }
            } catch (e: Exception) {
                info.markError(e.message ?: "Workshop download failed")
                info.updateStatus(DownloadPhase.FAILED, e.message ?: "Workshop download failed")
                Result.failure(e)
            }

            result.onSuccess { message ->
                val totalBytes = info.getTotalExpectedBytes()
                if (totalBytes > 0L) {
                    info.initializeBytesDownloaded(totalBytes)
                }
                info.setProgress(1f)
                info.updateCurrentFileName(null)
                info.updateStatus(DownloadPhase.COMPLETE, message)
                info.setActive(false)
            }.onFailure {
                if (info.getStatusFlow().value != DownloadPhase.PAUSED &&
                    info.getStatusFlow().value != DownloadPhase.CANCELLED &&
                    info.getStatusFlow().value != DownloadPhase.FAILED
                ) {
                    info.setActive(false)
                }
            }

            if (!completion.isCompleted) {
                completion.complete(result)
            }
        }

        info.setDownloadJob(job)
        return completion
    }
}
