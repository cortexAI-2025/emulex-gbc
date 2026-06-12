package com.emulex.gbc.cartridge

/**
 * MBC1 — jusqu'à 2 Mo ROM / 32 Ko RAM.
 * Deux modes : ROM banking (défaut) ou RAM banking.
 *
 * Registres :
 *  - 0x0000–0x1FFF : activation RAM (0x0A = activer)
 *  - 0x2000–0x3FFF : ROM bank low (5 bits, 0→1)
 *  - 0x4000–0x5FFF : ROM bank high / RAM bank (2 bits)
 *  - 0x6000–0x7FFF : mode (0=ROM, 1=RAM)
 */
class Mbc1(
    private val rom: ByteArray,
    private val numRomBanks: Int,
    private val numRamBanks: Int,
    private val hasBat: Boolean
) : Mbc {

    private val ram = ByteArray(maxOf(numRamBanks, 1) * 0x2000)
    private var ramEnabled = false
    private var romBankLow = 1    // bits 0-4
    private var bankHigh = 0      // bits 0-1 (ROM bank high ou RAM bank)
    private var mode = 0          // 0=ROM banking, 1=RAM banking

    private val romBankCount get() = numRomBanks
    private val ramBankCount get() = maxOf(numRamBanks, 1)

    private fun romBank0(): Int {
        return if (mode == 1) (bankHigh shl 5) % romBankCount else 0
    }

    private fun romBankX(): Int {
        val bank = (bankHigh shl 5) or romBankLow
        return maxOf(bank % romBankCount, 1)
    }

    private fun ramBank(): Int = if (mode == 1) bankHigh % ramBankCount else 0

    override fun readRom(addr: Int): Int {
        return if (addr < 0x4000) {
            val offset = romBank0() * 0x4000 + addr
            if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
        } else {
            val offset = romBankX() * 0x4000 + (addr - 0x4000)
            if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
        }
    }

    override fun readRam(addr: Int): Int {
        if (!ramEnabled || numRamBanks == 0) return 0xFF
        val offset = ramBank() * 0x2000 + (addr - 0xA000)
        return if (offset < ram.size) ram[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeRom(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
            in 0x2000..0x3FFF -> romBankLow = maxOf(v and 0x1F, 1)
            in 0x4000..0x5FFF -> bankHigh = v and 0x03
            in 0x6000..0x7FFF -> mode = v and 0x01
        }
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled || numRamBanks == 0) return
        val offset = ramBank() * 0x2000 + (addr - 0xA000)
        if (offset < ram.size) ram[offset] = value.toByte()
    }

    override fun hasBattery() = hasBat
    override fun getRamData() = ram.copyOf()
    override fun loadRamData(data: ByteArray) { data.copyInto(ram, 0, 0, minOf(data.size, ram.size)) }

    override fun reset() {
        ramEnabled = false; romBankLow = 1; bankHigh = 0; mode = 0
    }
}
