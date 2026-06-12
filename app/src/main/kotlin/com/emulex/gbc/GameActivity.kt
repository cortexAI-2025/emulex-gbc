package com.emulex.gbc

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emulex.gbc.cartridge.Cartridge
import com.emulex.gbc.core.GameBoy
import com.emulex.gbc.core.Joypad
import com.emulex.gbc.databinding.ActivityGameBinding
import com.emulex.gbc.utils.FileUtils
import com.emulex.gbc.utils.SaveState
import kotlinx.coroutines.*

/**
 * Activité de jeu — lance l'émulation dans un thread dédié.
 * Gère la pause/reprise via le cycle de vie Android.
 */
class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROM_URI = "rom_uri"
        const val TARGET_FPS = 60
        const val FRAME_TIME_NS = 1_000_000_000L / TARGET_FPS
    }

    private lateinit var binding: ActivityGameBinding
    private var gameBoy: GameBoy? = null
    private var emulationJob: Job? = null
    private var romName = ""
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Plein écran immersif
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()

        val uriString = intent.getStringExtra(EXTRA_ROM_URI)
        if (uriString == null) { finish(); return }

        setupToolbar()
        loadAndStart(Uri.parse(uriString))
    }

    private fun setupToolbar() {
        binding.toolbar.visibility = View.GONE

        binding.btnPause.setOnClickListener { togglePause() }
        binding.btnReset.setOnClickListener { resetEmulation() }
        binding.btnSaveState.setOnClickListener { showSaveStateDialog() }
        binding.btnLoadState.setOnClickListener { showLoadStateDialog() }

        // Tap sur le bouton menu du boîtier ou sur l'écran → bascule la toolbar
        binding.shellView.onScreenTap = { toggleToolbar() }
    }

    private fun toggleToolbar() {
        binding.toolbar.visibility =
            if (binding.toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun loadAndStart(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val romData = FileUtils.loadRom(this@GameActivity, uri)
                if (romData == null || romData.size < 0x150) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GameActivity, "ROM invalide", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@launch
                }

                val cartridge = Cartridge.load(romData)
                val gb = GameBoy(cartridge)

                // Charger sauvegarde RAM si existante
                romName = FileUtils.getRomNameFromUri(this@GameActivity, uri)
                val savData = FileUtils.loadSav(this@GameActivity, romName)
                if (savData != null && cartridge.hasBattery()) {
                    cartridge.loadRamData(savData)
                }

                withContext(Dispatchers.Main) {
                    gameBoy = gb
                    binding.shellView.gameBoy = gb
                    binding.shellView.onButton = { btn, isPressed -> gb.setButton(btn, isPressed) }
                    binding.tvRomTitle.text = cartridge.title
                    startEmulation(gb)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GameActivity, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Boucle d'émulation (thread dédié)
    // ─────────────────────────────────────────────────────────────────
    private fun startEmulation(gb: GameBoy) {
        gb.apu.start()

        emulationJob = lifecycleScope.launch(Dispatchers.Default) {
            var lastFrameTime = System.nanoTime()

            while (isActive) {
                if (isPaused) {
                    delay(16)
                    lastFrameTime = System.nanoTime()
                    continue
                }

                val frameStart = System.nanoTime()

                // Exécuter une frame complète
                gb.runFrame()

                // Dessiner
                binding.shellView.updateFrame()

                // Synchronisation temporelle (~60 FPS)
                val elapsed = System.nanoTime() - frameStart
                val sleepNs = FRAME_TIME_NS - elapsed
                if (sleepNs > 0) {
                    delay(sleepNs / 1_000_000)
                }
            }
        }
    }

    private fun togglePause() {
        isPaused = !isPaused
        binding.btnPause.text = if (isPaused) "▶" else "⏸"
        if (isPaused) gameBoy?.apu?.stop() else gameBoy?.apu?.start()
    }

    private fun resetEmulation() {
        gameBoy?.reset()
        if (isPaused) { isPaused = false; binding.btnPause.text = "⏸" }
    }

    // ─────────────────────────────────────────────────────────────────
    // Save States
    // ─────────────────────────────────────────────────────────────────
    private fun showSaveStateDialog() {
        val slots = arrayOf("Slot 1", "Slot 2", "Slot 3")
        AlertDialog.Builder(this)
            .setTitle("Sauvegarder l'état")
            .setItems(slots) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val gb = gameBoy ?: return@launch
                    val state = gb.saveState()
                    val data = SaveState.serialize(state)
                    FileUtils.saveState(this@GameActivity, romName, which + 1, data)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GameActivity, "Sauvegardé dans Slot ${which + 1}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun showLoadStateDialog() {
        val slots = arrayOf("Slot 1", "Slot 2", "Slot 3")
        AlertDialog.Builder(this)
            .setTitle("Charger l'état")
            .setItems(slots) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val gb = gameBoy ?: return@launch
                    val data = FileUtils.loadState(this@GameActivity, romName, which + 1)
                    val state = data?.let { SaveState.deserialize(it) }
                    if (state != null) {
                        gb.loadState(state)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@GameActivity, "Chargé depuis Slot ${which + 1}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@GameActivity, "Aucune sauvegarde dans Slot ${which + 1}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.show()
    }

    // ─────────────────────────────────────────────────────────────────
    // Clavier physique
    // ─────────────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return mapKey(keyCode, true) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return mapKey(keyCode, false) || super.onKeyUp(keyCode, event)
    }

    private fun mapKey(keyCode: Int, pressed: Boolean): Boolean {
        val btn = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> Joypad.BTN_RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> Joypad.BTN_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> Joypad.BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Joypad.BTN_DOWN
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z -> Joypad.BTN_A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> Joypad.BTN_B
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Joypad.BTN_START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Joypad.BTN_SELECT
            else -> return false
        }
        gameBoy?.setButton(btn, pressed)
        return true
    }

    // ─────────────────────────────────────────────────────────────────
    // Cycle de vie
    // ─────────────────────────────────────────────────────────────────
    override fun onPause() {
        super.onPause()
        if (!isPaused) {
            isPaused = true
            gameBoy?.apu?.stop()
        }
        saveRamIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        if (gameBoy != null && isPaused) {
            isPaused = false
            gameBoy?.apu?.start()
        }
    }

    override fun onDestroy() {
        emulationJob?.cancel()
        gameBoy?.apu?.stop()
        saveRamIfNeeded()
        super.onDestroy()
    }

    private fun saveRamIfNeeded() {
        val gb = gameBoy ?: return
        if (gb.cartridge.hasBattery() && romName.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                FileUtils.saveSav(this@GameActivity, romName, gb.cartridge.getRamData())
            }
        }
    }

    private fun setupImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }
}
