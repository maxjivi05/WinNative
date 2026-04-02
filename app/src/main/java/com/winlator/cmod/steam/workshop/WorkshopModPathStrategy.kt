package com.winlator.cmod.steam.workshop

import java.io.File

sealed class WorkshopModPathStrategy {
    enum class FanOutPolicy {
        PRIMARY_ONLY,
        ALL_DIRS,
    }

    data object Standard : WorkshopModPathStrategy()

    data class SymlinkIntoDir(
        val targetDirs: List<File>,
        val fanOut: FanOutPolicy = FanOutPolicy.PRIMARY_ONLY,
    ) : WorkshopModPathStrategy() {
        constructor(targetDir: File) : this(listOf(targetDir), FanOutPolicy.PRIMARY_ONLY)

        val effectiveDirs: List<File> get() = when (fanOut) {
            FanOutPolicy.PRIMARY_ONLY -> listOf(targetDirs.first())
            FanOutPolicy.ALL_DIRS -> targetDirs
        }
    }

    data class CopyIntoDir(
        val targetDirs: List<File>,
        val fanOut: FanOutPolicy = FanOutPolicy.PRIMARY_ONLY,
    ) : WorkshopModPathStrategy() {
        constructor(targetDir: File) : this(listOf(targetDir), FanOutPolicy.PRIMARY_ONLY)

        val effectiveDirs: List<File> get() = when (fanOut) {
            FanOutPolicy.PRIMARY_ONLY -> listOf(targetDirs.first())
            FanOutPolicy.ALL_DIRS -> targetDirs
        }
    }
}
