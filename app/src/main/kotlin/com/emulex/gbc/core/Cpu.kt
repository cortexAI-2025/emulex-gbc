package com.emulex.gbc.core

/**
 * Sharp LR35902 — processeur hybride Z80/8080 du Game Boy (Color).
 * Fréquence : 4.194304 MHz (mode normal) / 8.388608 MHz (double vitesse GBC).
 * Retourne des T-cycles (multiples de 4).
 */
@Suppress("NOTHING_TO_INLINE")
class Cpu(private val mmu: Mmu) {

    // ── Registres 8 bits ──────────────────────────────────────────────────
    var a = 0x11  // GBC : A=0x11, DMG : A=0x01
    var b = 0x00; var c = 0x00
    var d = 0xFF; var e = 0x56
    var h = 0x00; var l = 0x0D
    var f = 0x80  // Flags : Z=1, N=0, H=0, C=0

    // ── Registres 16 bits ─────────────────────────────────────────────────
    var sp = 0xFFFE
    var pc = 0x0100

    // ── Flags (F bits 7-4) ────────────────────────────────────────────────
    inline var flagZ: Boolean
        get() = (f and 0x80) != 0
        set(v) { f = if (v) f or 0x80 else f and 0x7F }

    inline var flagN: Boolean
        get() = (f and 0x40) != 0
        set(v) { f = if (v) f or 0x40 else f and 0xBF }

    inline var flagH: Boolean
        get() = (f and 0x20) != 0
        set(v) { f = if (v) f or 0x20 else f and 0xDF }

    inline var flagC: Boolean
        get() = (f and 0x10) != 0
        set(v) { f = if (v) f or 0x10 else f and 0xEF }

    // ── Paires de registres ───────────────────────────────────────────────
    inline var af: Int
        get() = (a shl 8) or (f and 0xF0)
        set(v) { a = (v shr 8) and 0xFF; f = v and 0xF0 }

    inline var bc: Int
        get() = (b shl 8) or c
        set(v) { b = (v shr 8) and 0xFF; c = v and 0xFF }

    inline var de: Int
        get() = (d shl 8) or e
        set(v) { d = (v shr 8) and 0xFF; e = v and 0xFF }

    inline var hl: Int
        get() = (h shl 8) or l
        set(v) { h = (v shr 8) and 0xFF; l = v and 0xFF }

    // ── État d'interruptions ──────────────────────────────────────────────
    var ime: Boolean = false
    private var imeScheduled: Boolean = false
    var halted: Boolean = false
    var haltBug: Boolean = false  // HALT bug : PC ne s'incrémente pas

    // ── Compteur de cycles (T-cycles cumulés) ─────────────────────────────
    var totalCycles: Long = 0L

    // ─────────────────────────────────────────────────────────────────────
    // Étape principale : exécute une instruction, retourne T-cycles
    // ─────────────────────────────────────────────────────────────────────
    fun step(): Int {
        // Activer IME si EI a été exécuté à l'instruction précédente
        if (imeScheduled) { ime = true; imeScheduled = false }

        // Vérifier interruptions (hors ou dans HALT)
        val irqCycles = handleInterrupts()
        if (irqCycles > 0) { totalCycles += irqCycles; return irqCycles }

        if (halted) { totalCycles += 4; return 4 }

        val opcode = fetchByte()
        // HALT bug : si halt activé avec bug, le byte suivant est lu deux fois
        if (haltBug) { pc = (pc - 1) and 0xFFFF; haltBug = false }

        val cycles = execute(opcode)
        totalCycles += cycles
        return cycles
    }

    // ─────────────────────────────────────────────────────────────────────
    // Gestion des interruptions
    // ─────────────────────────────────────────────────────────────────────
    private fun handleInterrupts(): Int {
        val ie = mmu.ie
        val ifReg = mmu.ifReg
        val pending = ie and ifReg and 0x1F

        if (pending == 0) return 0

        // Sortir du HALT même si IME est désactivé
        if (halted) { halted = false }

        if (!ime) return 0

        ime = false
        for (i in 0..4) {
            if (pending and (1 shl i) != 0) {
                mmu.ifReg = ifReg and (1 shl i).inv() and 0xFF
                push16(pc)
                pc = 0x40 + i * 8
                return 20
            }
        }
        return 0
    }

