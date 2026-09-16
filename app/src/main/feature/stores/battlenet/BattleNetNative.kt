package com.winlator.cmod.feature.stores.battlenet

internal object BattleNetNative {
    init { System.loadLibrary("wn_battlenet") }
    @JvmStatic external fun latestBuild(product: String, region: String): String
    @JvmStatic external fun downloadPlan(product: String, region: String, tagsJson: String): String
    @JvmStatic external fun installPreview(product: String, region: String): String
    @JvmStatic external fun createJob(): Long
    @JvmStatic external fun runJob(id: Long, request: String): String
    @JvmStatic external fun jobStatus(id: Long): String
    @JvmStatic external fun commandJob(id: Long, command: String): Boolean
    @JvmStatic external fun releaseJob(id: Long)
}
