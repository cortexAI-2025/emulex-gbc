package com.emulex.gbc

import com.emulex.gbc.cartridge.Cartridge
import com.emulex.gbc.core.Cpu
import com.emulex.gbc.core.GameBoy
import com.emulex.gbc.core.Mmu
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires du CPU Sharp LR35902.
 *
 * Les instructions sont placées en WRAM (0xC000+) qui est accessible
 * en lecture/écriture, permettant l'exécution de code arbitraire.
 */
class CpuTest {

    private lateinit var gameBoy: GameBoy
    private lateinit var cpu: Cpu
    private lateinit var mmu: Mmu

    // Adresse de base pour les instructions de test (WRAM)
    private val CODE_BASE = 0xC000

    private fun makeRom(): ByteArray {
        val rom = ByteArray(0x8000)
        rom[0x147] = 0x00; rom[0x148] = 0x00; rom[0x149] = 0x00
        return rom
    }

    // Charge des opcodes en WRAM et positionne le PC
    private fun load(vararg bytes: Int) {
        bytes.forEachIndexed { i, b -> mmu.write(CODE_BASE + i, b) }
        cpu.pc = CODE_BASE
    }

    @Before
    fun setUp() {
        gameBoy = GameBoy(Cartridge.load(makeRom()))
        cpu = gameBoy.cpu
        mmu = gameBoy.mmu
        cpu.pc = CODE_BASE
    }

    // ─────────────────────────────────────────────────────────────────
    // Flags
    // ─────────────────────────────────────────────────────────────────
    @Test fun testFlagZ() {
        cpu.flagZ = true; assertTrue(cpu.flagZ); assertEquals(0x80, cpu.f and 0x80)
        cpu.flagZ = false; assertFalse(cpu.flagZ); assertEquals(0, cpu.f and 0x80)
    }

    @Test fun testFlagN() { cpu.flagN = true; assertTrue(cpu.flagN); cpu.flagN = false; assertFalse(cpu.flagN) }
    @Test fun testFlagH() { cpu.flagH = true; assertTrue(cpu.flagH); cpu.flagH = false; assertFalse(cpu.flagH) }
    @Test fun testFlagC() { cpu.flagC = true; assertTrue(cpu.flagC); cpu.flagC = false; assertFalse(cpu.flagC) }

    @Test fun testFRegLowerNibbleAlwaysZero() {
        cpu.f = 0xFF
        assertEquals(0, cpu.f and 0x0F)
    }

    // ─────────────────────────────────────────────────────────────────
    // Paires de registres
    // ─────────────────────────────────────────────────────────────────
    @Test fun testRegisterPairs() {
        cpu.bc = 0x1234; assertEquals(0x12, cpu.b); assertEquals(0x34, cpu.c)
        cpu.de = 0xABCD; assertEquals(0xAB, cpu.d); assertEquals(0xCD, cpu.e)
        cpu.hl = 0xFF00; assertEquals(0xFF, cpu.h); assertEquals(0x00, cpu.l)
        cpu.af = 0x5600; assertEquals(0x56, cpu.a); assertEquals(0x00, cpu.f)
    }

    // ─────────────────────────────────────────────────────────────────
    // NOP (0x00) — 4 cycles
    // ─────────────────────────────────────────────────────────────────
    @Test fun testNop() {
        load(0x00)
        val cycles = cpu.step()
        assertEquals(4, cycles)
        assertEquals(CODE_BASE + 1, cpu.pc)
    }

    // ─────────────────────────────────────────────────────────────────
    // LD immediat
    // ─────────────────────────────────────────────────────────────────
    @Test fun testLdBImmediate() {
        load(0x06, 0x42)  // LD B, 0x42
        val cycles = cpu.step()
        assertEquals(8, cycles); assertEquals(0x42, cpu.b)
    }

    @Test fun testLdBCImmediate() {
        load(0x01, 0x34, 0x12)  // LD BC, 0x1234
        val cycles = cpu.step()
        assertEquals(12, cycles); assertEquals(0x1234, cpu.bc)
    }

    @Test fun testLdAImmediate() {
        load(0x3E, 0xFF)  // LD A, 0xFF
        cpu.step()
        assertEquals(0xFF, cpu.a)
    }

    // ─────────────────────────────────────────────────────────────────
    // LD r, r'
    // ─────────────────────────────────────────────────────────────────
    @Test fun testLdBFromC() {
        cpu.c = 0x55
        load(0x41)  // LD B, C
        val cycles = cpu.step()
        assertEquals(4, cycles); assertEquals(0x55, cpu.b)
    }

