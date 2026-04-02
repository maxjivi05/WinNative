package com.winlator.cmod.steam.workshop

data class WorkshopItem(
    val publishedFileId: Long,
    val appId: Int,
    val title: String,
    val fileSizeBytes: Long,
    val manifestId: Long,
    val timeUpdated: Long,
    val fileUrl: String = "",
    val fileName: String = "",
    val previewUrl: String = "",
) {
    companion object {
        val KNOWN_EXTENSIONS = setOf(
            "gma", "vpk", "bsp", "zip", "rar", "7z",
            "bsa", "esp", "esm", "ckm", "pak", "bin",
            "txt", "cfg", "lua", "mdl", "vmt", "vtf",
            "wav", "mp3", "ogg", "png", "jpg", "jpeg",
        )
    }
}

data class WorkshopFetchResult(
    val items: List<WorkshopItem>,
    val succeeded: Boolean,
    val isComplete: Boolean = false,
)

data class WorkshopSyncResult(
    val items: List<WorkshopItem>,
    val downloadedCount: Int,
    val failedCount: Int = 0,
)
