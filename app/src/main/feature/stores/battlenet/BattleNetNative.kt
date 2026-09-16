package com.winlator.cmod.feature.stores.battlenet

internal object BattleNetNative {
    init { System.loadLibrary("wn_battlenet") }
    @JvmStatic external fun latestBuild(product: String, region: String): String
    @JvmStatic external fun downloadPlan(product: String, region: String, tagsJson: String): String
}