    @Test fun testLdAFromB() {
        cpu.b = 0xAB
        load(0x78)  // LD A, B
        cpu.step()
        assertEquals(0xAB, cpu.a)
    }

    // ─────────────────────────────────────────────────────────────────
    // ADD / ADC / SUB / SBC
    // ─────────────────────────────────────────────────────────────────
    @Test fun testAddAB() {
        cpu.a = 0x10; cpu.b = 0x20
        load(0x80)  // ADD A, B
        cpu.step()
        assertEquals(0x30, cpu.a); assertFalse(cpu.flagZ); assertFalse(cpu.flagC)
    }

    @Test fun testAddOverflow() {
        cpu.a = 0xFF; cpu.b = 0x01
        load(0x80)  // ADD A, B
        cpu.step()
        assertEquals(0x00, cpu.a); assertTrue(cpu.flagZ); assertTrue(cpu.flagC)
    }

    @Test fun testAddHalfCarry() {
        cpu.a = 0x0F; cpu.b = 0x01
        load(0x80)
        cpu.step()
        assertEquals(0x10, cpu.a); assertTrue(cpu.flagH); assertFalse(cpu.flagC)
    }

    @Test fun testAdcWithCarry() {
        cpu.a = 0x10; cpu.b = 0x10; cpu.flagC = true
        load(0x88)  // ADC A, B
        cpu.step()
        assertEquals(0x21, cpu.a)
    }

    @Test fun testSubFlags() {
        cpu.a = 0x05; cpu.b = 0x05
        load(0x90)  // SUB B
        cpu.step()
        assertEquals(0x00, cpu.a); assertTrue(cpu.flagZ); assertTrue(cpu.flagN)
    }

    @Test fun testSubBorrow() {
        cpu.a = 0x00; cpu.b = 0x01
        load(0x90)  // SUB B
        cpu.step()
        assertEquals(0xFF, cpu.a); assertTrue(cpu.flagC)
    }

    // ─────────────────────────────────────────────────────────────────
    // AND / OR / XOR / CP
    // ─────────────────────────────────────────────────────────────────
    @Test fun testAndFlags() {
        cpu.a = 0xF0; cpu.b = 0x0F
        load(0xA0)  // AND B
        cpu.step()
        assertEquals(0x00, cpu.a); assertTrue(cpu.flagZ); assertTrue(cpu.flagH)
    }

    @Test fun testOrFlags() {
        cpu.a = 0xF0; cpu.b = 0x0F
        load(0xB0)  // OR B
        cpu.step()
        assertEquals(0xFF, cpu.a); assertFalse(cpu.flagZ)
    }

    @Test fun testXorSelf() {
        cpu.a = 0x55
        load(0xAF)  // XOR A
        cpu.step()
        assertEquals(0x00, cpu.a); assertTrue(cpu.flagZ)
    }

    @Test fun testCpEqual() {
        cpu.a = 0x42; cpu.b = 0x42
        load(0xB8)  // CP B
        cpu.step()
        assertTrue(cpu.flagZ); assertEquals(0x42, cpu.a)  // A inchangé
    }

    // ─────────────────────────────────────────────────────────────────
    // INC / DEC
    // ─────────────────────────────────────────────────────────────────
    @Test fun testIncB() {
        cpu.b = 0x0F
        load(0x04)
        cpu.step()
        assertEquals(0x10, cpu.b); assertTrue(cpu.flagH); assertFalse(cpu.flagZ)
    }

    @Test fun testIncOverflow() {
        cpu.b = 0xFF
        load(0x04)
        cpu.step()
        assertEquals(0x00, cpu.b); assertTrue(cpu.flagZ)
    }

    @Test fun testDecB() {
        cpu.b = 0x10
        load(0x05)
        cpu.step()
        assertEquals(0x0F, cpu.b); assertTrue(cpu.flagN)
    }

    @Test fun testDecUnderflow() {
        cpu.b = 0x00
        load(0x05)
        cpu.step()
        assertEquals(0xFF, cpu.b); assertFalse(cpu.flagZ)
    }

    // ─────────────────────────────────────────────────────────────────
    // JR conditionnel
    // ─────────────────────────────────────────────────────────────────
    @Test fun testJrPositive() {
        load(0x18, 0x05)  // JR +5
        val cycles = cpu.step()
        assertEquals(12, cycles)
        assertEquals(CODE_BASE + 2 + 5, cpu.pc)
    }

    @Test fun testJrNegative() {
        load(0x18, 0xFE.toByte().toInt())  // JR -2 (boucle infinie)
        cpu.step()
        assertEquals(CODE_BASE, cpu.pc)
    }