    // ─────────────────────────────────────────────────────────────────────
    // Accès mémoire et pile
    // ─────────────────────────────────────────────────────────────────────
    private fun fetchByte(): Int {
        val v = mmu.read(pc)
        pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun fetchWord(): Int {
        val lo = fetchByte()
        val hi = fetchByte()
        return (hi shl 8) or lo
    }

    private fun push16(value: Int) {
        sp = (sp - 1) and 0xFFFF
        mmu.write(sp, (value shr 8) and 0xFF)
        sp = (sp - 1) and 0xFFFF
        mmu.write(sp, value and 0xFF)
    }

    private fun pop16(): Int {
        val lo = mmu.read(sp)
        sp = (sp + 1) and 0xFFFF
        val hi = mmu.read(sp)
        sp = (sp + 1) and 0xFFFF
        return (hi shl 8) or lo
    }

    // ─────────────────────────────────────────────────────────────────────
    // Accès registre par index (0=B,1=C,2=D,3=E,4=H,5=L,6=(HL),7=A)
    // ─────────────────────────────────────────────────────────────────────
    private fun regGet(r: Int): Int = when (r) {
        0 -> b; 1 -> c; 2 -> d; 3 -> e; 4 -> h; 5 -> l
        6 -> mmu.read(hl)
        7 -> a
        else -> 0
    }

    private fun regSet(r: Int, v: Int) {
        val vv = v and 0xFF
        when (r) {
            0 -> b = vv; 1 -> c = vv; 2 -> d = vv; 3 -> e = vv
            4 -> h = vv; 5 -> l = vv
            6 -> mmu.write(hl, vv)
            7 -> a = vv
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Opérations ALU
    // ─────────────────────────────────────────────────────────────────────
    private fun aluAdd(n: Int) {
        val result = a + n
        flagZ = (result and 0xFF) == 0
        flagN = false
        flagH = ((a and 0x0F) + (n and 0x0F)) > 0x0F
        flagC = result > 0xFF
        a = result and 0xFF
    }

    private fun aluAdc(n: Int) {
        val c = if (flagC) 1 else 0
        val result = a + n + c
        flagZ = (result and 0xFF) == 0
        flagN = false
        flagH = ((a and 0x0F) + (n and 0x0F) + c) > 0x0F
        flagC = result > 0xFF
        a = result and 0xFF
    }

    private fun aluSub(n: Int) {
        val result = a - n
        flagZ = (result and 0xFF) == 0
        flagN = true
        flagH = (a and 0x0F) < (n and 0x0F)
        flagC = result < 0
        a = result and 0xFF
    }

    private fun aluSbc(n: Int) {
        val c = if (flagC) 1 else 0
        val result = a - n - c
        flagZ = (result and 0xFF) == 0
        flagN = true
        flagH = (a and 0x0F) < ((n and 0x0F) + c)
        flagC = result < 0
        a = result and 0xFF
    }

    private fun aluAnd(n: Int) {
        a = a and n
        flagZ = a == 0; flagN = false; flagH = true; flagC = false
    }

    private fun aluXor(n: Int) {
        a = a xor n
        flagZ = a == 0; flagN = false; flagH = false; flagC = false
    }

    private fun aluOr(n: Int) {
        a = a or n
        flagZ = a == 0; flagN = false; flagH = false; flagC = false
    }

    private fun aluCp(n: Int) {
        val result = a - n
        flagZ = (result and 0xFF) == 0
        flagN = true
        flagH = (a and 0x0F) < (n and 0x0F)
        flagC = result < 0
    }

    private fun aluInc(n: Int): Int {
        val result = (n + 1) and 0xFF
        flagZ = result == 0; flagN = false; flagH = (n and 0x0F) == 0x0F
        return result
    }

    private fun aluDec(n: Int): Int {
        val result = (n - 1) and 0xFF
        flagZ = result == 0; flagN = true; flagH = (n and 0x0F) == 0x00
        return result
    }

    private fun aluAddHL(n: Int) {
        val h16 = hl
        val result = h16 + n
        flagN = false
        flagH = ((h16 and 0x0FFF) + (n and 0x0FFF)) > 0x0FFF
        flagC = result > 0xFFFF
        hl = result and 0xFFFF
    }

    // ─────────────────────────────────────────────────────────────────────
    // Opérations CB
    // ─────────────────────────────────────────────────────────────────────
    private fun cbRlc(n: Int): Int {
        val result = ((n shl 1) or (n shr 7)) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x80) != 0
        return result
    }

    private fun cbRrc(n: Int): Int {
        val result = ((n shr 1) or (n shl 7)) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x01) != 0
        return result
    }

    private fun cbRl(n: Int): Int {
        val cin = if (flagC) 1 else 0
        val result = ((n shl 1) or cin) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x80) != 0
        return result
    }

