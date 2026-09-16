package com.winlator.cmod.feature.stores.battlenet

internal object BattleNetBuildInfo {
    fun clientCompatible(text: String): String {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size == 2)
        val headers = lines[0].split('|')
        val row = lines[1].split('|')
        require(headers.size == row.size)
        val missing = listOf("Install Key!HEX:16", "IM Size!DEC:4", "CDN Servers!STRING:0", "Armadillo!STRING:0", "Last Activated!STRING:0", "KeyRing!HEX:16")
            .filter { field -> headers.none { it.substringBefore('!') == field.substringBefore('!') } }
        if (missing.isEmpty()) return text
        return (headers + missing).joinToString("|") + "\n" + (row + missing.map { "" }).joinToString("|") + "\n"
    }

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