    @Test fun testJrNzNotTaken() {
        cpu.flagZ = true
        load(0x20, 0x10)  // JR NZ, +16
        val cycles = cpu.step()
        assertEquals(8, cycles)  // non-pris = 8 cycles
        assertEquals(CODE_BASE + 2, cpu.pc)
    }

    @Test fun testJrNzTaken() {
        cpu.flagZ = false
        load(0x20, 0x04)  // JR NZ, +4
        val cycles = cpu.step()
        assertEquals(12, cycles)  // pris = 12 cycles
        assertEquals(CODE_BASE + 2 + 4, cpu.pc)
    }

    // ─────────────────────────────────────────────────────────────────
    // JP / CALL / RET
    // ─────────────────────────────────────────────────────────────────
    @Test fun testJpAbsolute() {
        load(0xC3, 0x00, 0xD0)  // JP 0xD000
        val cycles = cpu.step()
        assertEquals(16, cycles)
        assertEquals(0xD000, cpu.pc)
    }

    @Test fun testCallRet() {
        // CALL 0xD000
        load(0xCD, 0x00, 0xD0)
        cpu.sp = 0xFFFE
        cpu.step()
        assertEquals(0xD000, cpu.pc)
        // Vérifier que PC+3 est sur la pile
        val retAddr = mmu.read(0xFFFC) or (mmu.read(0xFFFD) shl 8)
        assertEquals(CODE_BASE + 3, retAddr)
        // RET
        mmu.write(0xD000, 0xC9)  // RET
        cpu.step()
        assertEquals(CODE_BASE + 3, cpu.pc)
    }

    // ─────────────────────────────────────────────────────────────────
    // PUSH / POP
    // ─────────────────────────────────────────────────────────────────
    @Test fun testPushPopBC() {
        cpu.bc = 0xABCD; cpu.sp = 0xFFFE
        load(0xC5, 0xC1)  // PUSH BC, POP BC
        cpu.step()  // PUSH BC
        val savedBC = cpu.bc
        cpu.bc = 0  // effacer
        cpu.step()  // POP BC
        assertEquals(savedBC, cpu.bc)
    }

    @Test fun testPushPopAF() {
        cpu.af = 0x1230  // A=0x12, F=0x30 (H et N forcés)
        cpu.sp = 0xFFFE
        load(0xF5, 0xF1)  // PUSH AF, POP AF
        cpu.step(); val savedAF = cpu.af; cpu.af = 0; cpu.step()
        assertEquals(savedAF, cpu.af)
    }

    // ─────────────────────────────────────────────────────────────────
    // RLCA / RRCA / RLA / RRA
    // ─────────────────────────────────────────────────────────────────
    @Test fun testRlca() {
        cpu.a = 0x85  // 1000 0101
        load(0x07)  // RLCA
        cpu.step()
        assertEquals(0x0B, cpu.a)  // 0000 1011
        assertTrue(cpu.flagC); assertFalse(cpu.flagZ)
    }

    @Test fun testRrca() {
        cpu.a = 0x01
        load(0x0F)  // RRCA
        cpu.step()
        assertEquals(0x80, cpu.a); assertTrue(cpu.flagC)
    }

    // ─────────────────────────────────────────────────────────────────
    // CPL / SCF / CCF
    // ─────────────────────────────────────────────────────────────────
    @Test fun testCpl() {
        cpu.a = 0x55
        load(0x2F)  // CPL
        cpu.step()
        assertEquals(0xAA, cpu.a); assertTrue(cpu.flagN); assertTrue(cpu.flagH)
    }

    @Test fun testScf() {
        cpu.flagC = false; cpu.flagN = true; cpu.flagH = true
        load(0x37)
        cpu.step()
        assertTrue(cpu.flagC); assertFalse(cpu.flagN); assertFalse(cpu.flagH)
    }

    @Test fun testCcf() {
        cpu.flagC = true
        load(0x3F)
        cpu.step()
        assertFalse(cpu.flagC)
    }

    // ─────────────────────────────────────────────────────────────────
    // DAA
    // ─────────────────────────────────────────────────────────────────
    @Test fun testDaa() {
        cpu.a = 0x09; cpu.b = 0x01
        load(0x80, 0x27)  // ADD A,B ; DAA
        cpu.step(); cpu.step()
        assertEquals(0x10, cpu.a)
    }

