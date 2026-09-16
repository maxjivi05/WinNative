package com.winlator.cmod.feature.stores.battlenet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal object BattleNetSession {
    private const val REGISTRY = "Software\\Blizzard Entertainment\\Battle.net\\UnifiedAuth"

    fun requireIdle(checkProcesses: Boolean = false) {
        if (SessionKeepAliveService.isSessionActive() ||
            (checkProcesses && com.winlator.cmod.runtime.system.ProcessHelper.listRunningWineProcesses().isNotEmpty())
        ) throw IOException("Close the running game session before changing Battle.net accounts.")
    }

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "battlenet_session", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(context: Context, root: File, credential: BattleNetCredential) {
        requireIdle(checkProcesses = true)
        val secure = prefs(context)
        if (!secure.edit().putString("account", credential.account).putString("token", credential.token)
                .putString("region", credential.region).putBoolean("pending", true).commit()
        ) throw IOException("Could not save the Battle.net session.")
        stage(context, root)
    }

    fun stage(context: Context, root: File) {
        val secure = prefs(context)
        if (!secure.getBoolean("pending", false)) return
        requireIdle(checkProcesses = true)
        val account = secure.getString("account", null) ?: return
        val token = secure.getString("token", null) ?: return
        val region = secure.getString("region", "US")!!
        val auth = File(root, "auth")
        auth.mkdirs()
        File(auth, "session.bin").let { if (it.exists() && !it.delete()) throw IOException("Could not replace the Battle.net session.") }
        writePrivate(File(auth, "account"), account.toByteArray())
        writePrivate(File(auth, "pending.token"), token.toByteArray())
        File(auth, "status").delete()
        configure(root, account, region)
        if (!secure.edit().remove("token").putBoolean("pending", false).commit()) throw IOException("Could not finish saving the Battle.net session.")
    }

    private fun configure(root: File, account: String?, region: String?) {
        val file = File(root, "appdata/roaming/Battle.net/Battle.net.config")
        val json = if (file.isFile) {
            if (file.length() > 2 * 1024 * 1024) throw IOException("Battle.net configuration is too large.")
            try { JSONObject(file.readText()) } catch (_: Exception) { throw IOException("Battle.net configuration could not be read.") }
        } else JSONObject()
        val client = json.optJSONObject("Client") ?: JSONObject().also { json.put("Client", it) }
        client.put("SavedAccountNames", account.orEmpty()).put("AutoLogin", (account != null).toString())
            .put("RememberAccountName", (account != null).toString())
        if (region != null) {
            val keys = json.keys().asSequence().toList()
            for (key in keys) {
                val section = json.optJSONObject(key) ?: continue
                if (!section.has("Path")) continue
                val services = section.optJSONObject("Services") ?: JSONObject().also { section.put("Services", it) }
                services.put("LastLoginRegion", region)
                services.put("LastLoginAddress", "${region.lowercase(java.util.Locale.ROOT)}.actual.battle.net")
                services.put("LastLoginTassadar", "account.battle.net")
            }
        }
        writePrivate(file, json.toString(4).toByteArray())
    }

    fun clear(context: Context, root: File) {
        requireIdle(checkProcesses = true)
        ContainerManager(context).containers.forEach { container ->
            val registry = File(container.rootDir, ".wine/user.reg")
            if (registry.isFile) WineRegistryEditor(registry).use { editor ->
                editor.removeKey(REGISTRY, true)
                BattleNetCatalog.games.forEach { game ->
                    editor.removeValue("Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\${game.launchCode}", "WEB_TOKEN")
                }
            }
        }
        configure(root, null, null)
        val auth = File(root, "auth")
        for (name in listOf("pending.token", "session.bin", "session.tmp", "account", "status")) {
            val file = File(auth, name)
            if (file.exists() && !file.delete()) throw IOException("Could not remove the saved Battle.net session.")
        }
        if (!prefs(context).edit().clear().commit()) throw IOException("Could not remove Battle.net credentials.")
    }

    private fun writePrivate(file: File, bytes: ByteArray) {
        BattleNetRuntime.atomicWrite(file, bytes)
        file.setReadable(false, false); file.setWritable(false, false)
        if (!file.setReadable(true, true) || !file.setWritable(true, true)) throw IOException("Could not protect the Battle.net session file.")
    }
}
