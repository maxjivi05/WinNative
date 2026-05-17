package com.winlator.cmod.feature.configs.installflow

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.winlator.cmod.feature.configs.ConfigSerializer
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume

/**
 * Drives the "Missing Components" import flow.
 *
 *  Idle
 *    → start(json, container, shortcut) →
 *  Analyzing
 *    → all requirements installed + no arch mismatch → Applying → Done
 *    → otherwise → ChoosingComponents
 *  ChoosingComponents
 *    → confirmDownload(): Downloading
 *    → applyAvailableOnly(): Applying (with missing-component keys stripped)
 *    → cancel(): Idle
 *  Downloading
 *    → every selected row → Done → Applying → Done | Failed
 *    → retry(rowId): Failed → Downloading
 *    → cancel(): scope cancelled, Idle
 *
 * Downloads run in parallel; install/extract is serialized via [installMutex]
 * because ContentsManager writes to a single shared tmp dir during extraction
 * and concurrent installs corrupt each other.
 *
 * Constructed with the application context. The Shortcut + Container references
 * are stored on the [Session] for the duration of the active import (Analyzing
 * → Downloading → Applying) so the apply step can reach them; they are cleared
 * on terminal completion (Done/Failed) and on [cancel]. Callers must invoke
 * [dispose] when the owning ViewModel/Activity is finished — otherwise an
 * in-flight install may continue running against a destroyed UI host.
 */
