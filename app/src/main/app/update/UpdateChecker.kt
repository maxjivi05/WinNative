package com.winlator.cmod.app.update
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Update checker driven by the GitHub Releases stream of WinNative-Emu/WinNative.
 *
 * Behaviour:
 *  - Nightly builds (gradle default `VERSION_NAME=Nightly`, exposed as
 *    `BuildConfig.BUILD_IS_STABLE=false`) never check for updates. Nightly users
 *    update through a separate channel.
 *  - Stable builds check on app open (gated by [CHECK_INTERVAL_MS]) and then
 *    hourly while the user is not inside a game session.
 *  - When a newer version is published on GitHub Releases the user is prompted
 *    with a dialog showing the release notes; tapping "Update" downloads the
 *    matching APK for the current product flavor via [DownloadManager],
 *    verifies its signing certificate matches the installed app, and launches
 *    the system installer through a FileProvider URI. The cached APK is
 *    cleaned up on the next launch after a successful install.
 */
object UpdateChecker {
    private const val GITHUB_OWNER = "WinNative-Emu"
    private const val GITHUB_REPO = "WinNative"
    private const val RELEASES_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=20"

    private const val PREF_CHECK_FOR_UPDATES = "check_for_updates"
    private const val PREF_LAST_UPDATE_CHECK = "last_update_check_time"
    private const val PREF_SKIPPED_VERSION = "update_skipped_version"
    private const val PREF_PENDING_DOWNLOAD_ID = "update_pending_download_id"
    private const val PREF_PENDING_VERSION = "update_pending_version"
    private const val PREF_PENDING_FILE = "update_pending_file"
    private const val PREF_PENDING_INSTALL_FILE = "update_pending_install_file"

    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    private const val MANUAL_CHECK_COOLDOWN_MS = 30 * 1000L // 30 seconds
    private const val POST_GAME_CHECK_DELAY_MS = 10 * 1000L // 10 seconds

    private val lastManualCheckTime = AtomicLong(0L)
    private val isChecking = AtomicBoolean(false)
    private val isDownloading = AtomicBoolean(false)

    private var backgroundHandler: Handler? = null
    private var backgroundRunnable: Runnable? = null

    private var postGameHandler: Handler? = null
    private var postGameRunnable: Runnable? = null

    private var downloadReceiver: BroadcastReceiver? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    // ── Public API ────────────────────────────────────────────────────

    /** True for builds produced from a tagged release (not "Nightly"). */
    fun isStableBuild(): Boolean = BuildConfig.BUILD_IS_STABLE

