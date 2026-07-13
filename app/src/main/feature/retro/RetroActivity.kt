package com.winlator.cmod.feature.retro

import android.content.SharedPreferences
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.shared.android.FixedFontScaleAppCompatActivity
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

class RetroActivity : FixedFontScaleAppCompatActivity(), RetroInputView.Listener {
    companion object {
        const val EXTRA_ROM_PATH = "retro_rom_path"
        const val EXTRA_SYSTEM_ID = "retro_system_id"
        const val EXTRA_GAME_NAME = "retro_game_name"
        const val EXTRA_SHORTCUT_PATH = "retro_shortcut_path"
        const val EXTRA_CONTAINER_ID = "retro_container_id"
        const val EXTRA_SHADER = "retro_shader"
        const val EXTRA_TOUCH_CONTROLS = "retro_touch_controls"
        const val EXTRA_AUDIO = "retro_audio"
        const val EXTRA_VARIABLES = "retro_variables"

        private val SHADER_KEYS = listOf("default", "crt", "lcd", "sharp")
        private val SHADER_LABELS = listOf("Default", "CRT", "LCD", "Sharp")
    }

    private lateinit var retroView: GLRetroView
    private var overlay: RetroInputView? = null
    private val menu = RetroMenuController()
    private var retroReady = false
    private var gameName = "game"
    private var fastForward = false
    private var audioEnabledSetting = true
    private var touchControlsSetting = true
    private var currentShaderKey = "default"
    private var coreVars = HashMap<String, String>()
    private var diskCount = 0
    private var currentDisk = 0
    private var system: RetroSystem? = null
    private var persistShortcut: Shortcut? = null
    private var playtimePrefs: SharedPreferences? = null
    private var sessionStart = 0L
    private var emulationPaused = false

    private fun pauseEmulation() {
        if (emulationPaused || !retroReady) return
        emulationPaused = true
        retroView.onPause()
        LibretroDroid.pause()
    }