    // ─────────────────────────────────────────────────────────────────
    // CB-préfixés
    // ─────────────────────────────────────────────────────────────────
    @Test fun testCbRlcA() {
        cpu.a = 0x85
        load(0xCB, 0x07)  // RLC A
        val cycles = cpu.step()
        assertEquals(8, cycles)
        assertEquals(0x0B, cpu.a); assertTrue(cpu.flagC)
    }

    @Test fun testCbRlcHL() {
        cpu.hl = 0xD100; mmu.write(0xD100, 0x85)
        load(0xCB, 0x06)  // RLC (HL)
        val cycles = cpu.step()
        assertEquals(16, cycles)
        assertEquals(0x0B, mmu.read(0xD100))
    }

    @Test fun testCbBit3Set() {
        cpu.b = 0x08
        load(0xCB, 0x58)  // BIT 3, B
        cpu.step()
        assertFalse(cpu.flagZ)  // bit est à 1 → Z=0
    }

    @Test fun testCbBit3Clear() {
        cpu.b = 0x00
        load(0xCB, 0x58)  // BIT 3, B
        cpu.step()
        assertTrue(cpu.flagZ)  // bit est à 0 → Z=1
    }

    @Test fun testCbSetBit() {
        cpu.b = 0x00
        load(0xCB, 0xC0)  // SET 0, B
        cpu.step()
        assertEquals(0x01, cpu.b)
    }

    @Test fun testCbResBit() {
        cpu.b = 0xFF
        load(0xCB, 0x80)  // RES 0, B
        cpu.step()
        assertEquals(0xFE, cpu.b)
    }

    @Test fun testCbSwapA() {
        cpu.a = 0xAB
        load(0xCB, 0x37)  // SWAP A
        cpu.step()
        assertEquals(0xBA, cpu.a)
    }

    @Test fun testCbSrlA() {
        cpu.a = 0x80
        load(0xCB, 0x3F)  // SRL A
        cpu.step()
        assertEquals(0x40, cpu.a); assertFalse(cpu.flagC)
    }

    // ─────────────────────────────────────────────────────────────────
    // Timing
    // ─────────────────────────────────────────────────────────────────
    @Test fun testCycleAccounting() {
        val tests = listOf(
            listOf(0x00) to 4,           // NOP
            listOf(0x3E, 0x00) to 8,     // LD A, n
            listOf(0xC3, 0x00, 0xC0) to 16,  // JP nn (jump en WRAM)
        )
        for ((insn, expectedCycles) in tests) {
            load(*insn.toIntArray())
            assertEquals("Opcode 0x${insn[0].toString(16)}", expectedCycles, cpu.step())
            cpu.pc = CODE_BASE  // reset
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Save / Load state
    // ─────────────────────────────────────────────────────────────────
    @Test fun testSaveLoadState() {
        cpu.a = 0x42; cpu.b = 0x13; cpu.pc = 0xD000; cpu.sp = 0xFFF0
        cpu.flagZ = true; cpu.flagC = false
        val state = cpu.saveState()
        cpu.a = 0; cpu.b = 0; cpu.pc = CODE_BASE; cpu.sp = 0xFFFE
        cpu.loadState(state)
        assertEquals(0x42, cpu.a); assertEquals(0x13, cpu.b)
        assertEquals(0xD000, cpu.pc); assertEquals(0xFFF0, cpu.sp)
        assertTrue(cpu.flagZ); assertFalse(cpu.flagC)
    }

    // ─────────────────────────────────────────────────────────────────
    // HALT et interruptions
    // ─────────────────────────────────────────────────────────────────
    @Test fun testHalt() {
        load(0x76)  // HALT
        cpu.ime = true
        mmu.ie = 0x00; mmu.ifReg = 0x00  // Aucune interruption
        cpu.step()
        assertTrue(cpu.halted)
    }

    @Test fun testHaltExitOnInterrupt() {
        load(0x76)
        cpu.ime = true; mmu.ie = 0x01; mmu.ifReg = 0x01  // VBlank pending
        cpu.step()  // HALT + service interruption immédiate
        assertFalse(cpu.halted)
    }

    // ─────────────────────────────────────────────────────────────────
    // EI / DI
    // ─────────────────────────────────────────────────────────────────
    @Test fun testEiDelay() {
        cpu.ime = false
        load(0xFB, 0x00)  // EI, NOP
        cpu.step()  // EI — IME pas encore actif
        assertFalse(cpu.ime)  // pas encore activé (délai d'1 instruction)
        cpu.step()  // NOP — maintenant IME est activé
        assertTrue(cpu.ime)
    }

    @Test fun testDi() {
        cpu.ime = true
        load(0xF3)  // DI
        cpu.step()
        assertFalse(cpu.ime)
    }
}
