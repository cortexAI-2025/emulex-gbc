package com.emulex.gbc

import com.emulex.gbc.cartridge.Cartridge
import com.emulex.gbc.core.GameBoy
import com.emulex.gbc.core.Mmu
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires de la MMU — accès mémoire, WRAM, HRAM, registres I/O.
 */
class MmuTest {

    private lateinit var gameBoy: GameBoy
    private lateinit var mmu: Mmu

    private fun makeRom(): ByteArray {
        val rom = ByteArray(0x8000)
        rom[0x147] = 0x00
        rom[0x148] = 0x00
        rom[0x149] = 0x00
        // Données de test en ROM Bank 0
        for (i in 0 until 0x4000) rom[i] = (i and 0xFF).toByte()
        return rom
    }

    @Before
    fun setUp() {
        gameBoy = GameBoy(Cartridge.load(makeRom()))
        mmu = gameBoy.mmu
    }

    // ─────────────────────────────────────────────────────────────────
    // WRAM (0xC000–0xDFFF)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testWram0ReadWrite() {
        mmu.write(0xC000, 0xAB)
        assertEquals(0xAB, mmu.read(0xC000))
        mmu.write(0xCFFF, 0xCD)
        assertEquals(0xCD, mmu.read(0xCFFF))
    }

    @Test
    fun testWramBankDefault() {
        // WRAM bank 1 à 0xD000
        mmu.write(0xD000, 0x55)
        assertEquals(0x55, mmu.read(0xD000))
    }

    // ─────────────────────────────────────────────────────────────────
    // HRAM (0xFF80–0xFFFE)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testHramReadWrite() {
        mmu.write(0xFF80, 0x12)
        assertEquals(0x12, mmu.read(0xFF80))
        mmu.write(0xFFFE, 0x34)
        assertEquals(0x34, mmu.read(0xFFFE))
    }

    // ─────────────────────────────────────────────────────────────────
    // IE register (0xFFFF)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testIeRegister() {
        mmu.write(0xFFFF, 0x1F)
        assertEquals(0x1F, mmu.read(0xFFFF))
        mmu.ie = 0
        mmu.write(0xFFFF, 0x05)
        assertEquals(0x05, mmu.ie)
    }

    // ─────────────────────────────────────────────────────────────────
    // IF register (0xFF0F)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testIfRegister() {
        mmu.write(0xFF0F, 0x1F)
        assertEquals(0x1F, mmu.ifReg)
        // Lecture retourne bits 7-5 forcés à 1
        val read = mmu.read(0xFF0F)
        assertEquals(0xFF, read and 0xFF)
    }

    // ─────────────────────────────────────────────────────────────────
    // Echo RAM (0xE000–0xFDFF)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testEchoRam() {
        mmu.write(0xC100, 0x77)
        assertEquals(0x77, mmu.read(0xE100))  // Echo de 0xC100
        mmu.write(0xE200, 0x99)
        assertEquals(0x99, mmu.read(0xC200))  // Miroir inverse
    }

    // ─────────────────────────────────────────────────────────────────
    // Registres Timer (0xFF04–0xFF07)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testTimerDivReset() {
        // Écriture sur DIV (0xFF04) remet le compteur à 0
        gameBoy.timer.internalCounter = 0x1234
        mmu.write(0xFF04, 0x00)
        assertEquals(0, gameBoy.timer.internalCounter)
    }

    @Test
    fun testTimaReadWrite() {
        mmu.write(0xFF05, 0x42)
        assertEquals(0x42, gameBoy.timer.tima)
        assertEquals(0x42, mmu.read(0xFF05))
    }

    // ─────────────────────────────────────────────────────────────────
    // ROM lecture seule
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testRomIsReadOnly() {
        val before = mmu.read(0x1000)
        mmu.write(0x1000, (before xor 0xFF))  // tentative d'écriture
        assertEquals(before, mmu.read(0x1000))  // ROM inchangée
    }

    // ─────────────────────────────────────────────────────────────────
    // Interruption en attente
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testInterruptPending() {
        mmu.ie = 0x01; mmu.ifReg = 0x01
        assertTrue(mmu.interruptPending())
        mmu.ifReg = 0x00
        assertFalse(mmu.interruptPending())
    }

    // ─────────────────────────────────────────────────────────────────
    // Zone non utilisée (0xFEA0–0xFEFF)
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testUnusableArea() {
        // Les lectures retournent 0xFF, les écritures sont ignorées
        mmu.write(0xFEA0, 0x42)
        assertEquals(0xFF, mmu.read(0xFEA0))
    }

    // ─────────────────────────────────────────────────────────────────
    // Test reset
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun testReset() {
        mmu.write(0xC000, 0xAB)
        mmu.write(0xFF80, 0xCD)
        mmu.reset()
        assertEquals(0x00, mmu.read(0xC000))
        assertEquals(0x00, mmu.read(0xFF80))
    }
}
