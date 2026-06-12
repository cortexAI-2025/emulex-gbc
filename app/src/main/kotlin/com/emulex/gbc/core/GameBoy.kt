package com.emulex.gbc.core

import com.emulex.gbc.cartridge.Cartridge
import com.emulex.gbc.utils.SaveState

/**
 * Orchestrateur principal de l'émulation Game Boy Color.
 * Synchronise CPU, PPU, APU et Timer cycle à cycle.
 *
 * Fréquence CPU : 4.194304 MHz (normal) / 8.388608 MHz (double vitesse GBC)
 * Cycles par frame : 70224 T-cycles (≈ 59.73 Hz)
 */
class GameBoy(val cartridge: Cartridge) {

    companion object {
        const val CYCLES_PER_FRAME = 70224
        const val FRAME_RATE = 59.73
    }

    // ── Périphériques ─────────────────────────────────────────────────
    val joypad = Joypad()
    val timer = Timer()
    val apu = Apu()
    val ppu = Ppu()
    val mmu: Mmu = Mmu(cartridge, ppu, apu, timer, joypad)
    val cpu: Cpu = Cpu(mmu)

    init {
        // Résolution de la dépendance circulaire PPU ↔ MMU
        ppu.mmu = mmu
    }

    // ── État émulation ────────────────────────────────────────────────
    var isRunning = false
    var isPaused = false

    // Frame buffer exposé (160×144 ARGB pixels)
    val frameBuffer: IntArray get() = ppu.frameBuffer
    val isGbc: Boolean get() = cartridge.isGbc

    // ─────────────────────────────────────────────────────────────────
    // Exécute une frame complète (~70224 cycles)
    // ─────────────────────────────────────────────────────────────────
    fun runFrame() {
        var cycles = 0
        while (cycles < CYCLES_PER_FRAME) {
            cycles += stepOnce()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Exécute un seul pas (une instruction CPU)
    // Retourne les T-cycles consommés
    // ─────────────────────────────────────────────────────────────────
    fun stepOnce(): Int {
        mmu.pollInterrupts()
        val cpuCycles = cpu.step()
        // En double vitesse GBC, PPU/Timer tournent à vitesse normale
        val ppuCycles = if (mmu.doubleSpeed) cpuCycles / 2 else cpuCycles
        timer.step(ppuCycles)
        ppu.step(ppuCycles)
        apu.step(ppuCycles)
        return cpuCycles
    }

    // ─────────────────────────────────────────────────────────────────
    // Reset complet
    // ─────────────────────────────────────────────────────────────────
    fun reset() {
        cpu.reset(cartridge.isGbc)
        mmu.reset()
        ppu.reset()
        timer.reset()
        joypad.reset()
        apu.reset()
        cartridge.mbc.reset()
    }

    // ─────────────────────────────────────────────────────────────────
    // Sauvegarde / Chargement d'état
    // ─────────────────────────────────────────────────────────────────
    fun saveState(): SaveState = SaveState(
        cpuState = cpu.saveState(),
        mmuState = mmu.saveState(),
        ppuState = ppu.saveState(),
        timerState = timer.saveState(),
        joypadState = joypad.saveState()
    )

    fun loadState(state: SaveState) {
        cpu.loadState(state.cpuState)
        mmu.loadState(state.mmuState)
        ppu.loadState(state.ppuState)
        timer.loadState(state.timerState)
        joypad.loadState(state.joypadState)
    }

    // ─────────────────────────────────────────────────────────────────
    // Contrôles joypad
    // ─────────────────────────────────────────────────────────────────
    fun setButton(button: Int, pressed: Boolean) {
        joypad.setButton(button, pressed)
    }
}