class ConfigImportCoordinator(private val appContext: Context) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val installMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var workJob: Job? = null

    /**
     * Per-import map of (requirement id → installed driver name) populated by
     * [downloadAndInstallDriver] when the adrenotools install succeeds. Used
     * by [applySubstitutions] to rewrite `graphicsDriverConfig.version` to the
     * name the launcher will actually find on disk — without this rewrite, a
     * substituted nightly (or any case-mismatched identifier) would land in
     * the shortcut as the requested-but-uninstalled token and the launcher
     * would fail to resolve the driver at game start.
     */
    private val driverInstallNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Per-import set of requirement ids whose download + install actually
     * succeeded this run. Read by [isWineInstalled] so container provisioning
     * knows whether the imported Wine/Proton is genuinely on disk — a row the
     * user deselected, or one that failed, never lands here. Cleared alongside
     * [driverInstallNames] on every fresh [start] and on [cancel].
     */
    private val installedRequirementIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Begin the analysis + download + apply pipeline. No-op if not in [ImportState.Idle].
     * [onResult] is invoked exactly once when the flow reaches a terminal state (Done/Failed).
     */
    fun start(
        configJson: JSONObject,
        container: Container?,
        shortcut: Shortcut?,
        onResult: (String) -> Unit,
    ) {
        android.util.Log.d(
            TAG,
            "start: state=${_state.value::class.simpleName} " +
                "shortcut=${shortcut?.name} container=${container?.name}",
        )
        // Allow start() from Idle (first run) and from the terminal states
        // (Done / Failed) so the user can re-apply the same community config
        // without having to dismiss + re-open the screen. We only block when
        // a flow is actively mid-run — those states need the user to either
        // confirm, retry, or cancel before a new analysis kicks off.
        val current = _state.value
        val isActive = current is ImportState.Analyzing ||
            current is ImportState.ChoosingComponents ||
            current is ImportState.Downloading ||
            current is ImportState.Applying
        if (isActive) {
            Timber.tag(TAG).w("start() ignored: state is ${current::class.simpleName}")
            return
        }
        // Coming out of Done/Failed: clear any lingering per-import state so
        // the next analysis starts clean.
        session = null
        driverInstallNames.clear()
        installedRequirementIds.clear()
        // Wrap the caller's onResult so internal invocations always marshal to
        // Main. This is the boundary between coordinator coroutines (Default/IO)
        // and the UI side — without it, a caller that touches Toast/View/etc. from
        // their lambda crashes with "Can't toast on a thread that has not called
        // Looper.prepare()".
        //
        // The runCatching is defense-in-depth: Handler.post propagates exceptions
        // to the Looper's uncaught-exception handler, which crashes the process on
        // Android. Without the catch, an OEM-quirk Toast crash inside the caller
        // lambda would upgrade from "swallowed by coordinator catch" (pre-fix) to
        // "hard process crash" (post-fix). Better to log and drop.
        val mainSafeOnResult: (String) -> Unit = { msg ->
            mainHandler.post {
                runCatching { onResult(msg) }
                    .onFailure { Timber.tag(TAG).w(it, "onResult callback threw") }
            }
        }
        if (container == null || shortcut == null) {
            terminalFailure("No shortcut or container to import into.", mainSafeOnResult)
            return
        }
        session = Session(configJson, container, shortcut, mainSafeOnResult)
        // driverInstallNames was already cleared above for the Done/Failed
        // re-entry case (or untouched if we came in from Idle).
        _state.value = ImportState.Analyzing
        workJob = scope.launch { runAnalysis() }
    }

    /** Toggle a row's checkbox while in ChoosingComponents. */
    fun toggleSelection(requirementId: String) {
        val current = _state.value as? ImportState.ChoosingComponents ?: return
        val entry = current.entries.firstOrNull { it.requirement.id == requirementId } ?: return
        // Both Available (ContentsManager component) and AvailableDriver
        // (adrenotools graphics-driver asset) rows are user-toggleable —
        // anything we can actually download should accept a checkbox tap.
        if (entry.resolution !is RequirementResolution.Available &&
            entry.resolution !is RequirementResolution.AvailableDriver
        ) return
        val nextSelected =
            if (requirementId in current.selectedIds) current.selectedIds - requirementId
            else current.selectedIds + requirementId
        _state.value = current.copy(selectedIds = nextSelected)
    }

    /** User tapped Download. Transition to Downloading and queue the selected rows. */
    fun confirmDownload() {
        val current = _state.value as? ImportState.ChoosingComponents ?: return
        val selectedEntries = current.entries.filter {
            it.requirement.id in current.selectedIds &&
                (it.resolution is RequirementResolution.Available ||
                    it.resolution is RequirementResolution.AvailableDriver)
        }
        if (selectedEntries.isEmpty()) {
            // Nothing selected → apply available only
            applyAvailableOnly()
            return
        }
        val rowStates = selectedEntries.associate { it.requirement.id to (RowState.Queued as RowState) }
        _state.value = ImportState.Downloading(current.entries, rowStates, current.archMismatch)
        workJob = scope.launch { runDownloads(selectedEntries) }
    }

    /** User tapped "Apply available only" — strip keys for missing components and apply. */
    fun applyAvailableOnly() {
        val current = _state.value
        val entries = when (current) {
            is ImportState.ChoosingComponents -> current.entries
            is ImportState.Downloading -> current.entries
            else -> return
        }
        val keysToStrip = entries
            .filter { it.resolution !is RequirementResolution.Installed }
            .flatMap { it.requirement.keysGuarded }
            .toSet()
        _state.value = ImportState.Applying
        workJob = scope.launch { applyConfig(keysToStrip) }
    }

    /** Retry a failed download row. */
    fun retry(requirementId: String) {
        val current = _state.value as? ImportState.Downloading ?: return
        val entry = current.entries.firstOrNull { it.requirement.id == requirementId } ?: return
        if (current.rowStates[requirementId] !is RowState.Failed) return
        updateRow(requirementId, RowState.Queued)
        workJob = scope.launch { runDownloads(listOf(entry), reusePreviousStates = true) }
    }

    /**
     * Back-press / dismiss. Aborts in-flight work and resets to Idle. The
     * "Import cancelled." callback only fires when the flow was actually mid-run
     * — if we're already terminal (Done / Failed), onResult has already been
     * delivered and we don't double-toast.
     */
    fun cancel() {
        // Container provisioning (createContainer extracts a wineprefix archive,
        // then the shortcut .desktop is physically moved) is not safely
        // interruptible — aborting mid-move could leave a shortcut pointing at a
        // container it doesn't live in. The dialog already blocks dismissal in
        // this state (MissingComponentsDialog isBusy); ignore stray cancels too.
        if (_state.value is ImportState.ProvisioningContainer) {
            Timber.tag(TAG).w("cancel() ignored: container provisioning in progress")
            return
        }
        val terminalAlready = _state.value is ImportState.Done || _state.value is ImportState.Failed
        scope.coroutineContext.cancelChildren()
        if (!terminalAlready) session?.onResult?.invoke("Import cancelled.")
        session = null
        driverInstallNames.clear()
        installedRequirementIds.clear()
        _state.value = ImportState.Idle
    }

    /**
     * Caller must invoke when the parent UI is permanently gone (ViewModel.onCleared).
     *
     * Unlike [cancel], this force-cancels the scope even mid-[ImportState.ProvisioningContainer].
     * If that happens while a new container is being created, the container may
     * be left on disk without a shortcut moved into it (a harmless orphan that
     * the user can delete from container management) — an acceptable trade for
     * not leaking the coordinator's coroutine scope.
     */
    fun dispose() {
        scope.cancel()
        session = null
    }

    // -------------------------------------------------------------------------
    // Pipeline implementations
    // -------------------------------------------------------------------------

    private suspend fun runAnalysis() {
        val s = session ?: return
        try {
            val manager = ContentsManager(appContext)
            val catalogJson = withContext(Dispatchers.IO) { fetchCatalogJson() }
            if (catalogJson == null) {
                // Couldn't reach the remote catalog. Don't silently mark everything
                // "Unavailable" — that misrepresents the failure as "nothing exists
                // in the catalog" rather than "we couldn't read the catalog".
                terminalFailure(
                    "Couldn't reach the components catalog. Check your connection and try again.",
                    s.onResult,
                )
                return
            }
            withContext(Dispatchers.IO) { manager.setRemoteProfiles(catalogJson) }
            withContext(Dispatchers.IO) { manager.syncContents() }

            // Best-effort: fetch the graphics-driver GitHub release listings so
            // the detector can resolve a custom Turnip / Adreno-Tools driver
            // referenced in the config without falling back to "Unsupported".
            // Failure is non-fatal — the detector treats an empty list as "no
            // driver repos configured" and surfaces an actionable message.
            val driverCandidates = withContext(Dispatchers.IO) {
                runCatching { GraphicsDriverRepoLookup.fetchAllCandidates(appContext) }
                    .getOrDefault(emptyList())
            }

            // Lock per-arch component matching (DXVK / VKD3D) to the target
            // container's wineprefixArch. Without this, the detector's null-arch
            // wildcard could accept an implicit-x86_64 catalog entry for an
            // arm64ec container and queue the wrong build for download.
            val containerArch = s.container.getExtra("wineprefixArch")
                ?.takeIf { it.isNotEmpty() }
                ?: "x86_64"
            val entries = ConfigImportDetector.detect(
                appContext,
                s.configJson,
                manager,
                driverCandidates,
                containerArch = containerArch,
            )
            android.util.Log.d(
                TAG,
                "runAnalysis: detected ${entries.size} requirements, " +
                    "available=${entries.count { it.resolution is RequirementResolution.Available || it.resolution is RequirementResolution.AvailableDriver }} " +
                    "unavailable=${entries.count { it.resolution is RequirementResolution.Unavailable }} " +
                    "installed=${entries.count { it.resolution is RequirementResolution.Installed }}",
            )
            val arch = computeArchMismatch(s.configJson, s.container)

            // Available + AvailableDriver rows are user-actionable (they have a
            // download URL we can hit). Unsupported rows are informational;
            // Unavailable rows are catalog misses the user should still see.
            val anyAvailable = entries.any {
                it.resolution is RequirementResolution.Available ||
                    it.resolution is RequirementResolution.AvailableDriver
            }
            val anyUnavailable = entries.any { it.resolution is RequirementResolution.Unavailable }
            val needsUserDecision = anyAvailable || anyUnavailable || arch != null

            // Collect any substitutions surfaced by the detector so applyConfig
            // can rewrite the JSON to point at the substitute that's actually
            // installed (or about to be). Without this, an old nightly token in
            // the config gets written verbatim to the shortcut and the launcher
            // can't resolve it.
            val substitutions = entries.mapNotNull { e ->
                val r = e.resolution
                if (r is RequirementResolution.Available && r.substituteFor != null) {
                    e.requirement.id to r.profile
                } else null
            }.toMap()
            session = s.copy(manager = manager, substitutionMap = substitutions, entries = entries)

            if (!needsUserDecision) {
                // Everything is either Installed or Unsupported (drivers). There's
                // nothing the user can do from a dialog, so just apply directly. The
                // result message will mention any Unsupported-driver notes via the
                // serializer's warning list.
                android.util.Log.d(TAG, "runAnalysis: no user decision needed → applying directly")
                _state.value = ImportState.Applying
                applyConfig(emptySet())
                return
            }
            android.util.Log.d(TAG, "runAnalysis: user decision needed → ChoosingComponents")

            val defaultSelected = entries
                .filter {
                    it.resolution is RequirementResolution.Available ||
                        it.resolution is RequirementResolution.AvailableDriver
                }
                .map { it.requirement.id }
                .toSet()
            _state.value = ImportState.ChoosingComponents(entries, defaultSelected, arch)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "runAnalysis failed")
            terminalFailure("Analysis failed: ${t.message ?: t::class.simpleName}", s.onResult)
        }
    }

    private suspend fun runDownloads(
        entries: List<RequirementEntry>,
        reusePreviousStates: Boolean = false,
    ) {
        val s = session ?: return
        val manager = s.manager ?: run {
            terminalFailure("Components manager not initialized.", s.onResult)
            return
        }

        try {
            coroutineScope {
                entries.map { entry ->
                    async(Dispatchers.IO) {
                        val req = entry.requirement
                        when (val res = entry.resolution) {
                            is RequirementResolution.Available ->
                                downloadAndInstall(manager, req, res.profile)
                            is RequirementResolution.AvailableDriver ->
                                downloadAndInstallDriver(req, res)
                            else -> updateRow(req.id, RowState.Failed("Not available"))
                        }
                    }
                }.awaitAll()
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "runDownloads crashed")
        }

        // Decide whether to proceed to apply: only if every queued row across the
        // current state is Done. compareAndSet guards against the retry race —
        // two concurrent runDownloads tails can both observe allOk and try to
        // advance, but only one wins the CAS into Applying.
        val current = _state.value as? ImportState.Downloading ?: return
        val allOk = current.rowStates.values.all { it is RowState.Done }
        if (allOk && _state.compareAndSet(current, ImportState.Applying)) {
            applyConfig(emptySet())
        }
        // Else stay in Downloading; user can Retry failed rows or apply-available-only.
    }

    private suspend fun downloadAndInstall(
        manager: ContentsManager,
        req: ComponentRequirement,
        profile: ContentProfile,
    ) {
        val remoteUrl = profile.remoteUrl ?: run {
            updateRow(req.id, RowState.Failed("No download URL"))
            return
        }
        // --- Download phase (parallel-safe; writes to a unique tmp file) ----
        updateRow(req.id, RowState.Downloading(null))
        val tmp = File(appContext.cacheDir, "cfgimport_${System.currentTimeMillis()}_${req.id}")
        val downloadOk = runCatching {
            Downloader.downloadFileWinNativeFirst(remoteUrl, tmp) { downloaded, total ->
                val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                updateRow(req.id, RowState.Downloading(frac))
            }
        }.getOrDefault(false)

        if (!downloadOk) {
            runCatching { tmp.delete() }
            updateRow(req.id, RowState.Failed("Download failed"))
            return
        }

        // --- Install phase (must be serialized — shared tmp dir in ContentsManager) ----
        updateRow(req.id, RowState.Installing)
        installMutex.withLock {
            val installed = runCatching { runInstall(manager, tmp, profile) }
                .getOrElse {
                    Timber.tag(TAG).w(it, "Install failed for ${req.id}")
                    null
                }
            runCatching { tmp.delete() }
            if (installed != null) {
                withContext(Dispatchers.IO) { manager.syncContents() }
                installedRequirementIds.add(req.id)
                updateRow(req.id, RowState.Done)
            } else {
                updateRow(req.id, RowState.Failed("Install failed"))
            }
        }
    }

    /**
     * Mirror of [downloadAndInstall] for graphics-driver assets fetched from a
     * GitHub release repo. Uses [Downloader.downloadFile] (same downloader the
     * Drivers screen calls) and routes the resulting zip through
     * [com.winlator.cmod.runtime.content.AdrenotoolsManager.installDriver] so
     * the driver lands in the same on-disk layout as a hand-installed one.
     */
    private suspend fun downloadAndInstallDriver(
        req: ComponentRequirement,
        available: RequirementResolution.AvailableDriver,
    ) {
        updateRow(req.id, RowState.Downloading(null))
        val safeAssetName = available.assetName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "driver_${System.currentTimeMillis()}.zip" }
        val tmp = File(appContext.cacheDir, "cfgimport_drv_${System.currentTimeMillis()}_$safeAssetName")
        val downloadOk = runCatching {
            com.winlator.cmod.runtime.content.Downloader
                .downloadFile(available.downloadUrl, tmp) { downloaded, total ->
                    val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                    updateRow(req.id, RowState.Downloading(frac))
                }
        }.getOrDefault(false)

        if (!downloadOk) {
            runCatching { tmp.delete() }
            updateRow(req.id, RowState.Failed("Download failed"))
            return
        }

        updateRow(req.id, RowState.Installing)
        installMutex.withLock {
            val installedName = runCatching {
                val adrenotools = com.winlator.cmod.runtime.content.AdrenotoolsManager(appContext)
                adrenotools.installDriver(Uri.fromFile(tmp), available.assetName)
            }.getOrElse {
                Timber.tag(TAG).w(it, "Driver install failed for ${req.id}")
                ""
            }
            runCatching { tmp.delete() }
            if (installedName.isNotBlank()) {
                // Record the on-disk driver name so applySubstitutions can
                // rewrite graphicsDriverConfig.version to match. Without this
                // the shortcut would still point at the requested-but-uninstalled
                // identifier and the launcher would fail to resolve the driver.
                driverInstallNames[req.id] = installedName
                installedRequirementIds.add(req.id)
                updateRow(req.id, RowState.Done)
            } else {
                updateRow(req.id, RowState.Failed("Install failed"))
            }
        }
    }

    /**
     * Wraps the callback-based ContentsManager install pipeline in a suspend call.
     * The pipeline fires `onSucceed` twice — once after extraction, once after
     * `finishInstallContent`. We need to chain into `finishInstallContent` from the
     * first call, then resume on the second.
     */
    private suspend fun runInstall(
        manager: ContentsManager,
        archive: File,
        @Suppress("UNUSED_PARAMETER") expected: ContentProfile,
    ): ContentProfile? = suspendCancellableCoroutine { cont ->
        var extractedProfile: ContentProfile? = null
        val cb = object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                Timber.tag(TAG).w(e, "install failed: $reason")
                if (cont.isActive) cont.resume(null)
            }

            override fun onSucceed(profile: ContentProfile) {
                if (extractedProfile == null) {
                    extractedProfile = profile
                    runCatching { manager.finishInstallContent(profile, this) }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                } else {
                    if (cont.isActive) cont.resume(profile)
                }
            }
        }
        runCatching {
            manager.extraContentFile(Uri.parse(archive.absolutePath), cb, null)
        }.onFailure { ex ->
            Timber.tag(TAG).w(ex, "extraContentFile threw")
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun applyConfig(keysToStrip: Set<ComponentRequirement.KeyGuard>) {
        val s = session ?: return
        try {
            // Two transformations on the config JSON before writing it:
            //  1. Strip keys for components the user opted not to install.
            //  2. Rewrite the wineVersion / dxwrapperConfig.version /
            //     dxwrapperConfig.vkd3dVersion / box64Version / fexcoreVersion
            //     tokens to the substituted profile's identifier when the catalog
            //     fell back to a sibling (e.g. nightly → newer nightly). Without
            //     this rewrite the apply path writes the original token verbatim
            //     and the launcher fails at game start because the literal version
            //     it asks for isn't installed.
            // Two rewrite sources merge here: analysis-time component
            // substitutions (substitutionMap) and post-install graphics-driver
            // name mappings (driverInstallNames). Either being non-empty means
            // we need to walk the JSON.
            val rewritten = if (s.substitutionMap.isEmpty() && driverInstallNames.isEmpty()) {
                s.configJson
            } else {
                applySubstitutions(s.configJson, s.substitutionMap)
            }
            val filtered = if (keysToStrip.isEmpty()) rewritten
            else stripKeys(rewritten, keysToStrip)

            // --- Resolve the container the imported Wine/Proton must run in ---
            // A shortcut runs inside a Container whose wineprefix is physically
            // built for one Wine/Proton + arch. Writing wineVersion as a per-
            // shortcut override on a container that can't host it guarantees a
            // no-boot. Instead, move the shortcut into a container that matches
            // — reusing an existing one or creating a new one. wantedWine is
            // read post-strip: "Apply available only" that drops the wine key
            // leaves it blank → NoWineInConfig → no container change.
            val wantedWine = filtered.optJSONObject("container")
                ?.optString("wineVersion").orEmpty().takeIf { it.isNotBlank() }
            val wineInstalled = isWineInstalled(s, wantedWine)

            _state.value = ImportState.ProvisioningContainer("Setting up the container…")
            val contentsManager = s.manager
            if (contentsManager == null) {
                terminalFailure("Components manager not initialized.", s.onResult)
                return
            }
            val resolution = withContext(Dispatchers.IO) {
                val cm = ContainerManager(appContext)
                TargetContainerResolver.resolve(
                    appContext, cm, contentsManager,
                    wantedWine, wineInstalled, s.shortcut.name ?: "Imported",
                )
            }

            // --- Decide the target container + collect any warnings ----------
            val warnings = mutableListOf<String>()
            val targetContainer: Container = when (resolution) {
                is TargetContainerResolver.TargetContainerResult.Resolved ->
                    resolution.container
                TargetContainerResolver.TargetContainerResult.NoWineInConfig ->
                    s.container
                is TargetContainerResolver.TargetContainerResult.WineUnavailable -> {
                    warnings += "Wine/Proton '${resolution.wantedIdentifier}' could not be " +
                        "installed — kept your current container; Wine version not changed."
                    s.container
                }
                is TargetContainerResolver.TargetContainerResult.CreateFailed -> {
                    warnings += "Couldn't create a container for " +
                        "'${resolution.wantedIdentifier}'; Wine version not changed."
                    s.container
                }
            }
            val createdContainer =
                (resolution as? TargetContainerResolver.TargetContainerResult.Resolved)
                    ?.created == true
            val needsMove = targetContainer.id != s.container.id

            // --- Move the shortcut into the target container, if needed ------
            // Stay in ProvisioningContainer across the physical .desktop move so
            // cancel() keeps refusing to interrupt it — a half-done move could
            // duplicate the .desktop onto two containers. Switch to Applying
            // only once the move has settled.
            val movedShortcut: Shortcut? = if (needsMove) {
                withContext(Dispatchers.IO) { moveShortcutFile(s.shortcut, targetContainer) }
            } else {
                null
            }
            val moveFailed = needsMove && movedShortcut == null
            if (moveFailed) {
                warnings += "Couldn't move ${s.shortcut.name} into the matching " +
                    "container; kept it on the current one."
            }
            // The Shortcut to write settings into: the freshly-loaded instance
            // when the move succeeded (the original is stale — its .file was
            // deleted), otherwise the original. effectiveTarget is where it
            // actually lives now (old container if the move failed).
            val applyShortcut = movedShortcut ?: s.shortcut
            val effectiveTarget = if (moveFailed) s.container else targetContainer

            // --- Apply the remaining settings as per-shortcut overrides ------
            _state.value = ImportState.Applying
            val applyResult = withContext(Dispatchers.IO) {
                val r = ConfigSerializer.applyToShortcut(
                    filtered, effectiveTarget, applyShortcut,
                    skipWineVersionOverride = true,
                )
                // Honour the per-shortcut overrides we just wrote. Game shortcuts
                // are created with `use_container_defaults=1` (Steam/Epic/GOG/.lnk
                // creation paths all set it), and while it is "1" the launcher's
                // getSettingExtra() ignores EVERY per-shortcut extra and reads the
                // container value instead — so an imported config would silently
                // do nothing. "0" makes the launcher honour the extras; keys the
                // config didn't set still fall back to the container (empty extra
                // → container value), so this only enables the imported settings.
                applyShortcut.putExtra("use_container_defaults", "0")
                // The container owns the Wine now — drop any per-shortcut
                // wineVersion override, including a stale one left by an
                // earlier (pre-fix) import.
                applyShortcut.putExtra("wineVersion", null)
                applyShortcut.saveData()
                r
            }
            warnings += applyResult.warnings

            // --- Result message + library refresh ----------------------------
            val moved = needsMove && !moveFailed
            val base = when {
                moved && createdContainer ->
                    "Config imported — set up a container for the imported Wine and " +
                        "moved ${s.shortcut.name} into it."
                moved ->
                    "Config imported — moved ${s.shortcut.name} to a matching container."
                else -> "Config imported into ${s.shortcut.name}."
            }
            val strippedNote =
                if (keysToStrip.isEmpty()) "" else " (${keysToStrip.size} key(s) skipped)"
            val warningsTail = warnings.take(3).joinToString("\n")
            val message = if (warningsTail.isBlank()) "$base$strippedNote"
            else "$base$strippedNote\n$warningsTail"

            if (moved) {
                // Drop the ghost library entry that still points at the old
                // container. Best-effort; tolerate the UI host being gone.
                mainHandler.post {
                    runCatching { com.winlator.cmod.app.shell.UnifiedActivity.refreshLibrary() }
                        .onFailure { Timber.tag(TAG).w(it, "refreshLibrary failed") }
                }
            }
            _state.value = ImportState.Done(message)
            s.onResult(message)
            session = null
        } catch (t: kotlinx.coroutines.CancellationException) {
            // cancel()/dispose() already delivered the terminal outcome — don't
            // fire onResult again or flip the state back out of Idle.
            throw t
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "applyConfig failed")
            terminalFailure("Could not apply config: ${t.message ?: t::class.simpleName}", s.onResult)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Whether the imported Wine/Proton ([wantedWine], post-substitution) is
     * genuinely on disk now. Drives [TargetContainerResolver]: a row the user
     * deselected or one that failed to download must not let the resolver
     * create/move into a container for a wine that isn't there.
     *
     * MAIN wine ships with the app. Otherwise the wine is installed iff the
     * detector saw it as [RequirementResolution.Installed], or it was
     * downloadable and its install actually succeeded this run (tracked in
     * [installedRequirementIds]).
     */
    private fun isWineInstalled(s: Session, wantedWine: String?): Boolean {
        if (wantedWine == null) return false
        if (com.winlator.cmod.runtime.wine.WineInfo.isMainWineVersion(wantedWine)) return true
        val wineEntry = s.entries.firstOrNull {
            it.requirement.id == ComponentRequirement.ID_WINE
        } ?: return false
        return when (wineEntry.resolution) {
            is RequirementResolution.Installed -> true
            // Wine is downloaded through the ContentsManager path, never as an
            // AvailableDriver — so only Available can transition to installed,
            // and only when its install actually succeeded this run.
            is RequirementResolution.Available ->
                ComponentRequirement.ID_WINE in installedRequirementIds
            else -> false
        }
    }

    /**
     * Moves [shortcut]'s `.desktop` (and sibling `.lnk`) into [target]'s Desktop
     * directory and records the `container_id` override — the same move the
     * settings dialog performs (ShortcutSettingsComposeDialog.saveSettings).
     * The `container_id` is written + persisted BEFORE the copy so the moved
     * file carries it.
     *
     * Returns a freshly-loaded [Shortcut] bound to [target] (the caller's
     * original [Shortcut] is stale afterwards — its `.file` is deleted), or
     * null if the physical move failed, in which case the original shortcut is
     * left intact on its current container with `container_id` reverted.
     */
    private fun moveShortcutFile(shortcut: Shortcut, target: Container): Shortcut? {
        val originalContainerId = shortcut.container.id
        val oldFile = shortcut.file
        shortcut.putExtra("container_id", target.id.toString())
        shortcut.putExtra("cloud_force_download", "1")
        // Strip any per-shortcut wineVersion override BEFORE this pre-copy save,
        // so the .desktop copied into the new container never carries a stale
        // override. The target container owns the Wine; if a later step throws,
        // the moved shortcut is still safe to launch (no override pointing at a
        // wine the new container can't run).
        shortcut.putExtra("wineVersion", null)
        shortcut.saveData()
        return runCatching {
            val newDesktopDir = target.desktopDir
            if (!newDesktopDir.exists() && !newDesktopDir.mkdirs()) {
                throw java.io.IOException("Could not create ${newDesktopDir.path}")
            }
            val newShortcutFile = File(newDesktopDir, oldFile.name)
            if (!com.winlator.cmod.shared.io.FileUtils.copy(oldFile, newShortcutFile)) {
                throw java.io.IOException("Failed to copy ${oldFile.path}")
            }
            oldFile.delete()
            // Carry the sibling .lnk so launcher paths that key off it stay consistent.
            val lnkName = oldFile.name.substringBeforeLast(".desktop") + ".lnk"
            val oldLnk = File(oldFile.parentFile, lnkName)
            if (oldLnk.exists()) {
                val newLnk = File(newDesktopDir, lnkName)
                if (com.winlator.cmod.shared.io.FileUtils.copy(oldLnk, newLnk)) {
                    oldLnk.delete()
                } else {
                    // Non-fatal: the .desktop (the load-bearing file) did move.
                    Timber.tag(TAG).w("moveShortcutFile: .lnk copy failed, left at ${oldLnk.path}")
                }
            }
            Shortcut(target, newShortcutFile)
        }.getOrElse { ex ->
            Timber.tag(TAG).w(ex, "moveShortcutFile failed; reverting container_id")
            shortcut.putExtra("container_id", originalContainerId.toString())
            runCatching { shortcut.saveData() }
            null
        }
    }

    private fun computeArchMismatch(json: JSONObject, container: Container): ArchMismatch? {
        val ident = json.optJSONObject("container")?.optString("wineVersion").orEmpty()
        if (ident.isBlank()) return null
        val wantedArch = WINE_ARCH_REGEX.find(ident)?.groupValues?.get(1) ?: return null
        val containerArch = container.getExtra("wineprefixArch")?.takeIf { it.isNotEmpty() } ?: "x86_64"
        return if (wantedArch.equals(containerArch, ignoreCase = true)) null
        else ArchMismatch(expectedArch = wantedArch, containerArch = containerArch)
    }

    /**
     * Rewrite the version tokens in the config JSON to point at the substituted
     * profile that's actually installed. Operates on a deep copy of the input.
     *
     * - `wine` ⇒ `container.wineVersion`: full entry name via `getEntryName`
     *   (e.g. `Proton-9.0-arm64ec-2`) — the launcher's `WineInfo.fromIdentifier`
     *   parses both the canonical 4-part form and the regex-shaped legacy form.
     * - `dxvk` ⇒ inside `container.dxwrapperConfig`, replace `version=…` with
     *   `verName-verCode` of the substitute (the dropdown format).
     * - `vkd3d` ⇒ inside `container.dxwrapperConfig`, replace `vkd3dVersion=…`.
     * - `box64` / `fexcore` ⇒ `container.box64Version` / `fexcoreVersion`,
     *   preserving the original shape: if the source carried a verCode suffix,
     *   write `verName-verCode`; otherwise write just `verName`.
     */
    private fun applySubstitutions(
        src: JSONObject,
        substitutions: Map<String, com.winlator.cmod.runtime.content.ContentProfile>,
    ): JSONObject {
        val out = JSONObject(src.toString())
        val container = out.optJSONObject("container") ?: return out
        substitutions.forEach { (requirementId, profile) ->
            when (requirementId) {
                "wine" -> {
                    val newId = com.winlator.cmod.runtime.content.ContentsManager.getEntryName(profile)
                    container.put("wineVersion", newId)
                }
                "dxvk" -> {
                    val newToken = "${profile.verName}-${profile.verCode}"
                    val cfg = container.optString("dxwrapperConfig", "")
                    if (cfg.isNotEmpty()) container.put("dxwrapperConfig", rewriteCsv(cfg, "version", newToken))
                }
                "vkd3d" -> {
                    val newToken = "${profile.verName}-${profile.verCode}"
                    val cfg = container.optString("dxwrapperConfig", "")
                    if (cfg.isNotEmpty()) container.put("dxwrapperConfig", rewriteCsv(cfg, "vkd3dVersion", newToken))
                }
                "box64" -> {
                    val origin = container.optString("box64Version", "")
                    container.put("box64Version", preserveShape(origin, profile))
                }
                "fexcore" -> {
                    val origin = container.optString("fexcoreVersion", "")
                    container.put("fexcoreVersion", preserveShape(origin, profile))
                }
                else -> Unit // graphics-driver substitutions handled below
            }
        }
        // Graphics-driver rewrite: we only know the installed driver's on-disk
        // name *after* the adrenotools install runs, so this path is keyed off
        // [driverInstallNames] (populated by downloadAndInstallDriver) rather
        // than the analysis-time substitutionMap. Use the semicolon-delimited
        // graphicsDriverConfig writer (NOT the dxwrapper comma-CSV one).
        driverInstallNames["graphics-driver"]?.let { installedName ->
            val cfg = container.optString("graphicsDriverConfig", "")
            container.put("graphicsDriverConfig", rewriteGraphicsDriverVersion(cfg, installedName))
        }
        return out
    }

    /**
     * Replace `version=<old>` inside a semicolon-delimited
     * [GraphicsDriverConfigUtils] string, preserving every other pair. Adds a
     * `version=…` pair if the string didn't already have one (so the apply
     * path still points at the correct driver even when the source config was
     * minimal).
     */
    private fun rewriteGraphicsDriverVersion(csv: String, newVersion: String): String {
        if (csv.isBlank()) return "version=$newVersion"
        val parts = csv.split(";").filter { it.isNotBlank() }
        var found = false
        val mutated = parts.map { part ->
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).equals("version", ignoreCase = true)) {
                found = true
                "version=$newVersion"
            } else part
        }.toMutableList()
        if (!found) mutated += "version=$newVersion"
        return mutated.joinToString(";")
    }

    /** Returns `verName-verCode` if [origin] ends with `-<digits>`, else just `verName`. */
    private fun preserveShape(
        origin: String,
        profile: com.winlator.cmod.runtime.content.ContentProfile,
    ): String =
        if (Regex("-\\d+$").containsMatchIn(origin)) "${profile.verName}-${profile.verCode}"
        else profile.verName

    /** Replace `key=…` in a comma-delimited key=value string, leaving other pairs intact. */
    private fun rewriteCsv(csv: String, key: String, newValue: String): String {
        val parts = csv.split(",")
        var found = false
        val mutated = parts.map { part ->
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).equals(key, ignoreCase = true)) {
                found = true
                "$key=$newValue"
            } else part
        }.toMutableList()
        if (!found) mutated += "$key=$newValue"
        return mutated.joinToString(",")
    }

    private fun stripKeys(src: JSONObject, guards: Set<ComponentRequirement.KeyGuard>): JSONObject {
        val out = JSONObject(src.toString()) // deep copy
        guards.forEach { g ->
            when (g.block) {
                ComponentRequirement.KeyGuard.Block.CONTAINER ->
                    out.optJSONObject("container")?.remove(g.key)
                ComponentRequirement.KeyGuard.Block.CONTAINER_EXTRAS ->
                    out.optJSONObject("containerExtras")?.remove(g.key)
                ComponentRequirement.KeyGuard.Block.SHORTCUT_EXTRAS ->
                    out.optJSONObject("shortcutExtras")?.remove(g.key)
                ComponentRequirement.KeyGuard.Block.DXWRAPPER_CONFIG_VKD3D -> {
                    // Strip just the vkd3dVersion key from the comma-delimited string.
                    val container = out.optJSONObject("container") ?: return@forEach
                    val cfg = container.optString("dxwrapperConfig", "")
                    if (cfg.isNotBlank()) {
                        val rebuilt = cfg.split(",")
                            .filterNot { it.startsWith("vkd3dVersion=", ignoreCase = true) }
                            .joinToString(",")
                        container.put("dxwrapperConfig", rebuilt)
                    }
                }
            }
        }
        return out
    }

    private fun updateRow(id: String, next: RowState) {
        _state.update { cur ->
            if (cur !is ImportState.Downloading) cur
            else cur.copy(rowStates = cur.rowStates + (id to next))
        }
    }

    private fun terminalFailure(reason: String, onResult: (String) -> Unit) {
        _state.value = ImportState.Failed(reason)
        onResult(reason)
        session = null
    }

    private fun fetchCatalogJson(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val url = prefs.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES)
            ?: ContentsManager.REMOTE_PROFILES
        return runCatching { Downloader.downloadString(url) }.getOrNull()
    }

    private data class Session(
        val configJson: JSONObject,
        val container: Container,
        val shortcut: Shortcut,
        val onResult: (String) -> Unit,
        val manager: ContentsManager? = null,
        /**
         * Per-requirement substitution map: when the catalog had no exact match
         * and the resolver fell back to a sibling (e.g. nightly→newer nightly),
         * we record the substitute profile here so [applyConfig] can rewrite the
         * config JSON to point at the version that's actually installed on disk.
         */
        val substitutionMap: Map<String, com.winlator.cmod.runtime.content.ContentProfile> = emptyMap(),
        /**
         * The detector's analysis entries, captured so [applyConfig] — which
         * runs after the UI has left the entry-carrying states — can tell
         * whether the imported Wine requirement ended up installed.
         */
        val entries: List<RequirementEntry> = emptyList(),
    )

    companion object {
        private const val TAG = "ConfigImportCoordinator"
        private val WINE_ARCH_REGEX = Regex("(?i)-(x86_64|arm64ec|x86)\\s*$")
    }
}