    private fun resumeEmulation() {
        if (!emulationPaused || !retroReady) return
        emulationPaused = false
        LibretroDroid.resume()
        retroView.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        val systemId = intent.getStringExtra(EXTRA_SYSTEM_ID)
        gameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: "game"
        val resolvedSystem = RetroSystems.fromId(systemId)
        system = resolvedSystem

        if (romPath.isNullOrBlank() || resolvedSystem == null) {
            Toast.makeText(this, "Invalid retro game", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val romFile = File(romPath)
        if (!romFile.isFile) {
            Toast.makeText(this, "ROM not found: $romPath", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val coreFile = RetroCoreManager.coreFile(this, resolvedSystem)
        if (!coreFile.isFile) {
            Toast.makeText(this, "Core not installed: ${resolvedSystem.coreFileName}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (RetroCoreManager.missingBios(this, resolvedSystem)) {
            Toast.makeText(
                this,
                "${resolvedSystem.shortName} needs a BIOS in ${RetroCoreManager.systemDir(this)}",
                Toast.LENGTH_LONG,
            ).show()
        }

        val savesDir = RetroCoreManager.savesDir(this)
        val sramFile = File(savesDir, sramName())
        currentShaderKey = intent.getStringExtra(EXTRA_SHADER)?.lowercase() ?: "default"
        if (currentShaderKey !in SHADER_KEYS) currentShaderKey = "default"
        audioEnabledSetting = intent.getBooleanExtra(EXTRA_AUDIO, true)
        touchControlsSetting = intent.getBooleanExtra(EXTRA_TOUCH_CONTROLS, true)
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        coreVars = (intent.getSerializableExtra(EXTRA_VARIABLES) as? HashMap<String, String>) ?: HashMap()

        val data =
            GLRetroViewData(this).apply {
                coreFilePath = coreFile.absolutePath
                gameFilePath = romFile.absolutePath
                systemDirectory = RetroCoreManager.systemDir(this@RetroActivity).absolutePath
                savesDirectory = savesDir.absolutePath
                shader = shaderFromKey(currentShaderKey)
                variables = coreVars.map { Variable(it.key, it.value) }.toTypedArray()
                rumbleEventsEnabled = true
                preferLowLatencyAudio = true
                if (sramFile.isFile) saveRAMState = runCatching { sramFile.readBytes() }.getOrNull()
            }

        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        val root = FrameLayout(this)
        root.addView(
            retroView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val inputView = RetroInputView(this, this, resolvedSystem)
        inputView.visibility = if (touchControlsSetting) View.VISIBLE else View.GONE
        overlay = inputView
        root.addView(
            inputView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        menu.entriesProvider = { pane -> buildEntriesFor(pane) }
        menu.tabs = RetroDrawerTabs.build(resolvedSystem, RetroCoreOptions.forSystem(resolvedSystem).isNotEmpty())
        menu.onExit = { finish() }
        val menuView =
            ComposeView(this).apply {
                setContent {
                    WinNativeTheme {
                        RetroDrawerMenu(menu)
                    }
                }
            }
        root.addView(
            menuView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(root)
        retroReady = true
        recordLaunchStats()
        observeErrors()
        observeEvents()
    }

    private fun recordLaunchStats() {
        val prefs = getSharedPreferences("playtime_stats", MODE_PRIVATE)
        playtimePrefs = prefs
        sessionStart = System.currentTimeMillis()
        prefs
            .edit()
            .putInt("${gameName}_play_count", prefs.getInt("${gameName}_play_count", 0) + 1)
            .putLong("${gameName}_last_played", sessionStart)
            .apply()
    }

    private fun accumulatePlaytime() {
        val prefs = playtimePrefs ?: return
        val now = System.currentTimeMillis()
        val delta = now - sessionStart
        if (delta > 0) {
            prefs
                .edit()
                .putLong("${gameName}_playtime", prefs.getLong("${gameName}_playtime", 0L) + delta)
                .apply()
        }
        sessionStart = now
    }

    private fun observeEvents() {
        retroView
            .getGLRetroEvents()
            .onEach { event ->
                if (event is GLRetroView.GLRetroEvents.SurfaceCreated) {
                    if (!audioEnabledSetting) retroView.audioEnabled = false
                    lifecycleScope.launch(Dispatchers.Default) {
                        runCatching {
                            diskCount = retroView.getAvailableDisks()
                            currentDisk = retroView.getCurrentDisk()
                        }
                    }
                }
            }.launchIn(lifecycleScope)
    }

    private fun observeErrors() {
        retroView
            .getGLRetroErrors()
            .onEach { error ->
                val message =
                    when (error) {
                        GLRetroView.ERROR_LOAD_LIBRARY -> "Failed to load emulator core"
                        GLRetroView.ERROR_LOAD_GAME -> "Failed to load ROM"
                        GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "Graphics not supported for this core"
                        else -> "Emulator error"
                    }
                Toast.makeText(this@RetroActivity, message, Toast.LENGTH_LONG).show()
                finish()
            }.launchIn(lifecycleScope)
    }

    private fun sramName(): String {
        val safe = gameName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$safe.srm"
    }

    private fun shaderFromKey(value: String?): ShaderConfig =
        when (value?.lowercase()) {
            "crt" -> ShaderConfig.CRT
            "lcd" -> ShaderConfig.LCD
            "sharp" -> ShaderConfig.Sharp
            else -> ShaderConfig.Default
        }

    private fun persistExtra(
        key: String,
        value: String,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val shortcut =
                    persistShortcut ?: run {
                        val containerId = intent.getIntExtra(EXTRA_CONTAINER_ID, 0)
                        val path = intent.getStringExtra(EXTRA_SHORTCUT_PATH)
                        if (containerId <= 0 || path.isNullOrBlank()) return@run null
                        val file = File(path)
                        if (!file.isFile) return@run null
                        ContainerManager(this@RetroActivity)
                            .getContainerById(containerId)
                            ?.let { Shortcut(it, file) }
                    }?.also { persistShortcut = it }
                shortcut?.putExtra(key, value)
                shortcut?.saveData()
            }
        }
    }

    private fun buildEntriesFor(pane: RetroPane?): List<RetroMenuEntry> =
        when (pane) {
            null -> buildMainEntries()
            RetroPane.DISPLAY ->
                SHADER_KEYS.mapIndexed { index, key ->
                    RetroMenuEntry.Radio(
                        label = SHADER_LABELS[index],
                        selected = currentShaderKey == key,
                    ) {
                        currentShaderKey = key
                        retroView.shader = shaderFromKey(key)
                        persistExtra(RetroShortcuts.KEY_SHADER, key)
                        menu.rebuild()
                    }
                }
            RetroPane.SYSTEM ->
                RetroCoreOptions.forSystem(system).map { option ->
                    val current = coreVars[option.key] ?: option.defaultValue
                    val index = option.values.indexOf(current).coerceAtLeast(0)
                    RetroMenuEntry.Choice(option.label, option.valueLabels[index]) { direction ->
                        val next = (index + direction + option.values.size) % option.values.size
                        val newValue = option.values[next]
                        coreVars[option.key] = newValue
                        retroView.updateVariables(Variable(option.key, newValue))
                        persistExtra(RetroShortcuts.VAR_PREFIX + option.key, newValue)
                        menu.rebuild()
                    }
                }
            RetroPane.SOUND ->
                listOf(
                    RetroMenuEntry.Toggle("Sound", checked = audioEnabledSetting) { value ->
                        audioEnabledSetting = value
                        retroView.audioEnabled = value
                        persistExtra(RetroShortcuts.KEY_AUDIO, if (value) "1" else "0")
                        menu.rebuild()
                    },
                )
            RetroPane.CONTROLS ->
                listOf(
                    RetroMenuEntry.Toggle("On-screen Controls", checked = touchControlsSetting) { value ->
                        touchControlsSetting = value
                        overlay?.visibility = if (value) View.VISIBLE else View.GONE
                        persistExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (value) "1" else "0")
                        menu.rebuild()
                    },
                )
        }

    private fun buildMainEntries(): List<RetroMenuEntry> {
        val entries = mutableListOf<RetroMenuEntry>()
        entries +=
            if (emulationPaused) {
                RetroMenuEntry.Action("Resume", RetroDrawerIcons.Resume, active = true) {
                    resumeEmulation()
                    menu.close()
                }
            } else {
                RetroMenuEntry.Action("Pause", RetroDrawerIcons.Pause) {
                    pauseEmulation()
                    menu.close()
                }
            }
        entries +=
            RetroMenuEntry.Action("Save State", RetroDrawerIcons.Save) {
                menu.close()
                saveState()
            }
        entries +=
            RetroMenuEntry.Action("Load State", RetroDrawerIcons.Load) {
                menu.close()
                loadState()
            }
        entries +=
            RetroMenuEntry.Action("Reset", RetroDrawerIcons.Reset) {
                menu.close()
                retroView.reset()
            }
        entries +=
            RetroMenuEntry.Action("Fast Forward", RetroDrawerIcons.FastForward, active = fastForward) {
                fastForward = !fastForward
                retroView.frameSpeed = if (fastForward) 2 else 1
                menu.rebuild()
            }
        if (diskCount > 1) {
            entries +=
                RetroMenuEntry.Action("Disc ${currentDisk + 1}/$diskCount", RetroDrawerIcons.Disc) {
                    val next = (currentDisk + 1) % diskCount
                    lifecycleScope.launch(Dispatchers.Default) {
                        runCatching { retroView.changeDisk(next) }
                        currentDisk = next
                        runOnUiThread { menu.rebuild() }
                    }
                }
        }
        return entries
    }

    private fun openMenu() {
        if (!retroReady) {
            finish()
            return
        }
        overlay?.releaseAll()
        menu.open()
    }

    private fun mapPhysicalKey(keyCode: Int): Int =
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }

    private fun isGamepadSource(event: KeyEvent): Boolean {
        val source = event.device?.sources ?: return false
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private val forwardedKeys =
        setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (menu.visible && (isGamepadSource(event) || keyCode == KeyEvent.KEYCODE_BACK)) {
            menu.handleKey(keyCode, event.action)
            return true
        }
        if (retroReady && isGamepadSource(event)) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
            if (keyCode in forwardedKeys) {
                retroView.sendKeyEvent(event.action, mapPhysicalKey(keyCode), 0)
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (menu.visible && event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            return true
        }
        if (retroReady &&
            event.action == MotionEvent.ACTION_MOVE &&
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        ) {
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                0,
            )
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                0,
            )
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                0,
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onButton(
        keyCode: Int,
        down: Boolean,
    ) {
        if (!retroReady || menu.visible) return
        retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
    }

    override fun onDpad(
        x: Float,
        y: Float,
    ) {
        if (!retroReady || menu.visible) return
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, 0)
    }

    override fun onStick(
        x: Float,
        y: Float,
    ) {
        if (!retroReady || menu.visible) return
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, x, y, 0)
    }

    override fun onMenu() {
        runOnUiThread { openMenu() }
    }

    private fun saveState() {
        runCatching {
            val bytes = retroView.serializeState()
            RetroCoreManager.stateFile(this, gameName, 0).writeBytes(bytes)
        }.onSuccess {
            Toast.makeText(this, "State saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not save state", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadState() {
        val file = RetroCoreManager.stateFile(this, gameName, 0)
        if (!file.isFile) {
            Toast.makeText(this, "No saved state", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { retroView.unserializeState(file.readBytes()) }
            .onSuccess { Toast.makeText(this, "State loaded", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Could not load state", Toast.LENGTH_SHORT).show() }
    }

    private fun persistSram() {
        if (!retroReady) return
        runCatching {
            val sram = retroView.serializeSRAM()
            if (sram.isNotEmpty()) {
                File(RetroCoreManager.savesDir(this), sramName()).writeBytes(sram)
            }
        }
    }

    override fun onPause() {
        persistSram()
        accumulatePlaytime()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (emulationPaused && retroReady) {
            window.decorView.post {
                if (emulationPaused && retroReady) {
                    retroView.onPause()
                    LibretroDroid.pause()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
