package com.winlator.cmod.feature.stores.battlenet

class BattleNetTransferSample {
    private var previous: BattleNetInstall? = null
    private var previousTime = 0L

    fun update(install: BattleNetInstall, timeMillis: Long): Long? {
        val old = previous
        val elapsed = timeMillis - previousTime
        previous = install
        previousTime = timeMillis
        if (old == null || old.uid != install.uid || old.totalBytes != install.totalBytes ||
            elapsed <= 0 || elapsed > 30_000 || install.downloadedBytes < old.downloadedBytes
        ) return null
        return ((install.downloadedBytes - old.downloadedBytes).toDouble() * 1000 / elapsed).toLong()
    }
}
