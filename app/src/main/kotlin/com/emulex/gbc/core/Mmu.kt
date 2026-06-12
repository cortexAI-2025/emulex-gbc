package com.emulex.gbc.core

import com.emulex.gbc.cartridge.Cartridge

/**
 * Memory Management Unit du Game Boy Color.
 *
 * Carte mémoire :
 *  0x0000–0x3FFF : ROM Bank 0
 *  0x4000–0x7FFF : ROM Bank N (switchable via MBC)
 *  0x8000–0x9FFF : VRAM (2 banks GBC)
 *  0xA000–0xBFFF : RAM cartouche externe (MBC)
 *  0xC000–0xCFFF : WRAM Bank 0
 *  0xD000–0xDFFF : WRAM Bank 1–7 (GBC)
 *  0xE000–0xFDFF : Echo RAM (miroir 0xC000–0xDDFF)
 *  0xFE00–0xFE9F : OAM
 *  0xFEA0–0xFEFF : Non utilisé
 *  0xFF00–0xFF7F : Registres I/O
 *  0xFF80–0xFFFE : HRAM
 *  0xFFFF        : IE (Interrupt Enable)
 */
class Mmu(
    val cartridge: Cartridge,
    val ppu: Ppu,
    val apu: Apu,
    val timer: Timer,
    val joypad: Joypad
) {
    val gbcMode: Boolean = cartridge.isGbc

    // ── WRAM (8 Ko bank 0 + 7×4 Ko banks 1–7 pour GBC) ──────────────
    private val wram0 = ByteArray(0x1000)
    private val wramBanks = Array(7) { ByteArray(0x1000) }  // banks 1–7
    private var wramBankNum = 1  // registre SVBK (GBC)

    // ── HRAM (127 octets) ─────────────────────────────────────────────
    val hram = ByteArray(0x7F)

    // ── Registres d'interruptions ─────────────────────────────────────
    var ie = 0x00   // 0xFFFF : Interrupt Enable
    var ifReg = 0xE1  // 0xFF0F : Interrupt Flag

    // ── GBC : double vitesse ──────────────────────────────────────────
    var key1 = 0x00  // 0xFF4D
    var doubleSpeed = false

    // ── Registre serial (simplifié) ───────────────────────────────────
    private var sb = 0x00  // 0xFF01
    private var sc = 0x7E  // 0xFF02

    // ─────────────────────────────────────────────────────────────────
    // Lecture principale
    // ─────────────────────────────────────────────────────────────────
    fun read(addr: Int): Int {
        val a = addr and 0xFFFF
        return when {
            a < 0x8000 -> cartridge.read(a)
            a < 0xA000 -> ppu.readVram(a)
            a < 0xC000 -> cartridge.read(a)
            a < 0xD000 -> wram0[a - 0xC000].toInt() and 0xFF
            a < 0xE000 -> {
                val bank = if (gbcMode) maxOf(wramBankNum, 1) - 1 else 0
                wramBanks[bank][a - 0xD000].toInt() and 0xFF
            }
            a < 0xFE00 -> read(a - 0x2000)  // Echo RAM
            a < 0xFEA0 -> ppu.readOam(a)
            a < 0xFF00 -> 0xFF  // Zone non utilisée
            a < 0xFF80 -> readIo(a)
            a < 0xFFFF -> hram[a - 0xFF80].toInt() and 0xFF
            else -> ie
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Écriture principale
    // ─────────────────────────────────────────────────────────────────
    fun write(addr: Int, value: Int) {
        val a = addr and 0xFFFF
        val v = value and 0xFF
        when {
            a < 0x8000 -> cartridge.write(a, v)
            a < 0xA000 -> ppu.writeVram(a, v)
            a < 0xC000 -> cartridge.write(a, v)
            a < 0xD000 -> wram0[a - 0xC000] = v.toByte()
            a < 0xE000 -> {
                val bank = if (gbcMode) maxOf(wramBankNum, 1) - 1 else 0
                wramBanks[bank][a - 0xD000] = v.toByte()
            }
            a < 0xFE00 -> write(a - 0x2000, v)  // Echo RAM
            a < 0xFEA0 -> ppu.writeOam(a, v)
            a < 0xFF00 -> Unit  // Zone non utilisée
            a < 0xFF80 -> writeIo(a, v)
            a < 0xFFFF -> hram[a - 0xFF80] = v.toByte()
            else -> ie = v
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Accès DMA (non bloqué par PPU mode)
    // ─────────────────────────────────────────────────────────────────
    fun readDma(addr: Int): Int {
        val a = addr and 0xFFFF
        return when {
            a < 0x8000 -> cartridge.read(a)
            a < 0xA000 -> {
                val bank = if (gbcMode) ppu.vramBank else 0
                ppu.vram[bank][(a - 0x8000)].toInt() and 0xFF
            }
            a < 0xC000 -> cartridge.read(a)
            a < 0xD000 -> wram0[a - 0xC000].toInt() and 0xFF
            a < 0xE000 -> {
                val bank = if (gbcMode) maxOf(wramBankNum, 1) - 1 else 0
                wramBanks[bank][a - 0xD000].toInt() and 0xFF
            }
            else -> 0xFF
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // I/O registers (0xFF00–0xFF7F)
    // ─────────────────────────────────────────────────────────────────
    private fun readIo(addr: Int): Int = when (addr) {
        0xFF00 -> joypad.read(addr)
        0xFF01 -> sb
        0xFF02 -> sc
        0xFF04, 0xFF05, 0xFF06, 0xFF07 -> timer.read(addr)
        0xFF0F -> ifReg or 0xE0
        in 0xFF10..0xFF3F -> apu.read(addr)
        in 0xFF40..0xFF4B -> ppu.readReg(addr)
        0xFF4D -> if (gbcMode) key1 or (if (doubleSpeed) 0x80 else 0) else 0xFF
        0xFF4F -> ppu.readReg(addr)
        in 0xFF51..0xFF55 -> ppu.readReg(addr)
        in 0xFF68..0xFF6B -> ppu.readReg(addr)
        0xFF70 -> if (gbcMode) wramBankNum or 0xF8 else 0xFF
        else -> 0xFF
    }

    private fun writeIo(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            0xFF00 -> joypad.write(addr, v)
            0xFF01 -> sb = v
            0xFF02 -> {
                sc = v
                // Serial simple : si transfert demandé, répondre 0xFF
                if ((v and 0x80) != 0 && (v and 0x01) != 0) {
                    sb = 0xFF
                    ifReg = ifReg or 0x08  // serial interrupt
                    sc = sc and 0x7F
                }
            }
            0xFF04, 0xFF05, 0xFF06, 0xFF07 -> timer.write(addr, v)
            0xFF0F -> ifReg = v and 0x1F
            in 0xFF10..0xFF3F -> apu.write(addr, v)
            in 0xFF40..0xFF4B -> ppu.writeReg(addr, v)
            0xFF4D -> if (gbcMode) key1 = (key1 and 0xFE) or (v and 0x01)
            0xFF4F -> ppu.writeReg(addr, v)
            in 0xFF51..0xFF55 -> ppu.writeReg(addr, v)
            in 0xFF68..0xFF6B -> ppu.writeReg(addr, v)
            0xFF70 -> if (gbcMode) wramBankNum = v and 0x07
            // 0xFF50 : désactivation boot ROM (ignorée, pas de boot ROM)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GBC : basculement double vitesse (via STOP)
    // ─────────────────────────────────────────────────────────────────
    fun toggleDoubleSpeed() {
        doubleSpeed = !doubleSpeed
        key1 = key1 and 0x7E  // effacer bit 0 (demande) et mettre à jour bit 7
    }

    // ─────────────────────────────────────────────────────────────────
    // Vérifie si une interruption est en attente (pour HALT)
    // ─────────────────────────────────────────────────────────────────
    fun interruptPending(): Boolean = (ie and ifReg and 0x1F) != 0

    // ─────────────────────────────────────────────────────────────────
    // Collecte les interruptions des périphériques
    // ─────────────────────────────────────────────────────────────────
    fun pollInterrupts() {
        if (timer.interruptRequested) {
            ifReg = ifReg or 0x04
            timer.interruptRequested = false
        }
        if (joypad.interruptRequested) {
            ifReg = ifReg or 0x10
            joypad.interruptRequested = false
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────────────────────────
    fun reset() {
        wram0.fill(0)
        wramBanks.forEach { it.fill(0) }
        hram.fill(0)
        ie = 0x00; ifReg = 0xE1
        key1 = 0x00; doubleSpeed = false
        sb = 0x00; sc = 0x7E; wramBankNum = 1
    }

    fun saveState(): MmuState = MmuState(
        wram0.copyOf(), wramBanks.map { it.copyOf() }.toTypedArray(),
        hram.copyOf(), ie, ifReg, key1, doubleSpeed, wramBankNum, sb, sc
    )

    fun loadState(s: MmuState) {
        s.wram0.copyInto(wram0)
        s.wramBanks.forEachIndexed { i, b -> b.copyInto(wramBanks[i]) }
        s.hram.copyInto(hram)
        ie = s.ie; ifReg = s.ifReg; key1 = s.key1; doubleSpeed = s.doubleSpeed
        wramBankNum = s.wramBankNum; sb = s.sb; sc = s.sc
    }
}

data class MmuState(
    val wram0: ByteArray,
    val wramBanks: Array<ByteArray>,
    val hram: ByteArray,
    val ie: Int, val ifReg: Int, val key1: Int,
    val doubleSpeed: Boolean, val wramBankNum: Int,
    val sb: Int, val sc: Int
) : java.io.Serializable
