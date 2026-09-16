package com.winlator.cmod.feature.stores.battlenet

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object BattleNetRuntime {
    private const val PREFS = "battlenet_runtime"
    private const val SETUP_URL = "https://www.battle.net/download/getInstallerForGame?os=win&version=LIVE&gameProgram=BATTLENET_APP"
    private val lock = Mutex()
    private val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()

    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val artworkPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val artworkHttp = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    data class Snapshot(val containerId: Int, val installs: List<BattleNetInstall>, val sessionProblem: String? = null, val importedCount: Int = 0)

    private fun container(context: Context, requestedId: Int? = null): Container? {
        val manager = ContainerManager(context)
        return if (requestedId != null) {
            manager.getContainerById(requestedId)?.takeIf { SetupWizardActivity.isContainerUsable(context, it) }
                ?: throw IOException("The selected Battle.net container is unavailable.")
        } else {
            SetupWizardActivity.getPreferredGameContainer(context, manager)
        }
    }

    internal fun shared(context: Context) = BattleNetSharedFiles(
        File(com.winlator.cmod.runtime.display.environment.ImageFs.find(context).rootDir.canonicalFile, ".shared/battlenet"),
    )

    @JvmStatic
    @Synchronized
    fun ensureSharedFiles(context: Context, container: Container) {
        val shared = shared(context)
        shared.bind(container.rootDir)
        val helper = File(shared.root, "battlenet-session.exe")
        val bytes = context.assets.open("winnative/battlenet-session.exe").use { it.readBytes() }
        if (!helper.isFile || !helper.readBytes().contentEquals(bytes)) atomicWrite(helper, bytes)
    }

    suspend fun saveCredential(context: Context, credential: BattleNetCredential) = withContext(Dispatchers.IO) {
        lock.withLock { BattleNetSession.save(context, shared(context).root, credential) }
    }

    suspend fun clearCredential(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock { BattleNetSession.clear(context, shared(context).root) }
    }

    @JvmStatic
    fun sessionArguments(container: Container, game: BattleNetGame?): String =
        "\"${launcherWindowsPath(container)}\"" + (game?.let { " " + it.command(false) } ?: "")

    const val SESSION_EXE = "C:\\WinNative\\Battle.net\\battlenet-session.exe"

    private fun client(container: Container): File? = listOf(
        "Program Files (x86)/Battle.net/Battle.net.exe",
        "Program Files (x86)/Battle.net/Battle.net Launcher.exe",
        "Program Files/Battle.net/Battle.net.exe",
        "Program Files/Battle.net/Battle.net Launcher.exe",
    ).map { File(container.rootDir, ".wine/drive_c/$it") }.firstOrNull { it.isFile }

    private fun windowsPath(container: Container, file: File): String =
        "C:\\" + file.relativeTo(File(container.rootDir, ".wine/drive_c")).path.replace('/', '\\')

    @JvmStatic
    fun launcherWindowsPath(container: Container): String =
        windowsPath(container, client(container) ?: throw IOException("Battle.net is not installed in this container."))

    suspend fun prepareLaunch(context: Context, game: BattleNetGame? = null, install: Boolean = false, containerId: Int? = null): Intent =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val container = container(context, containerId)
                    ?: throw IOException("Install Wine/Proton and create a container before setting up Battle.net.")
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val previousContainer = prefs.getInt("last_container", 0)
                if (previousContainer != 0 && previousContainer != container.id &&
                    com.winlator.cmod.runtime.system.SessionKeepAliveService.isSessionActive()
                ) throw IOException("Close the running session before switching Battle.net containers.")
                ensureSharedFiles(context, container)
                BattleNetSession.stage(context, shared(context).root)
                val launcher = client(container)
                val executable = launcher ?: downloadInstaller(context, container)
                if (!prefs.edit().putInt("last_container", container.id).commit()) throw IOException("Could not save the Battle.net session.")
                Intent(context, XServerDisplayActivity::class.java).apply {
                    putExtra("container_id", container.id)
                    putExtra("battlenet_session", true)
                    putExtra("boot_exe", SESSION_EXE)
                    putExtra("boot_exe_args", "\"${windowsPath(container, executable)}\"" +
                        if (launcher != null && game != null) " " + game.command(install) else "")
                }
            }
        }

    private fun sessionProblem(context: Context): String? {
        val file = File(shared(context).root, "auth/status")
        val status = if (file.isFile && file.length() <= 128) file.readText() else ""
        return when (status) {
            "sign_in_required" -> "Battle.net needs you to reconnect your account."
            "credential_import_failed" -> "The Battle.net session could not be opened in this container. Reconnect your account."
            "state_import_failed", "state_save_failed" -> "Battle.net could not share its account cache. Check available storage and reconnect."
            "launch_failed" -> "Battle.net could not start its client. Open Battle.net to retry."
            else -> null
        }
    }

    suspend fun snapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            val database = shared(context).database
            val problem = sessionProblem(context)
            if (!database.isFile) return@withLock Snapshot(0, emptyList(), problem)
            val containerId = container(context)?.id ?: 0
            if (database.length() > BattleNetProductDb.MAX_BYTES) throw IOException("Battle.net database is too large.")
            val bytes = database.inputStream().use { readLimited(it, BattleNetProductDb.MAX_BYTES) }
            Snapshot(containerId, BattleNetProductDb.parse(bytes), problem)
        }
    }

    suspend fun importInstalled(context: Context): Snapshot {
        val state = snapshot(context)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (state.containerId == 0 || state.installs.isEmpty()) return@withLock state
                val container = container(context) ?: return@withLock state
                ensureSharedFiles(context, container)
                val launcher = client(container) ?: return@withLock state
                val existingProducts = ContainerManager(context).loadShortcuts()
                    .filter { it.getExtra("game_source") == "BATTLENET" }
                    .map { it.getExtra("battlenet_product") }.toSet()
                var imported = 0
                state.installs.filter { it.installed && it.playable }.forEach { install ->
                    coroutineContext.ensureActive()
                    val game = BattleNetCatalog.byProduct(install.product) ?: return@forEach
                    val folder = nativePath(context, container, install.path) ?: return@forEach
                    if (!folder.isDirectory) return@forEach
                    val shortcutFile = File(container.desktopDir.canonicalFile, "Battle.net-${game.product}.desktop")
                    val artwork = File(shared(context).root, "artwork/${game.product}.jpg")
                    scheduleArtwork(game, artwork)
                    if (shortcutFile.exists() || game.product in existingProducts) return@forEach
                    val uuid = UUID.nameUUIDFromBytes("battlenet:${container.id}:${game.product}".toByteArray()).toString()
                    val windowsLauncher = windowsPath(container, launcher)
                    val text = buildString {
                        append("[Desktop Entry]\nType=Application\nName=${game.title}\nExec=wine \"$windowsLauncher\"\nIcon=custom_game\n\n[Extra Data]\n")
                        append("game_source=BATTLENET\nbattlenet_product=${game.product}\ncustom_name=${game.title}\n")
                        append("custom_exe=${launcher.absolutePath}\ngame_install_path=${folder.absolutePath}\n")
                        append("uuid=$uuid\ncontainer_id=${container.id}\nuse_container_defaults=1\n")
                        append("customCoverArtPath=${artwork.absolutePath}\n")
                    }
                    atomicWrite(shortcutFile, text.toByteArray())
                    imported++
                }
                state.copy(importedCount = imported)
            }
        }
    }

    suspend fun hasUpdate(context: Context, install: BattleNetInstall): Boolean = withContext(Dispatchers.IO) {
        val local = lock.withLock {
            val container = container(context) ?: throw IOException(context.getString(com.winlator.cmod.R.string.battlenet_failed))
            val folder = nativePath(context, container, install.path)
                ?: throw IOException(context.getString(com.winlator.cmod.R.string.battlenet_failed))
            val file = File(folder, ".build.info")
            val bytes = file.inputStream().use { readLimited(it, 1024 * 1024) }
            BattleNetBuildInfo.activeKey(bytes.toString(Charsets.UTF_8), install.product)
                ?: throw IOException(context.getString(com.winlator.cmod.R.string.battlenet_failed))
        }
        val response = try {
            org.json.JSONObject(BattleNetNative.latestBuild(install.product, BattleNetSession.region(context)))
        } catch (_: LinkageError) {
            throw IOException(context.getString(com.winlator.cmod.R.string.battlenet_failed))
        }
        coroutineContext.ensureActive()
        val build = response.optJSONObject("build")
            ?: throw IOException(context.getString(com.winlator.cmod.R.string.battlenet_failed))
        local != build.getString("buildKey")
    }

    private fun nativePath(context: Context, container: Container, path: String): File? {
        if (!Regex("^[A-Za-z]:[\\\\/].*").matches(path) || path.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        val drive = path.take(1).uppercase(java.util.Locale.ROOT)
        val root = if (drive == "C") File(container.rootDir, ".wine/drive_c") else {
            Container.drivesIterator(container.drives).firstOrNull { it[0].equals(drive, true) }?.let { File(it[1]) } ?: return null
        }
        val resolved = File(root, path.drop(3).replace('\\', '/')).canonicalFile
        val canonicalRoot = root.canonicalFile
        val sharedGames = File(shared(context).root, "games").canonicalFile
        return resolved.takeIf {
            (it.toPath().startsWith(canonicalRoot.toPath()) && it != canonicalRoot) ||
                (it.toPath().startsWith(sharedGames.toPath()) && it != sharedGames)
        }
    }

    private suspend fun downloadInstaller(context: Context, container: Container): File {
        val destination = File(shared(context).root, "setup/Battle.net-Setup.exe")
        download(SETUP_URL, destination, 64 * 1024 * 1024, executable = true)
        return File(container.rootDir, ".wine/drive_c/WinNative/Battle.net/setup/Battle.net-Setup.exe")
    }

    private fun scheduleArtwork(game: BattleNetGame, artwork: File) {
        if (artwork.isFile || !artworkPending.add(artwork.absolutePath)) return
        artworkScope.launch {
            try {
                download(game.coverUrl, artwork, 8 * 1024 * 1024, artworkHttp)
                com.winlator.cmod.app.PluviaApp.events.emit(com.winlator.cmod.feature.stores.steam.events.AndroidEvent.LibraryArtworkChanged)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IOException) {
            } finally {
                artworkPending.remove(artwork.absolutePath)
            }
        }
    }

    private suspend fun download(url: String, destination: File, limit: Int, client: OkHttpClient = http, executable: Boolean = false) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful || response.request.url.scheme != "https") throw IOException("Battle.net download failed (${response.code}).")
            val body = response.body ?: throw IOException("Empty Battle.net download.")
            if (body.contentLength() > limit) throw IOException("Battle.net download is too large.")
            if (executable) {
                val source = body.source()
                if (!source.request(2) || source.buffer[0] != 0x4d.toByte() || source.buffer[1] != 0x5a.toByte()) {
                    throw IOException("Battle.net returned an invalid installer.")
                }
            }
            BattleNetSharedFiles.requireUnlinkedPath(destination)
            BattleNetSharedFiles.requireUnlinkedPath(File(destination.path + ".new"))
            BattleNetSharedFiles.requireUnlinkedPath(File(destination.path + ".bak"))
            if (!destination.parentFile!!.isDirectory && !destination.parentFile!!.mkdirs() && !destination.parentFile!!.isDirectory) {
                throw IOException("Could not create the Battle.net directory.")
            }
            val atomic = AtomicFile(destination)
            val output = atomic.startWrite()
            try {
                body.byteStream().use { input ->
                    val buffer = ByteArray(16384)
                    var count = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > limit) throw IOException("Battle.net download exceeds the size limit.")
                        output.write(buffer, 0, read)
                    }
                }
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
        }
    }

    internal fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > limit) throw IOException("Battle.net response exceeds the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun atomicWrite(file: File, bytes: ByteArray) {
        BattleNetSharedFiles.requireUnlinkedPath(file)
        BattleNetSharedFiles.requireUnlinkedPath(File(file.path + ".new"))
        BattleNetSharedFiles.requireUnlinkedPath(File(file.path + ".bak"))
        if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException("Could not create the Battle.net directory.")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
