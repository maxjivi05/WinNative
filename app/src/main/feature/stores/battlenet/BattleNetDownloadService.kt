package com.winlator.cmod.feature.stores.battlenet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.winlator.cmod.R
import com.winlator.cmod.app.shell.UnifiedActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class BattleNetDownloadState(
    val product: String = "", val stage: String = "", val current: Long = 0,
    val total: Long = 0, val paused: Boolean = false, val done: Boolean = true,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) (current.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
    fun label(context: Context): String = context.getString(when {
        paused -> R.string.downloads_queue_phase_paused
        stage == "downloading" -> R.string.downloads_queue_phase_downloading
        stage == "installing" -> R.string.downloads_queue_installing_package
        stage == "verifying" -> R.string.downloads_queue_phase_verifying
        stage == "complete" -> R.string.downloads_queue_phase_complete
        stage == "cancelled" -> R.string.downloads_queue_phase_cancelled
        stage == "failed" -> R.string.battlenet_failed
        else -> R.string.downloads_queue_preparing_download
    })
}

internal object BattleNetDownloads {
    private const val PREFS = "battlenet_native_downloads"
    private val mutable = MutableStateFlow(BattleNetDownloadState())
    val state = mutable.asStateFlow()
    @Volatile var activeId = 0L
        private set
    private var pending = false
    private var restored = false
    @Synchronized fun isBusy(): Boolean = activeId != 0L || pending
    fun supported(product: String) = product in setOf("wow", "wow_classic", "wow_classic_era")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val request = p.getString("request", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val value = p.getString("status", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        synchronized(this@BattleNetDownloads) {
            if (!restored && activeId == 0L && !pending) {
                restored = true
                if (request != null && value != null) {
                    if (!value.optBoolean("done", true)) value.put("paused", true).put("stage", "interrupted")
                    publish(request, value)
                }
            }
        }
    }
    suspend fun preview(context: Context, product: String): JSONObject = withContext(Dispatchers.IO) {
        val response = JSONObject(BattleNetNative.installPreview(product, BattleNetSession.region(context)))
        if (response.has("error")) throw IOException(context.getString(R.string.battlenet_native_unavailable))
        response
    }
    private fun directory(context: Context, product: String, cache: Boolean, buildKey: String = ""): File {
        require(supported(product))
        val root = BattleNetRuntime.shared(context).root
        if (!cache) require(buildKey.matches(Regex("[a-f0-9]{32}")))
        val file = if (cache) File(root, "native-cache/$product")
            else File(gameRoot(context), "${BattleNetCatalog.byProduct(product)!!.title}-$buildKey")
        BattleNetSharedFiles.requireUnlinkedPath(file)
        if (!file.isDirectory && !file.mkdirs() && !file.isDirectory) throw IOException(context.getString(R.string.battlenet_failed))
        return file
    }
    fun gameRoot(context: Context): File {
        val pref = com.winlator.cmod.feature.stores.steam.utils.PrefManager
        val uri = if (pref.useSingleDownloadFolder) pref.defaultDownloadFolder else pref.battleNetDownloadFolder
        val selected = if (uri.isNotEmpty()) {
            com.winlator.cmod.shared.io.FileUtils.getFilePathFromUri(context, android.net.Uri.parse(uri))
                ?: throw IOException(context.getString(R.string.battlenet_failed))
        } else null
        val fallback = if (pref.useExternalStorage && File(pref.externalStoragePath).isDirectory)
            File(pref.externalStoragePath, "Battle.net/games") else File(context.dataDir, "Battle.net/games")
        return (selected?.let { File(it) } ?: fallback).canonicalFile
    }

    suspend fun prepareSharedInstall(context: Context, product: String): Boolean = withContext(Dispatchers.IO) {
        val saved = prefs(context).getString("installed_$product", null) ?: return@withContext true
        val request = JSONObject(saved)
        val target = File(request.getString("target"))
        val legacy = File(BattleNetRuntime.shared(context).root, "games")
        if (!target.toPath().startsWith(legacy.toPath())) return@withContext true
        val destination = directory(context, product, false, request.getString("buildKey"))
        dispatch(context, request.put("target", destination.absolutePath).put("action", "download"))
        false
    }

    suspend fun start(context: Context, product: String, preview: JSONObject) = withContext(Dispatchers.IO) {
        val buildKey = preview.getString("buildKey")
        val request = JSONObject().put("product", product).put("region", BattleNetSession.region(context))
            .put("cache", directory(context, product, true).absolutePath)
            .put("target", directory(context, product, false, buildKey).absolutePath)
            .put("buildKey", buildKey).put("action", "download")
        dispatch(context, request)
    }
    suspend fun verify(context: Context, product: String) = withContext(Dispatchers.IO) {
        val saved = prefs(context).getString("installed_$product", null) ?: throw IOException(context.getString(R.string.battlenet_failed))
        dispatch(context, JSONObject(saved).put("action", "verify"))
    }
    suspend fun installed(context: Context, product: String): Boolean = withContext(Dispatchers.IO) {
        prefs(context).contains("installed_$product")
    }
    suspend fun installPath(context: Context, product: String): String = withContext(Dispatchers.IO) {
        prefs(context).getString("installed_$product", null)?.let { JSONObject(it).getString("target") }.orEmpty()
    }
    suspend fun hasUpdate(context: Context, product: String): Boolean = withContext(Dispatchers.IO) {
        val saved = prefs(context).getString("installed_$product", null) ?: throw IOException(context.getString(R.string.battlenet_failed))
        val installed = JSONObject(saved)
        val latest = JSONObject(BattleNetNative.latestBuild(product, installed.getString("region")))
            .optJSONObject("build") ?: throw IOException(context.getString(R.string.battlenet_failed))
        installed.getString("buildKey") != latest.getString("buildKey")
    }
    suspend fun resume(context: Context) = withContext(Dispatchers.IO) {
        val id = activeId
        if (id != 0L) { BattleNetNative.commandJob(id, "resume"); return@withContext }
        val saved = prefs(context).getString("request", null) ?: return@withContext
        dispatch(context, JSONObject(saved))
    }
    fun command(command: String) { val id = activeId; if (id != 0L) BattleNetNative.commandJob(id, command) }
    suspend fun cancel(context: Context) = withContext(Dispatchers.IO) {
        val id = activeId
        if (id != 0L) { BattleNetNative.commandJob(id, "cancel"); return@withContext }
        val saved = prefs(context).getString("request", null) ?: return@withContext
        val request = JSONObject(saved)
        val status = JSONObject().put("stage", "cancelled").put("done", true).put("paused", false)
        val claimed = synchronized(this@BattleNetDownloads) {
            if (activeId == 0L && !pending) { pending = true; true } else false
        }
        if (claimed) {
            try { persist(context, request, status); publish(request, status) }
            finally { synchronized(this@BattleNetDownloads) { pending = false } }
        }
    }

    private fun dispatch(context: Context, request: JSONObject) {
        synchronized(this) {
            if (activeId != 0L || pending) throw IOException(context.getString(R.string.battlenet_download_busy))
            pending = true
        }
        try {
            val status = JSONObject().put("stage", "preparing").put("done", false)
            if (!prefs(context).edit().putString("request", request.toString()).putString("status", status.toString()).commit()) throw IOException()
            publish(request, status)
            ContextCompat.startForegroundService(context, Intent(context, BattleNetDownloadService::class.java).putExtra("request", request.toString()))
        } catch (failure: Exception) {
            synchronized(this) { pending = false }
            mutable.value = mutable.value.copy(stage = "failed", done = true)
            throw IOException(context.getString(R.string.battlenet_failed), failure)
        }
    }
    @Synchronized fun claim(id: Long): Boolean {
        if (activeId != 0L) return false
        activeId = id; pending = false; restored = true
        return true
    }
    @Synchronized fun finished(id: Long) { if (activeId == id) activeId = 0 }
    fun publish(request: JSONObject, status: JSONObject) {
        mutable.value = BattleNetDownloadState(request.optString("product"), status.optString("stage"), status.optLong("current"), status.optLong("total"), status.optBoolean("paused"), status.optBoolean("done"), status.optString("error").takeIf { it.isNotEmpty() })
    }
    fun persist(context: Context, request: JSONObject, status: JSONObject): Boolean {
        val edit = prefs(context).edit().putString("status", status.toString())
        if (status.optString("stage") == "complete" && request.optString("action") == "download") edit.putString("installed_${request.getString("product")}", request.toString())
        return edit.commit()
    }
}

class BattleNetDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var id = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.battlenet_downloads), NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(state: BattleNetDownloadState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(BattleNetCatalog.byProduct(state.product)?.title ?: getString(R.string.battlenet_downloads))
        .setContentText(state.label(this)).setOnlyAlertOnce(true).setOngoing(!state.done)
        .setProgress(1000, (state.fraction * 1000).toInt(), state.total == 0L)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, UnifiedActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (id != 0L) return START_NOT_STICKY
        startForeground(NOTIFICATION, notification(BattleNetDownloads.state.value))
        val request = runCatching { JSONObject(intent?.getStringExtra("request").orEmpty()) }.getOrNull()
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        val handle = BattleNetNative.createJob()
        if (handle == 0L || !BattleNetDownloads.claim(handle)) { if (handle != 0L) BattleNetNative.releaseJob(handle); stopSelf(startId); return START_NOT_STICKY }
        id = handle
        scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WinNative:BattleNetDownload")
            var result = JSONObject().put("stage", "failed").put("done", true)
            val polling = launch {
                while (isActive) {
                    val status = JSONObject(BattleNetNative.jobStatus(handle))
                    BattleNetDownloads.publish(request, status)
                    BattleNetDownloads.persist(applicationContext, request, status)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(BattleNetDownloads.state.value))
                    delay(1000)
                }
            }
            try {
                wake.acquire(12 * 60 * 60 * 1000L)
                result = JSONObject(BattleNetNative.runJob(handle, request.toString()))
            } catch (cancelled: CancellationException) {
                BattleNetNative.commandJob(handle, "cancel")
                throw cancelled
            } catch (_: Exception) {
                result = JSONObject().put("stage", "failed").put("done", true)
            } finally {
                withContext(NonCancellable) {
                    polling.cancelAndJoin()
                    if (!BattleNetDownloads.persist(applicationContext, request, result)) result = JSONObject().put("stage", "failed").put("done", true).put("error", "state_save_failed")
                    BattleNetDownloads.publish(request, result)
                    BattleNetNative.releaseJob(handle)
                    BattleNetDownloads.finished(handle)
                    if (wake.isHeld) wake.release()
                    withContext(Dispatchers.Main) { id = 0; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (id != 0L) BattleNetNative.commandJob(id, "cancel")
        scope.cancel()
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "battlenet_native_download"; private const val NOTIFICATION = 48017 }
}
