package com.winlator.cmod.feature.retro

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

class RetroActivity : AppCompatActivity(), RetroInputView.Listener {
    companion object {
        const val EXTRA_ROM_PATH = "retro_rom_path"
        const val EXTRA_SYSTEM_ID = "retro_system_id"
        const val EXTRA_GAME_NAME = "retro_game_name"
        const val EXTRA_SHORTCUT_PATH = "retro_shortcut_path"
        const val EXTRA_SHADER = "retro_shader"
        const val EXTRA_TOUCH_CONTROLS = "retro_touch_controls"
        const val EXTRA_AUDIO = "retro_audio"
    }

    private lateinit var retroView: GLRetroView
    private var overlay: RetroInputView? = null
    private var retroReady = false
    private var gameName = "game"
    private var fastForward = false
    private var audioEnabledSetting = true
    private var system: RetroSystem? = null

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

        val data =
            GLRetroViewData(this).apply {
                coreFilePath = coreFile.absolutePath
                gameFilePath = romFile.absolutePath
                systemDirectory = RetroCoreManager.systemDir(this@RetroActivity).absolutePath
                savesDirectory = savesDir.absolutePath
                shader = shaderFromExtra(intent.getStringExtra(EXTRA_SHADER))
                rumbleEventsEnabled = true
                preferLowLatencyAudio = true
                if (sramFile.isFile) saveRAMState = runCatching { sramFile.readBytes() }.getOrNull()
            }

        audioEnabledSetting = intent.getBooleanExtra(EXTRA_AUDIO, true)
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

        if (intent.getBooleanExtra(EXTRA_TOUCH_CONTROLS, true)) {
            val inputView = RetroInputView(this, this)
            overlay = inputView
            root.addView(
                inputView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        setContentView(root)
        retroReady = true
        observeErrors()
        observeEvents()
    }

    private fun observeEvents() {
        retroView
            .getGLRetroEvents()
            .onEach { event ->
                if (event is GLRetroView.GLRetroEvents.SurfaceCreated && !audioEnabledSetting) {
                    retroView.audioEnabled = false
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

    private fun shaderFromExtra(value: String?): ShaderConfig =
        when (value?.lowercase()) {
            "crt" -> ShaderConfig.CRT
            "lcd" -> ShaderConfig.LCD
            "sharp" -> ShaderConfig.Sharp
            else -> ShaderConfig.Default
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
        if (retroReady && isGamepadSource(event)) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (event.action == KeyEvent.ACTION_DOWN) openMenu()
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
        if (!retroReady) return
        retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
    }

    override fun onDpad(
        x: Float,
        y: Float,
    ) {
        if (!retroReady) return
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, 0)
    }

    override fun onMenu() {
        runOnUiThread { openMenu() }
    }

    private fun openMenu() {
        if (!retroReady) {
            finish()
            return
        }
        overlay?.releaseAll()
        val ffLabel = if (fastForward) "Fast Forward: On" else "Fast Forward: Off"
        val items = arrayOf("Save State", "Load State", "Reset", ffLabel, "Exit")
        AlertDialog
            .Builder(this)
            .setTitle(gameName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveState()
                    1 -> loadState()
                    2 -> retroView.reset()
                    3 -> toggleFastForward()
                    4 -> finish()
                }
            }.setNegativeButton("Resume", null)
            .show()
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        retroView.frameSpeed = if (fastForward) 2 else 1
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
        super.onPause()
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
