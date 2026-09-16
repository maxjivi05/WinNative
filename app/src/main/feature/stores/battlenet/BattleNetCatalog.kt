package com.winlator.cmod.feature.stores.battlenet

data class BattleNetGame(
    val product: String,
    val title: String,
    val launchCode: String,
    val titleId: Long?,
    val artworkSlug: String,
    val artworkDirectory: String = "cover",
) {
    val coverUrl: String get() = "https://lutris.net/games/$artworkDirectory/$artworkSlug.jpg"
    fun command(install: Boolean): String = "--exec=\"${if (install) "install" else "launch"} $launchCode\""
}

object BattleNetCatalog {
    val games = listOf(
        BattleNetGame("wow", "World of Warcraft", "WoW", 5730135, "world-of-warcraft"),
        BattleNetGame("wow_classic", "World of Warcraft Classic", "WoWC", 5730135, "world-of-warcraft-classic"),
        BattleNetGame("wow_classic_era", "World of Warcraft Classic Era", "WoWC", 5730135, "world-of-warcraft-classic"),
        BattleNetGame("s1", "StarCraft", "S1", 21297, "starcraft-remastered"),
        BattleNetGame("s2", "StarCraft II", "S2", 21298, "starcraft-ii"),
        BattleNetGame("pro", "Overwatch 2", "Pro", 5272175, "overwatch-2"),
        BattleNetGame("w1", "Warcraft: Orcs & Humans", "W1", null, "warcraft-orcs-humans"),
        BattleNetGame("w2bn", "Warcraft II: Battle.net Edition", "W2BN", null, "warcraft-ii-battlenet-edition"),
        BattleNetGame("gryphon", "Warcraft Rumble", "GRY", null, "warcraft-rumble", "banner"),
        BattleNetGame("w3", "Warcraft III", "W3", 22323, "warcraft-iii-reforged"),
        BattleNetGame("hsb", "Hearthstone", "WTCG", 1465140039, "hearthstone"),
        BattleNetGame("hero", "Heroes of the Storm", "Hero", 1214607983, "heroes-of-the-storm"),
        BattleNetGame("d3", "Diablo III", "D3", 17459, "diablo-iii"),
        BattleNetGame("fenris", "Diablo IV", "Fen", 4613486, "diablo-iv"),
        BattleNetGame("osi", "Diablo II: Resurrected", "OSI", 5198665, "diablo-2-ressurected"),
        BattleNetGame("anbs", "Diablo Immortal", "ANBS", 1095647827, "diablo-immortal"),
        BattleNetGame("w1r", "Warcraft I: Remastered", "W1R", 5714258, "warcraft-i-remastered", "banner"),
        BattleNetGame("w2r", "Warcraft II: Remastered", "W2R", 5714514, "warcraft-ii-remastered", "banner"),
        BattleNetGame("rtro", "Blizzard Arcade Collection", "RTRO", 1381257807, "blizzard-arcade-collection"),
        BattleNetGame("wlby", "Crash Bandicoot 4: It's About Time", "WLBY", 1464615513, "crash-bandicoot-4-its-about-time"),
    )

    private val accountAliases = mapOf(
        "fenris" to setOf("diablo4"), "anbs" to setOf("diablo_immortal"),
        "pro" to setOf("prometheus"), "hsb" to setOf("hs_beta"),
        "hero" to setOf("heroes"), "d3" to setOf("diablo3"), "w2bn" to setOf("w2be"),
    )

    fun ownedGames(titleIds: Set<String>): List<BattleNetGame> {
        val ids = titleIds.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        return games.filter { game ->
            game.titleId?.toString() in ids || game.product in ids || accountAliases[game.product].orEmpty().any { it in ids }
        }
    }

    fun byProduct(product: String): BattleNetGame? = games.firstOrNull { it.product == product }
}
