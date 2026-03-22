package com.winlator.cmod.google

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.games.GamesSignInClient
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.SnapshotsClient
import com.google.android.gms.games.snapshot.Snapshot
import com.google.android.gms.games.snapshot.SnapshotMetadataChange
import com.google.android.gms.tasks.Tasks
import com.winlator.cmod.R
import com.winlator.cmod.PluviaApp
import com.winlator.cmod.epic.service.EpicAuthManager
import com.winlator.cmod.epic.service.EpicService
import com.winlator.cmod.gog.service.GOGAuthManager
import com.winlator.cmod.gog.service.GOGService
import com.winlator.cmod.steam.service.SteamService
import com.winlator.cmod.steam.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object CloudSyncManager {
    const val REQUEST_CODE_SAVED_GAMES_PERMISSIONS = 4101

    private const val TAG = "CloudSyncManager"
    private const val PREFS_NAME = "google_store_login_sync"
    private const val KEY_GOOGLE_SYNC_ENABLED = "google_sync_enabled"
    private const val KEY_PENDING_BACKUP = "pending_backup"
    private const val KEY_LAST_SYNC_FINGERPRINT = "last_sync_fingerprint"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_AUTO_RESTORE_COMPLETED = "auto_restore_completed"
    private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
    private const val KEY_SNAPSHOTS_BLOCKED = "snapshots_blocked"
    private const val SNAPSHOT_NAME = "store_logins_v1"
    private const val ZIP_MANIFEST = "manifest.json"
    private const val ZIP_STEAM = "stores/steam.json"
    private const val ZIP_EPIC = "stores/epic_credentials.json"
    private const val ZIP_GOG = "stores/gog_auth.json"
    private const val ZIP_AMAZON = "stores/amazon_credentials.json"

    private const val STORE_STEAM = "Steam"
    private const val STORE_EPIC = "Epic"
    private const val STORE_GOG = "GOG"
    private const val STORE_AMAZON = "Amazon"

    private const val AMAZON_PRIMARY_RELATIVE_PATH = "amazon/credentials.json"
    private val AMAZON_DISCOVERY_PATHS = listOf(
        AMAZON_PRIMARY_RELATIVE_PATH,
        "amazon/auth.json",
        "amazon_auth.json"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val syncMutex = Mutex()
    private val permissionEvents = kotlinx.coroutines.flow.MutableSharedFlow<PermissionResumeEvent>(extraBufferCapacity = 1)
    private var pendingSavedGamesAction: PendingSavedGamesAction? = null

    val savedGamesPermissionEvents: kotlinx.coroutines.flow.SharedFlow<PermissionResumeEvent> =
        permissionEvents.asSharedFlow()

    data class StoreLoginSyncState(
        val googleSignedIn: Boolean = false,
        val localStores: Set<String> = emptySet(),
        val cloudStores: Set<String> = emptySet(),
        val lastSyncTime: Long? = null,
        val status: SyncStatus = SyncStatus.NOT_SIGNED_IN,
        val detail: String = "Sign in to Google Play Games to sync store logins."
    )

    enum class SyncStatus {
        NOT_SIGNED_IN,
        EMPTY,
        SYNCED,
        BACKUP_PENDING,
        RESTORE_AVAILABLE,
        ERROR
    }

    private data class StorePayload(
        val createdAt: Long,
        val stores: Map<String, ByteArray>,
        val fingerprint: String
    )

    private data class SnapshotReadResult(
        val payload: StorePayload?,
        val lastModifiedTime: Long?
    )

    data class PermissionResumeEvent(
        val success: Boolean,
        val message: String
    )

    private enum class PendingSavedGamesAction {
        BACKUP,
        RESTORE
    }

    private enum class SavedGamesPermissionState {
        GRANTED,
        REQUESTED,
        UNAVAILABLE
    }

    private data class SyncSummary(
        val restoredStores: Set<String> = emptySet(),
        val uploadedStores: Set<String> = emptySet(),
        val localStores: Set<String> = emptySet(),
        val cloudStores: Set<String> = emptySet(),
        val lastSyncTime: Long? = null
    ) {
        fun message(): String {
            return when {
                restoredStores.isNotEmpty() && uploadedStores.isNotEmpty() ->
                    "Store logins restored and synced for ${restoredStores.union(uploadedStores).joinToString()}."
                restoredStores.isNotEmpty() ->
                    "Restored store logins for ${restoredStores.joinToString()}."
                uploadedStores.isNotEmpty() ->
                    "Backed up store logins for ${uploadedStores.joinToString()}."
                cloudStores.isNotEmpty() ->
                    "Store login sync is ready."
                else ->
                    "No store logins available to sync yet."
            }
        }
    }

    fun signIn(activity: Activity, callback: (Boolean, String) -> Unit) {
        val gamesSignInClient = PlayGames.getGamesSignInClient(activity)
        Log.i(TAG, "Starting Google Play Games sign-in for store login sync")
        Timber.tag(TAG).i("Starting Google Play Games sign-in for store login sync")
        gamesSignInClient.signIn().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result?.isAuthenticated == true) {
                Log.d(TAG, "Sign in successful to Play Games Services; requesting Saved Games consent")
                Timber.tag(TAG).i("Google Play Games sign-in succeeded; requesting Saved Games access")
                requestSavedGamesAccess(activity, gamesSignInClient, callback)
            } else {
                Log.e(TAG, "Sign in failed: ${task.exception?.message}")
                Timber.tag(TAG).e(task.exception, "Google Play Games sign-in failed")
                callback(false, "Google Play Games sign-in failed.")
            }
        }
    }

    private fun requestSavedGamesAccess(
        activity: Activity,
        gamesSignInClient: GamesSignInClient,
        callback: (Boolean, String) -> Unit
    ) {
        scope.launch {
            prefs(activity).edit().putBoolean(KEY_GOOGLE_SYNC_ENABLED, true).apply()
            
            Timber.tag(TAG).i("Starting smart sync with Play Games Services v2")
            val syncResult = runCatching {
                performSmartSync(activity, preferRestoreForMissingStores = true)
            }
            val message = syncResult.fold(
                onSuccess = {
                    clearSyncError(activity)
                    it.message()
                },
                onFailure = { error ->
                    rememberSyncError(activity, error)
                }
            )
            callback(true, message)
        }
    }

    fun signOut(activity: Activity, callback: (Boolean, String) -> Unit) {
        Log.i(TAG, "Signing out of Google Play Games store login sync")
        prefs(activity).edit()
            .putBoolean(KEY_GOOGLE_SYNC_ENABLED, false)
            .putBoolean(KEY_PENDING_BACKUP, false)
            .apply()
        clearSyncError(activity)
        callback(true, "Signed out of Google Play Games for this app.")
    }

    fun isAuthenticated(activity: Activity, callback: (Boolean) -> Unit) {
        val gamesSignInClient = PlayGames.getGamesSignInClient(activity)
        gamesSignInClient.isAuthenticated.addOnCompleteListener { task ->
            val authenticated = task.isSuccessful && task.result?.isAuthenticated == true
            callback(authenticated)
        }
    }

    fun queueStoreLoginBackup(context: Context) {
        prefs(context).edit().putBoolean(KEY_PENDING_BACKUP, true).apply()
        Timber.tag(TAG).i("Queued store login backup")

        val currentActivity = PluviaApp.currentForegroundActivity
        if (currentActivity != null) {
            Timber.tag(TAG).d("Foreground activity available, flushing pending backup immediately")
            flushPendingBackup(currentActivity)
        } else {
            Timber.tag(TAG).d("No foreground activity available, pending backup will flush later")
        }
    }

    fun flushPendingBackup(activity: Activity) {
        scope.launch {
            runCatching {
                if (!prefs(activity).getBoolean(KEY_PENDING_BACKUP, false)) {
                    Timber.tag(TAG).v("flushPendingBackup skipped: no pending backup")
                    return@runCatching
                }
                Timber.tag(TAG).i("Flushing pending store login backup")
                performBackupIfAuthenticated(activity)
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to flush pending store login backup")
            }
        }
    }

    suspend fun syncOnGoogleScreenOpened(activity: Activity): StoreLoginSyncState {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext StoreLoginSyncState(
                    googleSignedIn = false,
                    localStores = collectLocalStoreNames(activity),
                    status = SyncStatus.NOT_SIGNED_IN,
                    detail = "Sign in to Google Play Games to sync store logins."
                )
            }
            val authenticated = isAuthenticatedBlocking(activity)
            Timber.tag(TAG).i("Google screen opened; authenticated=%s", authenticated)
            if (authenticated) {
                val prefs = prefs(activity)
                if (!snapshotsBlocked(activity) && hasSavedGamesPermission(activity)) {
                    runCatching {
                        performSmartSync(activity, preferRestoreForMissingStores = true)
                    }.onFailure { error ->
                        Timber.tag(TAG).e(error, "Initial Google screen sync failed")
                    }
                } else if (!hasSavedGamesPermission(activity)) {
                    Timber.tag(TAG).i("Skipping automatic Google screen sync because DRIVE_APPFOLDER permission is missing")
                } else {
                    Timber.tag(TAG).w("Skipping automatic Google screen sync because snapshots are blocked")
                }
                prefs.edit().putBoolean(KEY_AUTO_RESTORE_COMPLETED, true).apply()
            }
            readStateInternal(activity, authenticated)
        }
    }

    suspend fun readStoreLoginState(activity: Activity): StoreLoginSyncState {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext StoreLoginSyncState(
                    googleSignedIn = false,
                    localStores = collectLocalStoreNames(activity),
                    status = SyncStatus.NOT_SIGNED_IN,
                    detail = "Sign in to Google Play Games to sync store logins."
                )
            }
            val authenticated = isAuthenticatedBlocking(activity)
            Timber.tag(TAG).d("Reading store login sync state; authenticated=%s", authenticated)
            readStateInternal(activity, authenticated)
        }
    }

    suspend fun restoreStoreLogins(activity: Activity): String {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext "Sign in to Google Play Games first."
            }
            if (!isAuthenticatedBlocking(activity)) {
                Timber.tag(TAG).w("restoreStoreLogins aborted: Google Play Games not authenticated")
                return@withContext "Sign in to Google Play Games first."
            }
            when (ensureSavedGamesPermission(activity, PendingSavedGamesAction.RESTORE)) {
                SavedGamesPermissionState.UNAVAILABLE -> return@withContext "Sign in to Google Play Games first."
                SavedGamesPermissionState.REQUESTED -> return@withContext "Grant Google Saved Games permission to continue restore."
                SavedGamesPermissionState.GRANTED -> Unit
            }

            Log.i(TAG, "Starting manual store login restore")
            clearSyncError(activity)
            Timber.tag(TAG).i("Starting manual store login restore")
            runCatching {
                val summary = syncMutex.withLock {
                    val remote = readRemotePayload(activity)
                    val payload = remote.payload ?: return@withLock SyncSummary(
                        localStores = collectLocalStoreNames(activity),
                        cloudStores = emptySet()
                    )

                    val restored = restoreMissingStores(activity, payload)
                    if (restored.isNotEmpty()) {
                        prefs(activity).edit().putBoolean(KEY_AUTO_RESTORE_COMPLETED, true).apply()
                    }

                    val local = collectLocalPayload(activity)
                    SyncSummary(
                        restoredStores = restored,
                        localStores = local.stores.keys,
                        cloudStores = payload.stores.keys,
                        lastSyncTime = remote.lastModifiedTime ?: payload.createdAt
                    )
                }

                clearSyncError(activity)
                if (summary.cloudStores.isEmpty()) {
                    "No saved store logins were found in Google cloud sync."
                } else {
                    summary.message()
                }
            }.getOrElse { error ->
                rememberSyncError(activity, error)
            }
        }
    }

    suspend fun backupStoreLogins(activity: Activity): String {
        return withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                return@withContext "Sign in to Google Play Games first."
            }
            if (!isAuthenticatedBlocking(activity)) {
                Timber.tag(TAG).w("backupStoreLogins aborted: Google Play Games not authenticated")
                return@withContext "Sign in to Google Play Games first."
            }
            when (ensureSavedGamesPermission(activity, PendingSavedGamesAction.BACKUP)) {
                SavedGamesPermissionState.UNAVAILABLE -> return@withContext "Sign in to Google Play Games first."
                SavedGamesPermissionState.REQUESTED -> return@withContext "Grant Google Saved Games permission to continue backup."
                SavedGamesPermissionState.GRANTED -> Unit
            }

            Log.i(TAG, "Starting manual store login backup")
            clearSyncError(activity)
            Timber.tag(TAG).i("Starting manual store login backup")
            runCatching {
                val summary = syncMutex.withLock {
                    val localPayload = collectLocalPayload(activity)
                    if (localPayload.stores.isEmpty()) {
                        return@withLock SyncSummary(
                            localStores = emptySet(),
                            cloudStores = readRemotePayload(activity).payload?.stores?.keys ?: emptySet()
                        )
                    }

                    val uploaded = backupPayload(activity, localPayload)
                    SyncSummary(
                        uploadedStores = uploaded,
                        localStores = localPayload.stores.keys,
                        cloudStores = localPayload.stores.keys,
                        lastSyncTime = prefs(activity).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L }
                    )
                }

                clearSyncError(activity)
                if (summary.uploadedStores.isEmpty()) {
                    "No local store logins are available to back up."
                } else {
                    summary.message()
                }
            }.getOrElse { error ->
                rememberSyncError(activity, error)
            }
        }
    }

    private suspend fun performSmartSync(
        activity: Activity,
        preferRestoreForMissingStores: Boolean
    ): SyncSummary {
        return withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val localBefore = collectLocalPayload(activity)
                val remote = readRemotePayload(activity)
                val remotePayload = remote.payload
                Timber.tag(TAG).i(
                    "performSmartSync localStores=%s remoteStores=%s pendingBackup=%s preferRestore=%s",
                    localBefore.stores.keys,
                    remotePayload?.stores?.keys ?: emptySet<String>(),
                    prefs(activity).getBoolean(KEY_PENDING_BACKUP, false),
                    preferRestoreForMissingStores
                )

                val restoredStores = if (preferRestoreForMissingStores && remotePayload != null) {
                    restoreMissingStores(activity, remotePayload)
                } else {
                    emptySet()
                }

                val localAfterRestore = collectLocalPayload(activity)
                val shouldUpload = localAfterRestore.stores.isNotEmpty() &&
                    (prefs(activity).getBoolean(KEY_PENDING_BACKUP, false) ||
                        remotePayload == null ||
                        localAfterRestore.fingerprint != remotePayload.fingerprint)

                val uploadedStores = if (shouldUpload) {
                    Timber.tag(TAG).i("Local payload differs or backup pending; uploading stores=%s", localAfterRestore.stores.keys)
                    backupPayload(activity, localAfterRestore)
                } else {
                    Timber.tag(TAG).d("Cloud backup already current; skipping upload")
                    emptySet()
                }

                val effectiveCloudStores = if (uploadedStores.isNotEmpty()) {
                    localAfterRestore.stores.keys
                } else {
                    remotePayload?.stores?.keys ?: emptySet()
                }

                SyncSummary(
                    restoredStores = restoredStores,
                    uploadedStores = uploadedStores,
                    localStores = localAfterRestore.stores.keys,
                    cloudStores = effectiveCloudStores,
                    lastSyncTime = if (uploadedStores.isNotEmpty()) {
                        prefs(activity).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L }
                    } else {
                        remote.lastModifiedTime ?: remotePayload?.createdAt
                    }
                )
            }
        }
    }

    private suspend fun performBackupIfAuthenticated(activity: Activity) {
        withContext(Dispatchers.IO) {
            if (!isGoogleSyncEnabled(activity)) {
                Timber.tag(TAG).d("performBackupIfAuthenticated skipped: Google sync not enabled by user")
                return@withContext
            }
            if (snapshotsBlocked(activity)) {
                Timber.tag(TAG).w("performBackupIfAuthenticated skipped: snapshots are currently blocked")
                return@withContext
            }
            if (!isAuthenticatedBlocking(activity)) {
                Timber.tag(TAG).w("performBackupIfAuthenticated skipped: Google Play Games not authenticated")
                return@withContext
            }
            if (!hasSavedGamesPermission(activity)) {
                Timber.tag(TAG).w("performBackupIfAuthenticated skipped: DRIVE_APPFOLDER permission missing")
                return@withContext
            }

            syncMutex.withLock {
                val localPayload = collectLocalPayload(activity)
                if (localPayload.stores.isEmpty()) {
                    Timber.tag(TAG).i("No local store logins found; clearing pending backup flag")
                    prefs(activity).edit().putBoolean(KEY_PENDING_BACKUP, false).apply()
                    return@withLock
                }
                Timber.tag(TAG).i("Uploading pending backup for stores=%s", localPayload.stores.keys)
                backupPayload(activity, localPayload)
            }
        }
    }

    private suspend fun backupPayload(activity: Activity, payload: StorePayload): Set<String> {
        val client = freshSnapshotsClient(activity) ?: return emptySet()
        Log.i(TAG, "Opening snapshot for backup: $SNAPSHOT_NAME")
        Timber.tag(TAG).i("Opening snapshot for backup: %s", SNAPSHOT_NAME)
        val snapshot = openSnapshot(activity, client, createIfMissing = true) ?: return emptySet()
        try {
            val bytes = payloadToZip(payload)
            Timber.tag(TAG).i("Writing %d bytes to snapshot for stores=%s", bytes.size, payload.stores.keys)
            if (!snapshot.snapshotContents.writeBytes(bytes)) {
                throw IllegalStateException("Unable to write snapshot contents")
            }

            val metadata = SnapshotMetadataChange.Builder()
                .setDescription("Store logins: ${payload.stores.keys.joinToString()}")
                .setPlayedTimeMillis(0L)
                .setProgressValue(payload.stores.size.toLong())
                .build()

            Tasks.await(client.commitAndClose(snapshot, metadata))
            clearSyncError(activity)
            Timber.tag(TAG).i("Snapshot commitAndClose succeeded for stores=%s", payload.stores.keys)

            prefs(activity).edit()
                .putBoolean(KEY_PENDING_BACKUP, false)
                .putString(KEY_LAST_SYNC_FINGERPRINT, payload.fingerprint)
                .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                .apply()

            return payload.stores.keys
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Snapshot backup failed for stores=%s", payload.stores.keys)
            runCatching { Tasks.await(client.discardAndClose(snapshot)) }
            throw error
        }
    }

    private suspend fun readRemotePayload(activity: Activity): SnapshotReadResult {
        val client = freshSnapshotsClient(activity) ?: return SnapshotReadResult(null, null)
        Timber.tag(TAG).d("Opening snapshot for read: %s", SNAPSHOT_NAME)
        val snapshot = openSnapshot(activity, client, createIfMissing = false) ?: return SnapshotReadResult(null, null)
        return try {
            val bytes = snapshot.snapshotContents.readFully()
            val payload = if (bytes.isNotEmpty()) zipToPayload(bytes) else null
            val modifiedTime = snapshot.metadata.lastModifiedTimestamp
            clearSyncError(activity)
            Timber.tag(TAG).i(
                "Read snapshot bytes=%d remoteStores=%s lastModified=%d",
                bytes.size,
                payload?.stores?.keys ?: emptySet<String>(),
                modifiedTime
            )
            Tasks.await(client.discardAndClose(snapshot))
            SnapshotReadResult(payload, modifiedTime)
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Snapshot read failed")
            runCatching { Tasks.await(client.discardAndClose(snapshot)) }
            throw error
        }
    }

    private suspend fun openSnapshot(
        context: Context,
        client: SnapshotsClient,
        createIfMissing: Boolean
    ): Snapshot? {
        return try {
            Log.i(TAG, "SnapshotsClient.open(name=$SNAPSHOT_NAME, createIfMissing=$createIfMissing)")
            Timber.tag(TAG).d("SnapshotsClient.open(name=%s, createIfMissing=%s)", SNAPSHOT_NAME, createIfMissing)
            val result = Tasks.await(
                client.open(
                    SNAPSHOT_NAME,
                    createIfMissing,
                    SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED
                )
            )
            if (result.isConflict) {
                Timber.tag(TAG).w("Snapshot conflict detected for %s", SNAPSHOT_NAME)
            } else {
                Timber.tag(TAG).d("Snapshot open returned without conflict")
            }
            result.data ?: result.conflict?.snapshot ?: result.conflict?.conflictingSnapshot
        } catch (error: Exception) {
            if (!createIfMissing) {
                Timber.tag(TAG).d("No existing store login snapshot found: ${error.message}")
                return null
            }
            rememberSyncError(context, error)
            Timber.tag(TAG).e(error, "Snapshot open failed for createIfMissing=%s", createIfMissing)
            throw error
        }
    }

    private suspend fun freshSnapshotsClient(activity: Activity): SnapshotsClient? {
        return PlayGames.getSnapshotsClient(activity)
    }

    // Removed resolveFreshSavedGamesAccount

    private suspend fun ensureSavedGamesPermission(
        activity: Activity,
        pendingAction: PendingSavedGamesAction
    ): SavedGamesPermissionState {
        return SavedGamesPermissionState.GRANTED
    }

    private fun hasSavedGamesPermissionCached(activity: Activity): Boolean {
        return true
    }

    private suspend fun hasSavedGamesPermission(activity: Activity): Boolean {
        return true
    }

    fun onSavedGamesPermissionResult(activity: Activity) {
        // No-op for Play Games Services v2 since permissions are handled internally during sign-in
    }

    // Removed buildSavedGamesSignInOptions

    private suspend fun readStateInternal(
        activity: Activity,
        authenticated: Boolean
    ): StoreLoginSyncState {
        val localStores = collectLocalStoreNames(activity)
        if (!authenticated) {
            return StoreLoginSyncState(
                googleSignedIn = false,
                localStores = localStores,
                status = SyncStatus.NOT_SIGNED_IN,
                detail = if (localStores.isEmpty()) {
                    "Sign in to Google Play Games to sync store logins."
                } else {
                    "Sign in to back up ${localStores.joinToString()} login tokens."
                }
            )
        }

        if (snapshotsBlocked(activity)) {
            return StoreLoginSyncState(
                googleSignedIn = true,
                localStores = localStores,
                status = SyncStatus.ERROR,
                detail = prefs(activity).getString(KEY_LAST_SYNC_ERROR, null)
                    ?: "Google Saved Games is unavailable for this build."
            )
        }

        if (!hasSavedGamesPermission(activity)) {
            return StoreLoginSyncState(
                googleSignedIn = true,
                localStores = localStores,
                status = if (localStores.isNotEmpty()) SyncStatus.BACKUP_PENDING else SyncStatus.EMPTY,
                detail = if (localStores.isNotEmpty()) {
                    "Grant Google Saved Games access to back up ${localStores.joinToString()} login tokens."
                } else {
                    "Grant Google Saved Games access to enable cloud restore."
                }
            )
        }

        return try {
            val remote = readRemotePayload(activity)
            val cloudStores = remote.payload?.stores?.keys ?: emptySet()
            val pendingBackup = prefs(activity).getBoolean(KEY_PENDING_BACKUP, false)
            val status = when {
                cloudStores.isEmpty() && localStores.isEmpty() -> SyncStatus.EMPTY
                pendingBackup && localStores.isNotEmpty() -> SyncStatus.BACKUP_PENDING
                cloudStores.isNotEmpty() && localStores.containsAll(cloudStores) -> SyncStatus.SYNCED
                cloudStores.isNotEmpty() -> SyncStatus.RESTORE_AVAILABLE
                localStores.isNotEmpty() -> SyncStatus.BACKUP_PENDING
                else -> SyncStatus.EMPTY
            }
            val detail = when (status) {
                SyncStatus.EMPTY -> "No backed up store logins found yet."
                SyncStatus.BACKUP_PENDING -> "Waiting to sync ${localStores.joinToString()} logins to Google."
                SyncStatus.RESTORE_AVAILABLE -> "Cloud login restore is available for ${cloudStores.joinToString()}."
                SyncStatus.SYNCED -> "Store logins are synced with Google Play Games."
                SyncStatus.NOT_SIGNED_IN -> "Sign in to Google Play Games to sync store logins."
                SyncStatus.ERROR -> "Store login sync ran into a problem."
            }
            StoreLoginSyncState(
                googleSignedIn = true,
                localStores = localStores,
                cloudStores = cloudStores,
                lastSyncTime = remote.lastModifiedTime ?: prefs(activity).getLong(KEY_LAST_SYNC_TIME, 0L).takeIf { it > 0L },
                status = status,
                detail = detail
            )
        } catch (error: Exception) {
            val detail = rememberSyncError(activity, error)
            Timber.tag(TAG).e(error, "Failed to read store login sync state")
            StoreLoginSyncState(
                googleSignedIn = true,
                localStores = localStores,
                status = SyncStatus.ERROR,
                detail = detail
            )
        }
    }

    private fun collectLocalStoreNames(context: Context): Set<String> {
        return collectLocalPayload(context).stores.keys
    }

    private fun collectLocalPayload(context: Context): StorePayload {
        PrefManager.init(context)

        val stores = linkedMapOf<String, ByteArray>()

        exportSteam(context)?.let { stores[STORE_STEAM] = it }
        exportEpic(context)?.let { stores[STORE_EPIC] = it }
        exportGog(context)?.let { stores[STORE_GOG] = it }
        exportAmazon(context)?.let { stores[STORE_AMAZON] = it }

        val createdAt = System.currentTimeMillis()
        val fingerprint = computeFingerprint(stores)
        Timber.tag(TAG).i("Collected local store payload stores=%s fingerprint=%s", stores.keys, fingerprint.take(12))
        return StorePayload(createdAt = createdAt, stores = stores, fingerprint = fingerprint)
    }

    private fun exportSteam(context: Context): ByteArray? {
        if (!SteamService.hasStoredCredentials(context)) {
            return null
        }

        val json = JSONObject().apply {
            put("username", PrefManager.username)
            put("refreshToken", PrefManager.refreshToken)
            put("accessToken", PrefManager.accessToken)
            put("steamUserSteamId64", PrefManager.steamUserSteamId64)
            put("steamUserAccountId", PrefManager.steamUserAccountId)
            put("steamUserName", PrefManager.steamUserName)
            put("steamUserAvatarHash", PrefManager.steamUserAvatarHash)
            put("personaState", PrefManager.personaState)
            put("clientId", PrefManager.clientId)
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun exportEpic(context: Context): ByteArray? {
        if (!EpicAuthManager.hasStoredCredentials(context)) {
            return null
        }
        val file = File(context.filesDir, "epic/credentials.json")
        return if (file.exists()) file.readBytes() else null
    }

    private fun exportGog(context: Context): ByteArray? {
        if (!GOGAuthManager.hasStoredCredentials(context)) {
            return null
        }
        val file = File(GOGAuthManager.getAuthConfigPath(context))
        return if (file.exists()) file.readBytes() else null
    }

    private fun exportAmazon(context: Context): ByteArray? {
        for (relativePath in AMAZON_DISCOVERY_PATHS) {
            val file = File(context.filesDir, relativePath)
            if (file.exists()) {
                return file.readBytes()
            }
        }
        return null
    }

    private fun restoreMissingStores(context: Context, payload: StorePayload): Set<String> {
        val restored = linkedSetOf<String>()

        for ((store, bytes) in payload.stores) {
            when (store) {
                STORE_STEAM -> if (!SteamService.hasStoredCredentials(context) && restoreSteam(context, bytes)) {
                    restored += STORE_STEAM
                }
                STORE_EPIC -> if (!EpicAuthManager.hasStoredCredentials(context) && restoreEpic(context, bytes)) {
                    restored += STORE_EPIC
                }
                STORE_GOG -> if (!GOGAuthManager.hasStoredCredentials(context) && restoreGog(context, bytes)) {
                    restored += STORE_GOG
                }
                STORE_AMAZON -> if (restoreAmazon(context, bytes)) {
                    restored += STORE_AMAZON
                }
            }
        }

        return restored
    }

    private fun restoreSteam(context: Context, bytes: ByteArray): Boolean {
        return runCatching {
            Timber.tag(TAG).i("Restoring Steam login tokens from cloud payload")
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            PrefManager.init(context)
            PrefManager.username = json.optString("username", "")
            PrefManager.refreshToken = json.optString("refreshToken", "")
            PrefManager.accessToken = json.optString("accessToken", "")
            PrefManager.steamUserSteamId64 = json.optLong("steamUserSteamId64", 0L)
            PrefManager.steamUserAccountId = json.optInt("steamUserAccountId", 0)
            PrefManager.steamUserName = json.optString("steamUserName", "")
            PrefManager.steamUserAvatarHash = json.optString("steamUserAvatarHash", "")
            PrefManager.personaState = json.optInt("personaState", 0)
            PrefManager.clientId = json.optLong("clientId", 0L)
            SteamService.initLoginStatus(context)
            SteamService.start(context)
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore Steam login tokens")
            false
        }
    }

    private fun restoreEpic(context: Context, bytes: ByteArray): Boolean {
        return runCatching {
            Timber.tag(TAG).i("Restoring Epic login tokens from cloud payload")
            val file = File(context.filesDir, "epic/credentials.json")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            EpicAuthManager.updateLoginStatus(context)
            EpicService.start(context)
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore Epic login tokens")
            false
        }
    }

    private fun restoreGog(context: Context, bytes: ByteArray): Boolean {
        return runCatching {
            Timber.tag(TAG).i("Restoring GOG login tokens from cloud payload")
            val file = File(GOGAuthManager.getAuthConfigPath(context))
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            GOGAuthManager.updateLoginStatus(context)
            GOGService.start(context)
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore GOG login tokens")
            false
        }
    }

    private fun restoreAmazon(context: Context, bytes: ByteArray): Boolean {
        return runCatching {
            Timber.tag(TAG).i("Restoring Amazon login tokens from cloud payload")
            val file = File(context.filesDir, AMAZON_PRIMARY_RELATIVE_PATH)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Failed to restore Amazon login tokens")
            false
        }
    }

    private fun payloadToZip(payload: StorePayload): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val manifest = JSONObject().apply {
                put("version", 1)
                put("createdAt", payload.createdAt)
                put("fingerprint", payload.fingerprint)
                put("stores", JSONArray(payload.stores.keys.toList()))
            }
            writeZipEntry(zip, ZIP_MANIFEST, manifest.toString(2).toByteArray(StandardCharsets.UTF_8))

            payload.stores.forEach { (store, data) ->
                val entryName = when (store) {
                    STORE_STEAM -> ZIP_STEAM
                    STORE_EPIC -> ZIP_EPIC
                    STORE_GOG -> ZIP_GOG
                    STORE_AMAZON -> ZIP_AMAZON
                    else -> null
                }
                if (entryName != null) {
                    writeZipEntry(zip, entryName, data)
                }
            }
        }
        return output.toByteArray()
    }

    private fun zipToPayload(bytes: ByteArray): StorePayload? {
        val stores = linkedMapOf<String, ByteArray>()
        var createdAt = 0L
        var fingerprint: String? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryBytes = zip.readBytes()
                when (entry.name) {
                    ZIP_MANIFEST -> {
                        val manifest = JSONObject(String(entryBytes, StandardCharsets.UTF_8))
                        createdAt = manifest.optLong("createdAt", 0L)
                        fingerprint = manifest.optString("fingerprint").takeIf { it.isNotBlank() }
                    }
                    ZIP_STEAM -> stores[STORE_STEAM] = entryBytes
                    ZIP_EPIC -> stores[STORE_EPIC] = entryBytes
                    ZIP_GOG -> stores[STORE_GOG] = entryBytes
                    ZIP_AMAZON -> stores[STORE_AMAZON] = entryBytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (stores.isEmpty()) {
            return null
        }

        val resolvedFingerprint = fingerprint ?: computeFingerprint(stores)
        return StorePayload(
            createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis(),
            stores = stores,
            fingerprint = resolvedFingerprint
        )
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun computeFingerprint(stores: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        stores.toSortedMap().forEach { (store, data) ->
            digest.update(store.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(data)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isGoogleSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GOOGLE_SYNC_ENABLED, false)

    private fun clearSyncError(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_SYNC_ERROR)
            .putBoolean(KEY_SNAPSHOTS_BLOCKED, false)
            .apply()
    }

    private fun rememberSyncError(context: Context, error: Throwable): String {
        val detail = explainSyncError(error)
        Log.e(TAG, detail, error)
        prefs(context).edit()
            .putString(KEY_LAST_SYNC_ERROR, detail)
            .putBoolean(KEY_SNAPSHOTS_BLOCKED, isPermanentSnapshotConfigError(error))
            .apply()
        return detail
    }

    private fun snapshotsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SNAPSHOTS_BLOCKED, false)

    private fun explainSyncError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .mapNotNull { throwable ->
                val simpleName = throwable::class.java.simpleName.takeIf { it.isNotBlank() }
                val message = throwable.message?.takeIf { it.isNotBlank() }
                when {
                    simpleName != null && message != null -> "$simpleName: $message"
                    message != null -> message
                    else -> simpleName
                }
            }
            .toList()
        val rawMessage = chain.joinToString(" | ")
        val normalized = rawMessage.lowercase()
        return when {
            "sign_in_required" in normalized || "apiexception: 4" in normalized ->
                "Google Play Games is connected, but the refreshed Google account session still lacks Saved Games authorization. Sign in again and allow Saved Games access."
            "sign-in check failed" in normalized ||
                "cannot find the installed destination app" in normalized ||
                "games service" in normalized ->
                "Google account is connected, but Google Play Games Saved Games rejected this build. Check the Play Games app ID, linked package, and signing certificate."
            "network" in normalized || "timeout" in normalized ->
                "Google cloud sync failed because the network request did not complete."
            rawMessage.isNotBlank() ->
                "Google cloud sync failed: $rawMessage"
            else ->
                "Google cloud sync failed before Google Saved Games could open."
        }
    }

    private fun isPermanentSnapshotConfigError(error: Throwable): Boolean {
        val rawMessage = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return "saved game" in rawMessage && "play console" in rawMessage
    }

    private suspend fun isAuthenticatedBlocking(activity: Activity): Boolean {
        return try {
            val result = Tasks.await(PlayGames.getGamesSignInClient(activity).isAuthenticated)
            val authenticated = result?.isAuthenticated == true
            Timber.tag(TAG).i("Blocking Google auth check result=%s", authenticated)
            authenticated
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to read Google authentication state")
            false
        }
    }
}
