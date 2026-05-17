package com.winlator.cmod.feature.configs.installflow

import android.content.Context
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.wine.WineInfo
import org.json.JSONObject
import timber.log.Timber

/**
 * Resolves the imported config's Wine/Proton identifier to a concrete
 * [Container] the shortcut can actually run inside.
 *
 * A Container's Windows prefix (`wineprefix`) is physically built for one
 * Wine/Proton and one architecture (`wineprefixArch`). Applying an imported
 * `wineVersion` as a per-shortcut override on a container whose prefix doesn't
 * match it produces a shortcut that won't boot. So instead of overriding the
 * key, the import moves the shortcut into a container whose wine matches —
 * reusing an existing one, or creating a new one (after the Wine/Proton has
 * been downloaded + installed).
 *
 * This is a stateless helper. It composes existing model APIs only
 * ([ContainerManager.getContainers], [ContainerManager.createContainer],
 * [WineInfo.fromIdentifier]) — no new model methods. Callers must invoke it off
 * the main thread: [resolve] does filesystem IO (container creation extracts a
 * wineprefix archive).
 */
object TargetContainerResolver {
    private const val TAG = "TargetContainerResolver"

    sealed class TargetContainerResult {
        /**
         * A usable container was found ([created] = false) or freshly created
         * ([created] = true). The shortcut should be moved into it.
         */
        data class Resolved(val container: Container, val created: Boolean) : TargetContainerResult()

        /** The config carries no Wine/Proton — leave the shortcut's container alone. */
        object NoWineInConfig : TargetContainerResult()

        /**
         * The wanted Wine/Proton is not installed and could not be installed
         * (download failed, not in the catalog, or the user declined). The
         * shortcut must stay on its current container — the wine change is
         * skipped, the rest of the config still applies.
         */
        data class WineUnavailable(val wantedIdentifier: String) : TargetContainerResult()

        /** The Wine/Proton is installed but [ContainerManager.createContainer] failed. */
        data class CreateFailed(val wantedIdentifier: String) : TargetContainerResult()
    }

    /**
     * @param wantedWineIdentifier the config's Wine identifier, already
     *   substitution-corrected (see [ConfigImportCoordinator.applySubstitutions]).
     * @param wineIsInstalled whether that Wine/Proton is on disk now (either it
     *   already was, or it was just downloaded by the coordinator).
     * @param gameLabelForNewContainer used to name a freshly-created container.
     */
    fun resolve(
        context: Context,
        manager: ContainerManager,
        contentsManager: ContentsManager,
        wantedWineIdentifier: String?,
        wineIsInstalled: Boolean,
        gameLabelForNewContainer: String,
    ): TargetContainerResult {
        val wanted = wantedWineIdentifier?.trim()?.takeIf { it.isNotEmpty() }
            ?: return TargetContainerResult.NoWineInConfig

        // MAIN wine ships with the app — always "installed". Anything else has to
        // actually be on disk before we'll create or move into a container for it.
        if (!wineIsInstalled && !WineInfo.isMainWineVersion(wanted)) {
            Timber.tag(TAG).w("resolve: wine '%s' not installed → WineUnavailable", wanted)
            return TargetContainerResult.WineUnavailable(wanted)
        }

        val wantedInfo = WineInfo.fromIdentifier(context, contentsManager, wanted)
        val wantedKey = wantedInfo.identifier()
        // Derive the architecture the SAME way ContainerManager.createContainer
        // does — from WineInfo.fromIdentifier (createContainer stores its result
        // as the container's `wineprefixArch`, ContainerManager.java:288-289).
        // Using an independent derivation here would let the resolver's notion
        // of arch drift from the created container's real prefix arch, so a
        // freshly-created container would fail to match on the next import and
        // a duplicate would be created. This is gated on `wineIsInstalled`, so
        // fromIdentifier resolves the real profile rather than falling back.
        val wantedArch = wantedInfo.arch
        val wantedIsMain = WineInfo.isMainWineVersion(wanted)

        // Pass 1: exact identifier match — the cheapest and most certain.
        manager.containers.firstOrNull { c ->
            c.wineVersion?.equals(wanted, ignoreCase = true) == true
        }?.let {
            Timber.tag(TAG).d("resolve: exact-match container %d for '%s'", it.id, wanted)
            return TargetContainerResult.Resolved(it, created = false)
        }

        // Pass 2: canonical-key / MAIN-wine equivalence, gated on equal arch.
        manager.containers.firstOrNull { c ->
            val cv = c.wineVersion ?: return@firstOrNull false
            val cInfo = WineInfo.fromIdentifier(context, contentsManager, cv)
            // wineprefixArch is what the prefix was physically built for; trust
            // it over the (possibly stale) wineVersion string. Containers
            // created before that extra existed fall back to the same
            // fromIdentifier-derived arch the wanted side uses.
            val cArch = c.getExtra("wineprefixArch").takeIf { it.isNotEmpty() }
                ?: cInfo.arch
            if (!cArch.equals(wantedArch, ignoreCase = true)) return@firstOrNull false
            (wantedIsMain && WineInfo.isMainWineVersion(cv)) ||
                cInfo.identifier().equals(wantedKey, ignoreCase = true)
        }?.let {
            Timber.tag(TAG).d("resolve: canonical-match container %d for '%s'", it.id, wanted)
            return TargetContainerResult.Resolved(it, created = false)
        }

        // No container can host this wine — create one. createContainer derives
        // the arch + emulators from the wineVersion and builds the wineprefix.
        val data = JSONObject().apply {
            put("name", newContainerName(gameLabelForNewContainer, wantedInfo))
            put("wineVersion", wanted)
        }
        Timber.tag(TAG).d("resolve: no match — creating container for '%s'", wanted)
        val created = manager.createContainer(data, contentsManager)
            ?: return TargetContainerResult.CreateFailed(wanted)
        Timber.tag(TAG).d("resolve: created container %d for '%s'", created.id, wanted)
        return TargetContainerResult.Resolved(created, created = true)
    }

    /** A recognisable, length-bounded name for a freshly-created container. */
    private fun newContainerName(gameLabel: String, wineInfo: WineInfo): String {
        val label = gameLabel.trim().ifEmpty { "Imported" }.take(40)
        return "$label — $wineInfo"
    }
}
