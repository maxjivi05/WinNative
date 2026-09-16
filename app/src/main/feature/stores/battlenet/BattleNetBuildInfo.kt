package com.winlator.cmod.feature.stores.battlenet

internal object BattleNetBuildInfo {
    fun activeKey(text: String, product: String): String? {
        if (text.length > 1024 * 1024) return null
        val lines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.iterator()
        if (!lines.hasNext()) return null
        val headers = lines.next().split('|').map { it.substringBefore('!') }
        if (headers.distinct().size != headers.size) return null
        val active = headers.indexOf("Active")
        val key = headers.indexOf("Build Key")
        val productIndex = headers.indexOf("Product")
        if (active < 0 || key < 0 || productIndex < 0) return null
        val keys = mutableListOf<String>()
        for (line in lines) {
            val row = line.split('|')
            if (row.size != headers.size) return null
            if (row[active] == "1" && row[productIndex] == product) keys += row[key]
        }
        return keys.singleOrNull()?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }?.lowercase(java.util.Locale.ROOT)
    }
}