    private fun cbRr(n: Int): Int {
        val cin = if (flagC) 0x80 else 0
        val result = ((n shr 1) or cin) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x01) != 0
        return result
    }

    private fun cbSla(n: Int): Int {
        val result = (n shl 1) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x80) != 0
        return result
    }

    private fun cbSra(n: Int): Int {
        val result = ((n shr 1) or (n and 0x80)) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x01) != 0
        return result
    }

    private fun cbSwap(n: Int): Int {
        val result = ((n and 0x0F) shl 4) or ((n shr 4) and 0x0F)
        flagZ = result == 0; flagN = false; flagH = false; flagC = false
        return result
    }

    private fun cbSrl(n: Int): Int {
        val result = (n shr 1) and 0xFF
        flagZ = result == 0; flagN = false; flagH = false; flagC = (n and 0x01) != 0
        return result
    }

    // ─────────────────────────────────────────────────────────────────────
    // Décodage du préfixe CB
    // ─────────────────────────────────────────────────────────────────────
    private fun executeCB(): Int {
        val op = fetchByte()
        val reg = op and 0x07
        val isHL = reg == 6
        val val8 = regGet(reg)
        val cycles: Int

        when (op shr 6) {
            0 -> {
                // Rotations / Shifts
                val result = when ((op shr 3) and 0x07) {
                    0 -> cbRlc(val8); 1 -> cbRrc(val8)
                    2 -> cbRl(val8);  3 -> cbRr(val8)
                    4 -> cbSla(val8); 5 -> cbSra(val8)
                    6 -> cbSwap(val8); else -> cbSrl(val8)
                }
                regSet(reg, result)
                cycles = if (isHL) 16 else 8
            }
            1 -> {
                // BIT
                val bit = (op shr 3) and 0x07
                flagZ = (val8 and (1 shl bit)) == 0
                flagN = false; flagH = true
                cycles = if (isHL) 12 else 8
            }
            2 -> {
                // RES
                val bit = (op shr 3) and 0x07
                regSet(reg, val8 and (1 shl bit).inv())
                cycles = if (isHL) 16 else 8
            }
            else -> {
                // SET
                val bit = (op shr 3) and 0x07
                regSet(reg, val8 or (1 shl bit))
                cycles = if (isHL) 16 else 8
            }
        }
        return cycles
    }

    // ─────────────────────────────────────────────────────────────────────
    // Décodage principal — 256 opcodes
    // ─────────────────────────────────────────────────────────────────────
    private fun execute(op: Int): Int = when (op) {

        // ── NOP ────────────────────────────────────────────────────────
        0x00 -> 4

        // ── LD rr, nn ──────────────────────────────────────────────────
        0x01 -> { bc = fetchWord(); 12 }
        0x11 -> { de = fetchWord(); 12 }
        0x21 -> { hl = fetchWord(); 12 }
        0x31 -> { sp = fetchWord(); 12 }

        // ── LD (rr), A ─────────────────────────────────────────────────
        0x02 -> { mmu.write(bc, a); 8 }
        0x12 -> { mmu.write(de, a); 8 }
        0x22 -> { mmu.write(hl, a); hl = (hl + 1) and 0xFFFF; 8 }
        0x32 -> { mmu.write(hl, a); hl = (hl - 1) and 0xFFFF; 8 }

        // ── INC rr ─────────────────────────────────────────────────────
        0x03 -> { bc = (bc + 1) and 0xFFFF; 8 }
        0x13 -> { de = (de + 1) and 0xFFFF; 8 }
        0x23 -> { hl = (hl + 1) and 0xFFFF; 8 }
        0x33 -> { sp = (sp + 1) and 0xFFFF; 8 }

        // ── INC r ──────────────────────────────────────────────────────
        0x04 -> { b = aluInc(b); 4 }
        0x0C -> { c = aluInc(c); 4 }
        0x14 -> { d = aluInc(d); 4 }
        0x1C -> { e = aluInc(e); 4 }
        0x24 -> { h = aluInc(h); 4 }
        0x2C -> { l = aluInc(l); 4 }
        0x34 -> { mmu.write(hl, aluInc(mmu.read(hl))); 12 }
        0x3C -> { a = aluInc(a); 4 }

        // ── DEC r ──────────────────────────────────────────────────────
        0x05 -> { b = aluDec(b); 4 }
        0x0D -> { c = aluDec(c); 4 }
        0x15 -> { d = aluDec(d); 4 }
        0x1D -> { e = aluDec(e); 4 }
        0x25 -> { h = aluDec(h); 4 }
        0x2D -> { l = aluDec(l); 4 }
        0x35 -> { mmu.write(hl, aluDec(mmu.read(hl))); 12 }
        0x3D -> { a = aluDec(a); 4 }

        // ── LD r, n ────────────────────────────────────────────────────
        0x06 -> { b = fetchByte(); 8 }
        0x0E -> { c = fetchByte(); 8 }
        0x16 -> { d = fetchByte(); 8 }
        0x1E -> { e = fetchByte(); 8 }
        0x26 -> { h = fetchByte(); 8 }
        0x2E -> { l = fetchByte(); 8 }
        0x36 -> { mmu.write(hl, fetchByte()); 12 }
        0x3E -> { a = fetchByte(); 8 }

        // ── Rotations A ────────────────────────────────────────────────
        0x07 -> { // RLCA
            flagC = (a and 0x80) != 0
            a = ((a shl 1) or (a shr 7)) and 0xFF
            flagZ = false; flagN = false; flagH = false; 4
        }
        0x0F -> { // RRCA
            flagC = (a and 0x01) != 0
            a = ((a shr 1) or (a shl 7)) and 0xFF
            flagZ = false; flagN = false; flagH = false; 4
        }
        0x17 -> { // RLA
            val c = if (flagC) 1 else 0
            flagC = (a and 0x80) != 0
            a = ((a shl 1) or c) and 0xFF
            flagZ = false; flagN = false; flagH = false; 4
        }
        0x1F -> { // RRA
            val c = if (flagC) 0x80 else 0
            flagC = (a and 0x01) != 0
            a = ((a shr 1) or c) and 0xFF
            flagZ = false; flagN = false; flagH = false; 4
        }

        // ── LD (nn), SP ────────────────────────────────────────────────
        0x08 -> {
            val addr = fetchWord()
            mmu.write(addr, sp and 0xFF)
            mmu.write((addr + 1) and 0xFFFF, sp shr 8)
            20
        }

        // ── ADD HL, rr ─────────────────────────────────────────────────
        0x09 -> { aluAddHL(bc); 8 }
        0x19 -> { aluAddHL(de); 8 }
        0x29 -> { aluAddHL(hl); 8 }
        0x39 -> { aluAddHL(sp); 8 }

        // ── LD A, (rr) ─────────────────────────────────────────────────
        0x0A -> { a = mmu.read(bc); 8 }
        0x1A -> { a = mmu.read(de); 8 }
        0x2A -> { a = mmu.read(hl); hl = (hl + 1) and 0xFFFF; 8 }
        0x3A -> { a = mmu.read(hl); hl = (hl - 1) and 0xFFFF; 8 }

        // ── DEC rr ─────────────────────────────────────────────────────
        0x0B -> { bc = (bc - 1) and 0xFFFF; 8 }
        0x1B -> { de = (de - 1) and 0xFFFF; 8 }
        0x2B -> { hl = (hl - 1) and 0xFFFF; 8 }
        0x3B -> { sp = (sp - 1) and 0xFFFF; 8 }

        // ── STOP ───────────────────────────────────────────────────────
        0x10 -> {
            // GBC : si KEY1 demande changement de vitesse
            if (mmu.gbcMode && (mmu.key1 and 0x01) != 0) {
                mmu.toggleDoubleSpeed()
            } else {
                fetchByte() // consomme le 0x00 suivant
            }
            4
        }

        // ── JR n ───────────────────────────────────────────────────────
        0x18 -> { val n = fetchByte().toByte().toInt(); pc = (pc + n) and 0xFFFF; 12 }
        0x20 -> { // JR NZ
            val n = fetchByte().toByte().toInt()
            if (!flagZ) { pc = (pc + n) and 0xFFFF; 12 } else 8
        }
        0x28 -> { // JR Z
            val n = fetchByte().toByte().toInt()
            if (flagZ) { pc = (pc + n) and 0xFFFF; 12 } else 8
        }
        0x30 -> { // JR NC
            val n = fetchByte().toByte().toInt()
            if (!flagC) { pc = (pc + n) and 0xFFFF; 12 } else 8
        }
        0x38 -> { // JR C
            val n = fetchByte().toByte().toInt()
            if (flagC) { pc = (pc + n) and 0xFFFF; 12 } else 8
        }

        // ── DAA ────────────────────────────────────────────────────────
        0x27 -> {
            if (!flagN) {
                if (flagC || a > 0x99) { a += 0x60; flagC = true }
                if (flagH || (a and 0x0F) > 0x09) { a += 0x06 }
            } else {
                if (flagC) { a -= 0x60 }
                if (flagH) { a -= 0x06 }
            }
            a = a and 0xFF
            flagZ = a == 0; flagH = false; 4
        }

        // ── CPL ────────────────────────────────────────────────────────
        0x2F -> { a = a.inv() and 0xFF; flagN = true; flagH = true; 4 }

        // ── SCF / CCF ──────────────────────────────────────────────────
        0x37 -> { flagN = false; flagH = false; flagC = true; 4 }
        0x3F -> { flagN = false; flagH = false; flagC = !flagC; 4 }

        // ── LD r, r' (0x40–0x7F) ──────────────────────────────────────
        in 0x40..0x75, in 0x77..0x7F -> {
            val dst = (op shr 3) and 0x07
            val src = op and 0x07
            val v = regGet(src)
            regSet(dst, v)
            if (dst == 6 || src == 6) 8 else 4
        }

        // ── HALT ───────────────────────────────────────────────────────
        0x76 -> {
            val ie = mmu.ie
            val ifReg = mmu.ifReg
            if (!ime && (ie and ifReg and 0x1F) != 0) {
                haltBug = true  // HALT bug
            } else {
                halted = true
            }
            4
        }

        // ── ALU A, r (0x80–0xBF) ──────────────────────────────────────
        in 0x80..0x87 -> { aluAdd(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0x88..0x8F -> { aluAdc(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0x90..0x97 -> { aluSub(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0x98..0x9F -> { aluSbc(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0xA0..0xA7 -> { aluAnd(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0xA8..0xAF -> { aluXor(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0xB0..0xB7 -> { aluOr(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }
        in 0xB8..0xBF -> { aluCp(regGet(op and 7)); if (op and 7 == 6) 8 else 4 }

        // ── RET cc / POP / PUSH / CALL / RST ──────────────────────────
        0xC0 -> if (!flagZ) { pc = pop16(); 20 } else 8
        0xC8 -> if (flagZ) { pc = pop16(); 20 } else 8
        0xD0 -> if (!flagC) { pc = pop16(); 20 } else 8
        0xD8 -> if (flagC) { pc = pop16(); 20 } else 8

        0xC1 -> { bc = pop16(); 12 }
        0xD1 -> { de = pop16(); 12 }
        0xE1 -> { hl = pop16(); 12 }
        0xF1 -> { af = pop16(); 12 }

        0xC5 -> { push16(bc); 16 }
        0xD5 -> { push16(de); 16 }
        0xE5 -> { push16(hl); 16 }
        0xF5 -> { push16(af); 16 }

        0xC2 -> { val nn = fetchWord(); if (!flagZ) { pc = nn; 16 } else 12 }
        0xCA -> { val nn = fetchWord(); if (flagZ) { pc = nn; 16 } else 12 }
        0xD2 -> { val nn = fetchWord(); if (!flagC) { pc = nn; 16 } else 12 }
        0xDA -> { val nn = fetchWord(); if (flagC) { pc = nn; 16 } else 12 }

        0xC3 -> { pc = fetchWord(); 16 }

        0xC4 -> { val nn = fetchWord(); if (!flagZ) { push16(pc); pc = nn; 24 } else 12 }
        0xCC -> { val nn = fetchWord(); if (flagZ) { push16(pc); pc = nn; 24 } else 12 }
        0xD4 -> { val nn = fetchWord(); if (!flagC) { push16(pc); pc = nn; 24 } else 12 }
        0xDC -> { val nn = fetchWord(); if (flagC) { push16(pc); pc = nn; 24 } else 12 }
        0xCD -> { val nn = fetchWord(); push16(pc); pc = nn; 24 }

        0xC9 -> { pc = pop16(); 16 }
        0xD9 -> { pc = pop16(); ime = true; 16 }  // RETI

        // ── RST ────────────────────────────────────────────────────────
        0xC7 -> { push16(pc); pc = 0x00; 16 }
        0xCF -> { push16(pc); pc = 0x08; 16 }
        0xD7 -> { push16(pc); pc = 0x10; 16 }
        0xDF -> { push16(pc); pc = 0x18; 16 }
        0xE7 -> { push16(pc); pc = 0x20; 16 }
        0xEF -> { push16(pc); pc = 0x28; 16 }
        0xF7 -> { push16(pc); pc = 0x30; 16 }
        0xFF -> { push16(pc); pc = 0x38; 16 }

        // ── ALU A, n ───────────────────────────────────────────────────
        0xC6 -> { aluAdd(fetchByte()); 8 }
        0xCE -> { aluAdc(fetchByte()); 8 }
        0xD6 -> { aluSub(fetchByte()); 8 }
        0xDE -> { aluSbc(fetchByte()); 8 }
        0xE6 -> { aluAnd(fetchByte()); 8 }
        0xEE -> { aluXor(fetchByte()); 8 }
        0xF6 -> { aluOr(fetchByte()); 8 }
        0xFE -> { aluCp(fetchByte()); 8 }

        // ── Préfixe CB ─────────────────────────────────────────────────
        0xCB -> executeCB()

        // ── LDH ────────────────────────────────────────────────────────
        0xE0 -> { val n = fetchByte(); mmu.write(0xFF00 or n, a); 12 }
        0xF0 -> { val n = fetchByte(); a = mmu.read(0xFF00 or n); 12 }
        0xE2 -> { mmu.write(0xFF00 or c, a); 8 }
        0xF2 -> { a = mmu.read(0xFF00 or c); 8 }

        // ── LD (nn), A / LD A, (nn) ────────────────────────────────────
        0xEA -> { val nn = fetchWord(); mmu.write(nn, a); 16 }
        0xFA -> { val nn = fetchWord(); a = mmu.read(nn); 16 }

        // ── ADD SP, n ──────────────────────────────────────────────────
        0xE8 -> {
            val n = fetchByte().toByte().toInt()
            flagZ = false; flagN = false
            flagH = ((sp and 0x0F) + (n and 0x0F)) > 0x0F
            flagC = ((sp and 0xFF) + (n and 0xFF)) > 0xFF
            sp = (sp + n) and 0xFFFF; 16
        }

        // ── LD HL, SP+n ────────────────────────────────────────────────
        0xF8 -> {
            val n = fetchByte().toByte().toInt()
            flagZ = false; flagN = false
            flagH = ((sp and 0x0F) + (n and 0x0F)) > 0x0F
            flagC = ((sp and 0xFF) + (n and 0xFF)) > 0xFF
            hl = (sp + n) and 0xFFFF; 12
        }

        // ── JP HL / LD SP, HL ─────────────────────────────────────────
        0xE9 -> { pc = hl; 4 }
        0xF9 -> { sp = hl; 8 }

        // ── DI / EI ────────────────────────────────────────────────────
        0xF3 -> { ime = false; imeScheduled = false; 4 }
        0xFB -> { imeScheduled = true; 4 }

        // ── Opcodes illégaux → 4 cycles ────────────────────────────────
        else -> 4
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reset (valeurs post-boot GBC)
    // ─────────────────────────────────────────────────────────────────────
    fun reset(isGbc: Boolean = true) {
        a = if (isGbc) 0x11 else 0x01
        f = 0x80; b = 0x00; c = 0x00; d = 0xFF
        e = 0x56; h = 0x00; l = 0x0D
        sp = 0xFFFE; pc = 0x0100
        ime = false; imeScheduled = false
        halted = false; haltBug = false
        totalCycles = 0L
    }

    // ─────────────────────────────────────────────────────────────────────
    // Save / Load state
    // ─────────────────────────────────────────────────────────────────────
    fun saveState() = CpuState(a, b, c, d, e, f, h, l, sp, pc, ime, imeScheduled, halted, haltBug, totalCycles)

    fun loadState(s: CpuState) {
        a = s.a; b = s.b; c = s.c; d = s.d; e = s.e; f = s.f
        h = s.h; l = s.l; sp = s.sp; pc = s.pc
        ime = s.ime; imeScheduled = s.imeScheduled
        halted = s.halted; haltBug = s.haltBug
        totalCycles = s.totalCycles
    }
}

data class CpuState(
    val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int,
    val h: Int, val l: Int, val sp: Int, val pc: Int,
    val ime: Boolean, val imeScheduled: Boolean,
    val halted: Boolean, val haltBug: Boolean,
    val totalCycles: Long
) : java.io.Serializable
