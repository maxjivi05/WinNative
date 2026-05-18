package com.winlator.cmod.feature.stores.steam.wnsteam

/**
 * Progress + completion callbacks for [WnSteamSession.downloadApp] — the
 * C++ WN-Steam-Client depot download path (Phase 5).
 *
 * Both methods fire on a native worker thread; marshal to your own
 * dispatcher before touching UI or Room.
 */
interface WnDownloadListener {

    /**
     * Per-chunk progress for the depot currently downloading.
     *
     * @param depotId      the depot being written
     * @param depotDone    decompressed bytes written for this depot so far
     * @param depotTotal   total file bytes of this depot
     * @param depotsDone   depots fully completed before this one
     * @param depotsTotal  total depots in this download
     */
    fun onProgress(
        depotId: Int,
        depotDone: Long,
        depotTotal: Long,
        depotsDone: Int,
        depotsTotal: Int,
    )

    /**
     * Fired once when the whole download finishes (success or failure).
     *
     * @param success          true if every depot was written + verified
     * @param error            empty when success=true; else a diagnostic
     * @param bytesWritten     total decompressed bytes written
     * @param depotsCompleted  depots downloaded this run
     * @param depotsSkipped    depots already installed (resume)
     */
    fun onComplete(
        success: Boolean,
        error: String,
        bytesWritten: Long,
        depotsCompleted: Int,
        depotsSkipped: Int,
    )
}
