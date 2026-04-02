package com.winlator.cmod.google

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.games.PlayGames
import com.google.android.gms.tasks.Tasks
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

object ContainerBackupManager {
    private const val TAG = "ContainerBackup"
    private const val DRIVE_FOLDER_NAME = "WinNative"
    private const val DRIVE_CONTAINERS_FOLDER_NAME = "Containers"
    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFS_NAME = "google_store_login_sync"
    private const val KEY_GOOGLE_SYNC_ENABLED = "google_sync_enabled"
    private const val AUTH_SESSION_RETRY_COUNT = 5
    private const val AUTH_SESSION_RETRY_DELAY_MS = 750L

    const val REQUEST_CODE_DRIVE_AUTH = 9003

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var pendingAction: (() -> Unit)? = null
    private var pendingCallback: ResultCallback? = null

    data class BackupEntry(
        val id: String,
        val name: String,
        val modifiedTime: String? = null,
    )

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val requiresSelection: Boolean = false,
        val backups: List<BackupEntry> = emptyList(),
    )

    fun interface ResultCallback {
        fun onResult(result: OperationResult)
    }

    private data class AuthResult(
        val accessToken: String? = null,
        val launchedConsent: Boolean = false,
        val errorMessage: String? = null,
    )

    @JvmStatic
    fun backupContainer(activity: Activity, container: Container, callback: ResultCallback) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    authorize(activity, callback) { accessToken ->
                        doBackup(activity, container, accessToken)
                    }
                }.getOrElse { error ->
                    Timber.tag(TAG).e(error, "Container backup failed for %s", container.name)
                    OperationResult(false, "Container backup failed: ${error.message}")
                }
            }
            if (result != null) callback.onResult(result)
        }
    }

    @JvmStatic
    fun restoreContainer(activity: Activity, container: Container, callback: ResultCallback) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    authorize(activity, callback) { accessToken ->
                        val folderId = getOrCreateContainersFolder(accessToken)
                            ?: return@authorize OperationResult(false, "Failed to access WinNative/Containers on Google Drive.")

                        val exactName = buildBackupFileName(container.name)
                        val exactMatch = findDriveFileByName(accessToken, folderId, exactName)
                        if (exactMatch != null) {
                            return@authorize doRestore(activity, container, accessToken, exactMatch)
                        }

                        val backups = listDriveBackups(accessToken, folderId)
                            .sortedBy { it.name.lowercase() }
                        if (backups.isEmpty()) {
                            OperationResult(false, "No container backups were found in Google Drive.")
                        } else {
                            OperationResult(
                                success = false,
                                message = "Select a container backup to restore.",
                                requiresSelection = true,
                                backups = backups,
                            )
                        }
                    }
                }.getOrElse { error ->
                    Timber.tag(TAG).e(error, "Container restore lookup failed for %s", container.name)
                    OperationResult(false, "Container restore failed: ${error.message}")
                }
            }
            if (result != null) callback.onResult(result)
        }
    }

    @JvmStatic
    fun restoreContainerFromBackup(
        activity: Activity,
        container: Container,
        backupId: String,
        backupName: String,
        callback: ResultCallback,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    authorize(activity, callback) { accessToken ->
                        doRestore(activity, container, accessToken, BackupEntry(backupId, backupName))
                    }
                }.getOrElse { error ->
                    Timber.tag(TAG).e(error, "Container restore failed for %s from %s", container.name, backupName)
                    OperationResult(false, "Container restore failed: ${error.message}")
                }
            }
            if (result != null) callback.onResult(result)
        }
    }

    @JvmStatic
    fun onDriveAuthResult(activity: Activity, resultCode: Int) {
        val callback = pendingCallback
        val action = pendingAction
        pendingCallback = null
        pendingAction = null

        if (resultCode == Activity.RESULT_OK) {
            Timber.tag(TAG).i("Container backup Drive authorization granted")
            action?.invoke()
        } else {
            Timber.tag(TAG).w("Container backup Drive authorization denied (resultCode=%d)", resultCode)
            callback?.onResult(OperationResult(false, "Google Drive access was denied."))
        }
    }

    private suspend fun authorize(
        activity: Activity,
        callback: ResultCallback,
        block: (String) -> OperationResult,
    ): OperationResult? {
        val context = activity.applicationContext
        if (!isGoogleSyncEnabled(context)) {
            return OperationResult(false, "Sign in to Google first in Settings > Google.")
        }
        if (!awaitAuthenticatedSession(activity)) {
            return OperationResult(false, "Google sign-in is still finishing. Try again in a moment.")
        }

        val authResult = getDriveAccessToken(activity)
        return when {
            authResult.accessToken != null -> block(authResult.accessToken)
            authResult.launchedConsent -> {
                pendingCallback = callback
                pendingAction = { blockOnMain(activity, callback, block) }
                null
            }
            else -> OperationResult(false, authResult.errorMessage ?: "Failed to access Google Drive.")
        }
    }

    private fun blockOnMain(
        activity: Activity,
        callback: ResultCallback,
        block: (String) -> OperationResult,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                authorize(activity, callback, block)
            }
            if (result != null) callback.onResult(result)
        }
    }

    private fun doBackup(activity: Activity, container: Container, accessToken: String): OperationResult {
        val tempRoot = File(activity.cacheDir, "container-backup-${container.id}-${System.currentTimeMillis()}")
        val zipFile = File(tempRoot, buildBackupFileName(container.name))
        val userDir = getContainerUserDir(container)

        try {
            if (!tempRoot.mkdirs()) {
                return OperationResult(false, "Failed to prepare temporary backup files.")
            }
            if (!userDir.exists() || !userDir.isDirectory) {
                return OperationResult(false, "The container user save folder was not found.")
            }

            zipContainer(userDir, zipFile)
            if (!zipFile.exists() || zipFile.length() == 0L) {
                return OperationResult(false, "The container backup zip is empty.")
            }

            val folderId = getOrCreateContainersFolder(accessToken)
                ?: return OperationResult(false, "Failed to access WinNative/Containers on Google Drive.")
            val existingFileId = findDriveFileByName(accessToken, folderId, zipFile.name)?.id
            val uploaded = if (existingFileId != null) {
                updateDriveFile(accessToken, existingFileId, zipFile)
            } else {
                createDriveFile(accessToken, folderId, zipFile.name, zipFile)
            }

            return if (uploaded) {
                OperationResult(true, "Container backed up to Google Drive.")
            } else {
                OperationResult(false, "Failed to upload the container backup.")
            }
        } finally {
            FileUtils.delete(tempRoot)
        }
    }

    private fun doRestore(activity: Activity, container: Container, accessToken: String, backup: BackupEntry): OperationResult {
        val tempRoot = File(activity.cacheDir, "container-restore-${container.id}-${System.currentTimeMillis()}")
        val zipFile = File(tempRoot, backup.name)
        val extractDir = File(tempRoot, "extract")
        val userDir = getContainerUserDir(container)

        try {
            if (!tempRoot.mkdirs()) {
                return OperationResult(false, "Failed to prepare temporary restore files.")
            }

            if (!downloadDriveFileToPath(accessToken, backup.id, zipFile)) {
                return OperationResult(false, "Failed to download the container backup.")
            }

            if (!extractDir.mkdirs()) {
                return OperationResult(false, "Failed to prepare the restore folder.")
            }

            unzipToDirectory(zipFile, extractDir)

            if (!userDir.exists() && !userDir.mkdirs()) {
                return OperationResult(false, "Failed to prepare the container user save folder.")
            }
            if (!FileUtils.clear(userDir)) {
                return OperationResult(false, "Failed to clear the existing container save files before restore.")
            }
            if (!copyDirectoryContents(extractDir, userDir)) {
                return OperationResult(false, "Failed to restore the container save files.")
            }

            return OperationResult(true, "Container restored from Google Drive.")
        } finally {
            FileUtils.delete(tempRoot)
        }
    }

    private fun copyDirectoryContents(sourceDir: File, targetDir: File): Boolean {
        val files = sourceDir.listFiles() ?: return true
        for (file in files) {
            val destination = File(targetDir, file.name)
            if (!FileUtils.copy(file, destination)) {
                return false
            }
        }
        return true
    }

    private fun zipContainer(sourceDir: File, zipFile: File) {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { output ->
            zipDirectoryContents(output, sourceDir, "")
        }
    }

    private fun zipDirectoryContents(output: ZipOutputStream, directory: File, prefix: String) {
        val children = directory.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (FileUtils.isSymlink(child)) {
                continue
            }

            val entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                output.putNextEntry(ZipEntry("$entryName/"))
                output.closeEntry()
                zipDirectoryContents(output, child, entryName)
            } else {
                output.putNextEntry(ZipEntry(entryName))
                FileInputStream(child).use { input ->
                    input.copyTo(output)
                }
                output.closeEntry()
            }
        }
    }

    private fun unzipToDirectory(zipFile: File, destinationDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val outputFile = File(destinationDir, entry.name)
                val targetRoot = destinationDir.canonicalPath + File.separator
                val outputPath = if (entry.isDirectory) {
                    outputFile.canonicalPath + File.separator
                } else {
                    outputFile.canonicalPath
                }
                if (!outputPath.startsWith(targetRoot)) {
                    throw SecurityException("Zip entry tries to escape restore directory")
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun buildBackupFileName(containerName: String): String {
        return "${sanitizeFileName(containerName)}.zip"
    }

    private fun getContainerUserDir(container: Container): File {
        return File(container.rootDir, ".wine/drive_c/users/xuser")
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (sanitized.isEmpty()) "Container" else sanitized
    }

    private fun isGoogleSyncEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GOOGLE_SYNC_ENABLED, false)
    }

    private suspend fun awaitAuthenticatedSession(activity: Activity): Boolean {
        repeat(AUTH_SESSION_RETRY_COUNT) { attempt ->
            if (isAuthenticatedBlocking(activity)) {
                return true
            }
            if (attempt < AUTH_SESSION_RETRY_COUNT - 1) {
                delay(AUTH_SESSION_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private suspend fun isAuthenticatedBlocking(activity: Activity): Boolean {
        return try {
            val task = PlayGames.getGamesSignInClient(activity).isAuthenticated
            val result = withContext(Dispatchers.IO) {
                try {
                    Tasks.await(task, 10, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    Timber.tag(TAG).e("Timeout waiting for Google authentication state")
                    null
                }
            }
            result?.isAuthenticated == true
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to read Google authentication state")
            false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun getDriveAccessToken(activity: Activity): AuthResult = withContext(Dispatchers.IO) {
        try {
            val authRequest = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .build()

            val authResult: AuthorizationResult = suspendCancellableCoroutine { continuation ->
                Identity.getAuthorizationClient(activity)
                    .authorize(authRequest)
                    .addOnSuccessListener { result ->
                        continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        Timber.tag(TAG).e(error, "AuthorizationClient.authorize failed for container backup")
                        continuation.resume(null)
                    }
            } ?: return@withContext AuthResult(errorMessage = "Failed to authorize Google Drive.")

            if (authResult.hasResolution()) {
                val pendingIntent = authResult.pendingIntent
                if (pendingIntent != null) {
                    Timber.tag(TAG).i("Launching Google Drive consent for container backup")
                    activity.runOnUiThread {
                        activity.startIntentSenderForResult(
                            pendingIntent.intentSender,
                            REQUEST_CODE_DRIVE_AUTH,
                            null,
                            0,
                            0,
                            0
                        )
                    }
                    return@withContext AuthResult(launchedConsent = true)
                }
                return@withContext AuthResult(errorMessage = "Google Drive consent could not be started.")
            }

            val token = authResult.accessToken
            if (token.isNullOrEmpty()) {
                AuthResult(errorMessage = "Google Drive access token was empty.")
            } else {
                AuthResult(accessToken = token)
            }
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to get Google Drive access token for container backup")
            AuthResult(errorMessage = "Failed to access Google Drive: ${error.message}")
        }
    }

    private fun getOrCreateContainersFolder(accessToken: String): String? {
        val rootFolderId = getOrCreateDriveFolder(accessToken, null, DRIVE_FOLDER_NAME)
            ?: return null
        return getOrCreateDriveFolder(accessToken, rootFolderId, DRIVE_CONTAINERS_FOLDER_NAME)
    }

    private fun getOrCreateDriveFolder(accessToken: String, parentId: String?, folderName: String): String? {
        val existingFolder = findDriveFolder(accessToken, parentId, folderName)
        if (existingFolder != null) {
            return existingFolder
        }

        val metadata = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
            if (parentId != null) {
                put("parents", JSONArray().put(parentId))
            }
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                return json.optString("id").takeIf { it.isNotEmpty() }
            }
            Timber.tag(TAG).e("Failed to create Drive folder %s: %d %s", folderName, response.code, response.message)
        }
        return null
    }

    private fun findDriveFolder(accessToken: String, parentId: String?, folderName: String): String? {
        val queryParts = mutableListOf(
            "name='${escapeDriveQueryValue(folderName)}'",
            "mimeType='application/vnd.google-apps.folder'",
            "trashed=false",
        )
        if (parentId != null) {
            queryParts += "'$parentId' in parents"
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(queryParts.joinToString(" and "), "UTF-8")}&fields=files(id,name)")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("Failed to search Drive folder %s: %d %s", folderName, response.code, response.message)
                return null
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            val files = json.optJSONArray("files") ?: return null
            if (files.length() == 0) {
                return null
            }
            return files.getJSONObject(0).optString("id").takeIf { it.isNotEmpty() }
        }
    }

    private fun findDriveFileByName(accessToken: String, folderId: String, fileName: String): BackupEntry? {
        val query = "name='${escapeDriveQueryValue(fileName)}' and '$folderId' in parents and trashed=false"
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,modifiedTime)")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("Failed to search Drive file %s: %d %s", fileName, response.code, response.message)
                return null
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            val files = json.optJSONArray("files") ?: return null
            if (files.length() == 0) {
                return null
            }
            val file = files.getJSONObject(0)
            return BackupEntry(
                id = file.optString("id"),
                name = file.optString("name"),
                modifiedTime = file.optString("modifiedTime"),
            )
        }
    }

    private fun listDriveBackups(accessToken: String, folderId: String): List<BackupEntry> {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")}&fields=files(id,name,modifiedTime)&orderBy=name")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("Failed to list container backups: %d %s", response.code, response.message)
                return emptyList()
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            val files = json.optJSONArray("files") ?: return emptyList()
            val results = ArrayList<BackupEntry>(files.length())
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val name = file.optString("name")
                if (name.endsWith(".zip", ignoreCase = true)) {
                    results += BackupEntry(
                        id = file.optString("id"),
                        name = name,
                        modifiedTime = file.optString("modifiedTime"),
                    )
                }
            }
            return results
        }
    }

    private fun createDriveFile(accessToken: String, folderId: String, fileName: String, file: File): Boolean {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("parents", JSONArray().put(folderId))
        }
        val boundary = "winnative_container_${System.currentTimeMillis()}"

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(buildMultipartRequestBody(boundary, metadata.toString(), file))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return true
            }
            Timber.tag(TAG).e("Failed to create Drive file %s: %d %s", fileName, response.code, response.message)
        }
        return false
    }

    private fun updateDriveFile(accessToken: String, fileId: String, file: File): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .patch(file.asRequestBody("application/zip".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return true
            }
            Timber.tag(TAG).e("Failed to update Drive file %s: %d %s", fileId, response.code, response.message)
        }
        return false
    }

    private fun buildMultipartRequestBody(boundary: String, jsonMetadata: String, file: File): RequestBody {
        return object : RequestBody() {
            override fun contentType() = "multipart/related; boundary=$boundary".toMediaType()

            override fun writeTo(sink: okio.BufferedSink) {
                val lineBreak = "\r\n"
                sink.writeUtf8("--$boundary$lineBreak")
                sink.writeUtf8("Content-Type: application/json; charset=UTF-8$lineBreak$lineBreak")
                sink.writeUtf8(jsonMetadata)
                sink.writeUtf8(lineBreak)
                sink.writeUtf8("--$boundary$lineBreak")
                sink.writeUtf8("Content-Type: application/zip$lineBreak$lineBreak")
                FileInputStream(file).use { input ->
                    input.copyTo(sink.outputStream())
                }
                sink.writeUtf8(lineBreak)
                sink.writeUtf8("--$boundary--")
            }
        }
    }

    private fun downloadDriveFileToPath(accessToken: String, fileId: String, destination: File): Boolean {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("Failed to download Drive file %s: %d %s", fileId, response.code, response.message)
                return false
            }
            destination.parentFile?.mkdirs()
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            return destination.exists() && destination.length() > 0L
        }
    }

    private fun escapeDriveQueryValue(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }
}