    /** True if updates are stable-build *and* user opted in via settings. */
    fun isEnabled(context: Context): Boolean {
        if (!isStableBuild()) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_CHECK_FOR_UPDATES, false)
    }

    /**
     * Retained for source compatibility with the previous implementation.
     * No-op now that update detection is version-based, but harmless to call.
     */
    @Suppress("UNUSED_PARAMETER")
    fun refreshInstallTimestamp(context: Context) {
        // No longer used — version comparison replaces install-timestamp comparison.
    }

    fun isDueForCheck(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS
    }

    fun startBackgroundLoop(context: Context) {
        stopBackgroundLoop()
        if (!isEnabled(context)) return
        if (!isAutoCheckAllowed()) return

        val appContext = context.applicationContext
        backgroundHandler = mainHandler
        backgroundRunnable =
            object : Runnable {
                override fun run() {
                    if (isEnabled(appContext) && isAutoCheckAllowed()) {
                        checkForUpdate(appContext, force = false)
                        backgroundHandler?.postDelayed(this, CHECK_INTERVAL_MS)
                    }
                }
            }
        backgroundHandler?.postDelayed(backgroundRunnable!!, 5_000L)
    }

    fun stopBackgroundLoop() {
        backgroundRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        backgroundHandler = null
        backgroundRunnable = null
    }

    fun checkForUpdate(
        context: Context,
        force: Boolean = false,
    ) {
        if (!isEnabled(context)) return
        if (!isAutoCheckAllowed()) return
        if (!force && !isDueForCheck(context)) return
        launchCheck(context, manual = false)
    }

    fun checkForUpdateManual(context: Context): Boolean {
        if (!isStableBuild()) return false
        val now = System.currentTimeMillis()
        val last = lastManualCheckTime.get()
        if (now - last < MANUAL_CHECK_COOLDOWN_MS) return false
        lastManualCheckTime.set(now)
        launchCheck(context, manual = true)
        return true
    }

    fun manualCheckCooldownSeconds(): Int {
        val elapsed = System.currentTimeMillis() - lastManualCheckTime.get()
        val remaining = MANUAL_CHECK_COOLDOWN_MS - elapsed
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    fun schedulePostGameCheck(context: Context) {
        cancelPostGameCheck()
        if (!isEnabled(context)) return
        if (!isAutoCheckAllowed()) return
        val appContext = context.applicationContext
        postGameHandler = mainHandler
        postGameRunnable =
            Runnable {
                if (isAutoCheckAllowed()) {
                    checkForUpdate(appContext, force = true)
                }
            }
        postGameHandler?.postDelayed(postGameRunnable!!, POST_GAME_CHECK_DELAY_MS)
    }

    fun cancelPostGameCheck() {
        postGameRunnable?.let { postGameHandler?.removeCallbacks(it) }
        postGameHandler = null
        postGameRunnable = null
    }

    fun resetCheckTimer(context: Context) {
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_LAST_UPDATE_CHECK, 0L)
            .apply()
    }

    /**
     * Resumes a download that was running when the app was last killed, and/or
     * launches the installer for a previously-completed download that we
     * couldn't hand off to the system (process death, app backgrounded, etc.).
     * Safe to call multiple times; idempotent.
     */
    fun resumePendingInstall(context: Context) {
        if (!isStableBuild()) return
        val appContext = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

        // 1) Foreground-deferred install: download already complete, we just
        // need to launch the system installer while we have an Activity.
        prefs.getString(PREF_PENDING_INSTALL_FILE, null)?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0L) {
                if (PluviaApp.currentForegroundActivity != null && launchInstaller(appContext, file)) {
                    prefs.edit().remove(PREF_PENDING_INSTALL_FILE).apply()
                    return
                }
            } else {
                prefs.edit().remove(PREF_PENDING_INSTALL_FILE).apply()
            }
        }

        // 2) Process-death-during-download: query DownloadManager for status.
        val pendingId = prefs.getLong(PREF_PENDING_DOWNLOAD_ID, -1L)
        if (pendingId < 0L) return
        val pendingPath = prefs.getString(PREF_PENDING_FILE, null) ?: run {
            clearPendingDownloadPrefs(appContext)
            return
        }
        val pendingFile = File(pendingPath)
        try {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(pendingId))
            val status =
                cursor.use { c ->
                    if (!c.moveToFirst()) -1
                    else c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        .takeIf { it >= 0 }
                        ?.let { c.getInt(it) } ?: -1
                }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    handleDownloadComplete(appContext, pendingId, "", pendingFile)
                }
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED,
                -> {
                    // Still in flight — re-attach a completion receiver so we
                    // catch the broadcast in this process.
                    registerCompletionReceiver(appContext, pendingId, "", pendingFile)
                }
                DownloadManager.STATUS_FAILED, -1 -> {
                    if (pendingFile.exists()) pendingFile.delete()
                    runCatching { dm.remove(pendingId) }
                    clearPendingDownloadPrefs(appContext)
                }
                else -> {
                    clearPendingDownloadPrefs(appContext)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "resumePendingInstall failed")
            clearPendingDownloadPrefs(appContext)
        }
    }

    /**
     * Removes stale APK files from the updates directory. Called on app start
     * so a successfully installed update doesn't leave its installer behind.
     */
    fun cleanupOldDownloads(context: Context) {
        try {
            val dir = updatesDir(context) ?: return
            if (!dir.exists()) return
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            // Files referenced by an outstanding pending-install / pending-download
            // record are kept; resumePendingInstall owns those.
            val protectedPaths =
                setOfNotNull(
                    prefs.getString(PREF_PENDING_INSTALL_FILE, null),
                    prefs.getString(PREF_PENDING_FILE, null),
                )
            val currentVersion = parseVersion(BuildConfig.VERSION_NAME)
            val maxAgeMs = 24L * 60 * 60 * 1000 // 24h
            dir.listFiles()?.forEach { file ->
                if (protectedPaths.contains(file.absolutePath)) return@forEach
                val fileVersion = extractVersionFromFilename(file.name)
                val sameOrOlderThanInstalled =
                    currentVersion != null &&
                        fileVersion != null &&
                        compareVersion(fileVersion, currentVersion) <= 0
                val tooOld = System.currentTimeMillis() - file.lastModified() > maxAgeMs
                if (sameOrOlderThanInstalled || tooOld) {
                    if (file.delete()) {
                        Timber.d("Deleted stale update file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Cleanup of old update downloads failed")
        }
    }

    // ── Internal: gating ─────────────────────────────────────────────

    private fun isAutoCheckAllowed(): Boolean {
        if (!isStableBuild()) return false
        val activity = PluviaApp.currentForegroundActivity ?: return false
        return activity !is XServerDisplayActivity
    }

    // ── Internal: check flow ─────────────────────────────────────────

    private fun launchCheck(
        context: Context,
        manual: Boolean,
    ) {
        if (!isChecking.compareAndSet(false, true)) return
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val release = fetchLatestRelease()
                PreferenceManager
                    .getDefaultSharedPreferences(appContext)
                    .edit()
                    .putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
                    .apply()

                if (release == null) {
                    Timber.d("Update check: no release info returned")
                    return@launch
                }

                val current = parseVersion(BuildConfig.VERSION_NAME)
                if (current == null) {
                    Timber.w("Update check: could not parse current version '%s'", BuildConfig.VERSION_NAME)
                    return@launch
                }

                if (compareVersion(release.version, current) <= 0) {
                    Timber.d("Update check: installed (%s) is current or newer than %s", current, release.version)
                    return@launch
                }

                val asset = pickAssetForPackage(appContext, release) ?: run {
                    Timber.w("Update check: no matching asset for package %s", appContext.packageName)
                    return@launch
                }

                if (!manual && isVersionSkipped(appContext, release.tagName)) {
                    Timber.d("Update check: version %s skipped by user", release.tagName)
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    showUpdateDialog(appContext, release, asset)
                }
            } catch (e: Exception) {
                Timber.e(e, "Update check failed")
            } finally {
                isChecking.set(false)
            }
        }
    }

    private data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    )

    private data class ReleaseInfo(
        val tagName: String,
        val version: SemVer,
        val publishedAt: Date?,
        val body: String?,
        val assets: List<ReleaseAsset>,
    )

    private fun fetchLatestRelease(): ReleaseInfo? {
        val ua = "WinNative-Updater/${BuildConfig.VERSION_NAME} (Android)"
        val request =
            Request
                .Builder()
                .url(RELEASES_URL)
                .header("User-Agent", ua)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

        val body =
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("Releases request failed: %d", resp.code)
                    return null
                }
                resp.body?.string() ?: return null
            }

        val arr =
            try {
                JSONArray(body)
            } catch (e: Exception) {
                Timber.w(e, "Releases response not a JSON array")
                return null
            }

        var best: ReleaseInfo? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            if (obj.optBoolean("prerelease", false)) continue
            val tag = obj.optString("tag_name").orEmpty().takeIf { it.isNotBlank() } ?: continue
            val parsed = parseVersion(tag) ?: continue
            if (best == null || compareVersion(parsed, best!!.version) > 0) {
                best = ReleaseInfo(
                    tagName = tag,
                    version = parsed,
                    publishedAt = parseIsoDate(obj.optString("published_at")),
                    body = obj.optString("body").takeIf { it.isNotBlank() },
                    assets = parseAssets(obj.optJSONArray("assets")),
                )
            }
        }
        return best
    }

    private fun parseAssets(arr: JSONArray?): List<ReleaseAsset> {
        if (arr == null) return emptyList()
        val out = ArrayList<ReleaseAsset>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").orEmpty()
            val url = obj.optString("browser_download_url").orEmpty()
            val size = obj.optLong("size", 0L)
            if (name.isBlank() || url.isBlank()) continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            out += ReleaseAsset(name, url, size)
        }
        return out
    }

    private fun pickAssetForPackage(
        context: Context,
        release: ReleaseInfo,
    ): ReleaseAsset? {
        // Map the installed product flavor (identified by applicationId) to the
        // matching asset suffix published on GitHub Releases. The asset filenames
        // follow `WinNative-<tag>-<Flavor>.apk`.
        val suffix =
            when (context.packageName) {
                "com.winnative.cmod" -> "-Standard.apk"
                "com.ludashi.benchmark" -> "-Ludashi.apk"
                "com.tencent.ig" -> "-Pubg.apk"
                else -> "-Standard.apk"
            }
        return release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
    }

    // ── Internal: SemVer ─────────────────────────────────────────────

    /**
     * Subset of SemVer 2.0 sufficient for our tag scheme.
     * Build metadata after `+` is ignored. Pre-release identifiers are
     * dot-separated; numeric identifiers compare numerically, mixed compare per
     * spec rules.
     */
    internal data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<String>,
    ) {
        override fun toString(): String {
            val core = "$major.$minor.$patch"
            return if (preRelease.isEmpty()) core else "$core-${preRelease.joinToString(".")}"
        }
    }

    internal fun parseVersion(raw: String?): SemVer? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
        // strip build metadata
        val plus = s.indexOf('+')
        if (plus >= 0) s = s.substring(0, plus)
        val dash = s.indexOf('-')
        val core = if (dash >= 0) s.substring(0, dash) else s
        val pre = if (dash >= 0) s.substring(dash + 1) else ""
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val preIdents =
            if (pre.isEmpty()) emptyList() else pre.split('.').filter { it.isNotEmpty() }
        return SemVer(major, minor, patch, preIdents)
    }

    internal fun compareVersion(
        a: SemVer,
        b: SemVer,
    ): Int {
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        if (a.patch != b.patch) return a.patch.compareTo(b.patch)
        return comparePreRelease(a.preRelease, b.preRelease)
    }

    private fun comparePreRelease(
        a: List<String>,
        b: List<String>,
    ): Int {
        // No pre-release outranks any pre-release.
        if (a.isEmpty() && b.isEmpty()) return 0
        if (a.isEmpty()) return 1
        if (b.isEmpty()) return -1
        val limit = minOf(a.size, b.size)
        for (i in 0 until limit) {
            val cmp = comparePreIdent(a[i], b[i])
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    private fun comparePreIdent(
        a: String,
        b: String,
    ): Int {
        val aNum = a.toIntOrNull()
        val bNum = b.toIntOrNull()
        return when {
            aNum != null && bNum != null -> aNum.compareTo(bNum)
            aNum != null -> -1
            bNum != null -> 1
            else -> a.compareTo(b)
        }
    }

    private fun extractVersionFromFilename(name: String): SemVer? {
        // Filenames look like WinNative-v0.1.0-beta-Standard.apk
        val regex = Regex("""WinNative-(v[0-9].+?)-(Standard|Ludashi|Pubg)\.apk""", RegexOption.IGNORE_CASE)
        val m = regex.find(name) ?: return null
        return parseVersion(m.groupValues[1])
    }

    // ── Internal: skip-version persistence ───────────────────────────

    private fun isVersionSkipped(
        context: Context,
        tagName: String,
    ): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_SKIPPED_VERSION, null) == tagName
    }

    private fun markVersionSkipped(
        context: Context,
        tagName: String,
    ) {
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_SKIPPED_VERSION, tagName)
            .apply()
    }

    // ── Internal: dialog ─────────────────────────────────────────────

    private fun showUpdateDialog(
        appContext: Context,
        release: ReleaseInfo,
        asset: ReleaseAsset,
    ) {
        val activity = PluviaApp.currentForegroundActivity ?: return
        if (activity.isFinishing) return
        if (activity is XServerDisplayActivity) return

        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val smallPad = (8 * activity.resources.displayMetrics.density).toInt()

        val container =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }

        val header =
            TextView(activity).apply {
                text = "${BuildConfig.VERSION_NAME}  \u2192  ${release.tagName}"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        container.addView(header)

        val meta = StringBuilder()
        release.publishedAt?.let {
            val fmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            meta.append(activity.getString(R.string.update_dialog_released_at, fmt.format(it)))
        }
        if (asset.sizeBytes > 0) {
            if (meta.isNotEmpty()) meta.append("  •  ")
            meta.append(humanReadableSize(asset.sizeBytes))
        }
        if (meta.isNotEmpty()) {
            container.addView(
                TextView(activity).apply {
                    text = meta.toString()
                    setTextColor(0xFFB0B0B0.toInt())
                    textSize = 13f
                    setPadding(0, smallPad / 2, 0, 0)
                },
            )
        }

        if (!release.body.isNullOrBlank()) {
            container.addView(
                android.view.View(activity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * activity.resources.displayMetrics.density).toInt(),
                        ).apply {
                            topMargin = padding
                            bottomMargin = smallPad
                        }
                    setBackgroundColor(0xFF444444.toInt())
                },
            )
            container.addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.update_dialog_release_notes)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, smallPad)
                },
            )
            val notesBody =
                TextView(activity).apply {
                    text = lightMarkdown(release.body)
                    setTextColor(0xFFCCCCCC.toInt())
                    textSize = 13f
                    movementMethod = ScrollingMovementMethod.getInstance()
                    isVerticalScrollBarEnabled = true
                }
            container.addView(
                ScrollView(activity).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    addView(notesBody)
                },
            )
        }

        AlertDialog
            .Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.update_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.update_dialog_update_button) { _, _ ->
                onUpdateConfirmed(activity, release, asset)
            }
            .setNeutralButton(R.string.update_dialog_skip_button) { _, _ ->
                markVersionSkipped(appContext, release.tagName)
            }
            .setNegativeButton(R.string.update_dialog_later_button, null)
            .show()
    }

    // ── Internal: download + install ─────────────────────────────────

    private fun onUpdateConfirmed(
        activity: android.app.Activity,
        release: ReleaseInfo,
        asset: ReleaseAsset,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                requestUnknownSourcesPermission(activity)
                return
            }
        }
        if (!isDownloading.compareAndSet(false, true)) {
            Timber.d("Update download already in progress, ignoring re-tap")
            return
        }
        startDownload(activity.applicationContext, release, asset)
    }

    private fun requestUnknownSourcesPermission(activity: android.app.Activity) {
        AlertDialog
            .Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open_settings) { _, _ ->
                val intent =
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${activity.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { activity.startActivity(intent) }
                    .onFailure { Timber.w(it, "Could not open unknown-sources settings") }
            }
            .setNegativeButton(R.string.update_permission_cancel, null)
            .show()
    }

    private fun startDownload(
        appContext: Context,
        release: ReleaseInfo,
        asset: ReleaseAsset,
    ) {
        val dir = updatesDir(appContext)
        if (dir == null) {
            Timber.e("Could not access updates directory")
            isDownloading.set(false)
            return
        }
        try {
            dir.mkdirs()
            // Free space sanity: bail if obviously insufficient.
            if (asset.sizeBytes > 0 && dir.usableSpace > 0 && dir.usableSpace < asset.sizeBytes + 64L * 1024 * 1024) {
                postToast(appContext, appContext.getString(R.string.update_toast_no_space))
                isDownloading.set(false)
                return
            }
            // Drop any partial leftovers for this filename so DownloadManager
            // doesn't refuse the destination.
            val target = File(dir, asset.name)
            if (target.exists()) target.delete()

            val request =
                DownloadManager
                    .Request(Uri.parse(asset.downloadUrl))
                    .setTitle("WinNative ${release.tagName}")
                    .setDescription("Downloading update")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(Uri.fromFile(target))
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)

            val dm =
                appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            PreferenceManager
                .getDefaultSharedPreferences(appContext)
                .edit()
                .putLong(PREF_PENDING_DOWNLOAD_ID, id)
                .putString(PREF_PENDING_VERSION, release.tagName)
                .putString(PREF_PENDING_FILE, target.absolutePath)
                .apply()

            registerCompletionReceiver(appContext, id, release.tagName, target)
            postToast(
                appContext,
                appContext.getString(R.string.update_toast_downloading, release.tagName),
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue update download")
            isDownloading.set(false)
            postToast(appContext, appContext.getString(R.string.update_toast_start_failed))
        }
    }

    private fun registerCompletionReceiver(
        appContext: Context,
        id: Long,
        tagName: String,
        target: File,
    ) {
        // Replace any prior receiver.
        unregisterDownloadReceiver(appContext)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    val completedId =
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (completedId != id) return
                    unregisterDownloadReceiver(c.applicationContext)
                    handleDownloadComplete(c.applicationContext, id, tagName, target)
                }
            }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // ACTION_DOWNLOAD_COMPLETE is a system protected broadcast; on Android
        // 13+ runtime receivers for system broadcasts must declare exported.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            // System broadcast routed to this app's UID; no need to expose it.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        downloadReceiver = receiver
    }

    private fun unregisterDownloadReceiver(appContext: Context) {
        val r = downloadReceiver ?: return
        runCatching { appContext.unregisterReceiver(r) }
        downloadReceiver = null
    }

    private fun handleDownloadComplete(
        appContext: Context,
        id: Long,
        tagName: String,
        target: File,
    ) {
        isDownloading.set(false)
        clearPendingDownloadPrefs(appContext)
        try {
            val dm =
                appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            dm.query(query).use { cursor ->
                if (!cursor.moveToFirst()) {
                    Timber.w("Download row missing for id %d", id)
                    return
                }
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Timber.w("Download finished with status %d", status)
                    postToast(appContext, appContext.getString(R.string.update_toast_download_failed))
                    target.delete()
                    return
                }
            }

            if (!target.exists() || target.length() <= 0L) {
                postToast(appContext, appContext.getString(R.string.update_toast_download_incomplete))
                return
            }
            if (!verifyApkSigner(appContext, target)) {
                Timber.e("Signer verification failed for %s", target.name)
                postToast(appContext, appContext.getString(R.string.update_toast_signature_mismatch))
                target.delete()
                return
            }
            if (!launchInstaller(appContext, target)) {
                // App was backgrounded when the download finished; remember
                // to launch the installer on the next foreground activity.
                PreferenceManager
                    .getDefaultSharedPreferences(appContext)
                    .edit()
                    .putString(PREF_PENDING_INSTALL_FILE, target.absolutePath)
                    .apply()
                postToast(appContext, appContext.getString(R.string.update_toast_install_ready))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to finish update download")
        }
    }

    /**
     * Verifies the downloaded APK is signed by the same certificate as the
     * currently installed app. Protects against CDN compromise / wrong-flavor
     * downloads. Returns true when signers match.
     */
    private fun verifyApkSigner(
        appContext: Context,
        apk: File,
    ): Boolean {
        return try {
            val pm = appContext.packageManager
            val installedSigners = currentSignerDigests(appContext) ?: return false
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
            val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
            val apkSigners = signerDigestsFromPackageInfo(info)
            apkSigners.isNotEmpty() && apkSigners.any { installedSigners.contains(it) }
        } catch (e: Exception) {
            Timber.w(e, "Signer verification threw")
            false
        }
    }

    private fun currentSignerDigests(appContext: Context): Set<String>? {
        return try {
            val pm = appContext.packageManager
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
                }
            signerDigestsFromPackageInfo(info)
        } catch (e: Exception) {
            Timber.w(e, "Could not load installed signers")
            null
        }
    }

    private fun signerDigestsFromPackageInfo(info: android.content.pm.PackageInfo): Set<String> {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val sigs: Array<android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sigInfo = info.signingInfo
                when {
                    sigInfo == null -> null
                    sigInfo.hasMultipleSigners() -> sigInfo.apkContentsSigners
                    else -> sigInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        if (sigs.isNullOrEmpty()) return emptySet()
        val out = HashSet<String>(sigs.size)
        for (s in sigs) {
            md.reset()
            out += md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        return out
    }

    /**
     * Hands the downloaded APK off to the system installer. Returns true when
     * we successfully started the install activity. If no Activity is in the
     * foreground we return false so the caller can defer (Android 10+ blocks
     * background activity launches; an installer popped from an offscreen
     * process is silently dropped on many OEM builds).
     */
    private fun launchInstaller(
        appContext: Context,
        apk: File,
    ): Boolean {
        return try {
            val authority = "${BuildConfig.APPLICATION_ID}.tileprovider"
            val uri = FileProvider.getUriForFile(appContext, authority, apk)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val activity = PluviaApp.currentForegroundActivity
            if (activity != null && !activity.isFinishing) {
                activity.startActivity(intent)
                true
            } else {
                // No foreground window — defer; resumePendingInstall handles it.
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch installer")
            postToast(appContext, appContext.getString(R.string.update_toast_install_open_failed))
            false
        }
    }

    private fun clearPendingDownloadPrefs(appContext: Context) {
        PreferenceManager
            .getDefaultSharedPreferences(appContext)
            .edit()
            .remove(PREF_PENDING_DOWNLOAD_ID)
            .remove(PREF_PENDING_VERSION)
            .remove(PREF_PENDING_FILE)
            .apply()
    }

    // ── Internal: helpers ────────────────────────────────────────────

    private fun updatesDir(appContext: Context): File? {
        // External app-private storage survives storage-pressure eviction better
        // than the cache directory and stays sandboxed to this app.
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir ?: return null
        return File(base, "updates")
    }

    private fun parseIsoDate(s: String?): Date? {
        if (s.isNullOrBlank()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return runCatching { fmt.parse(s) }.getOrNull()
    }

    private fun humanReadableSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.size - 1) {
            value /= 1024
            idx++
        }
        return if (idx == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[idx])
    }

    /**
     * Strips a small subset of Markdown so GitHub release bodies render
     * readably in our plain TextView dialog. Not a full renderer.
     */
    private fun lightMarkdown(input: String): String {
        var s = input.replace("\r\n", "\n")
        // Remove leading "## " / "### " markers, keep the heading text.
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        // Bullet markers → bullets
        s = s.replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        // Bold / italics markers stripped
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        s = s.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
        // Inline code backticks stripped
        s = s.replace(Regex("`(.+?)`"), "$1")
        // Markdown links [text](url) → text (url)
        s = s.replace(Regex("\\[([^]]+?)\\]\\(([^)]+?)\\)"), "$1 ($2)")
        return s.trim()
    }

    private fun postToast(
        appContext: Context,
        message: String,
    ) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
