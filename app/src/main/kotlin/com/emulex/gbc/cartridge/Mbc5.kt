package com.emulex.gbc.cartridge

/**
 * MBC5 — jusqu'à 8 Mo ROM / 128 Ko RAM.
 * ROM bank : 9 bits (0x00–0x1FF). RAM bank : 4 bits (0x00–0x0F).
 * Utilisé par Pokémon Or/Argent, Zelda Oracle, Link's Awakening DX...
 */
class Mbc5(
    private val rom: ByteArray,
    private val numRomBanks: Int,
    private val numRamBanks: Int,
    private val hasBat: Boolean
) : Mbc {

    private val ram = ByteArray(maxOf(numRamBanks, 1) * 0x2000)
    private var ramEnabled = false
    private var romBankLow = 1    // bits 0-7
    private var romBankHigh = 0   // bit 8
    private var ramBank = 0       // bits 0-3

    private fun romBankNum(): Int = ((romBankHigh shl 8) or romBankLow) % maxOf(numRomBanks, 1)

    override fun readRom(addr: Int): Int {
        val offset = if (addr < 0x4000) addr
        else romBankNum() * 0x4000 + (addr - 0x4000)
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun readRam(addr: Int): Int {
        if (!ramEnabled || numRamBanks == 0) return 0xFF
        val off = (ramBank % maxOf(numRamBanks, 1)) * 0x2000 + (addr - 0xA000)
        return if (off < ram.size) ram[off].toInt() and 0xFF else 0xFF
    }

    override fun writeRom(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x1FFF -> ramEnabled = (v and 0x0F) == 0x0A
            in 0x2000..0x2FFF -> romBankLow = v
            in 0x3000..0x3FFF -> romBankHigh = v and 0x01
            in 0x4000..0x5FFF -> ramBank = v and 0x0F
        }
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled || numRamBanks == 0) return
        val off = (ramBank % maxOf(numRamBanks, 1)) * 0x2000 + (addr - 0xA000)
        if (off < ram.size) ram[off] = value.toByte()
    }

    override fun hasBattery() = hasBat
    override fun getRamData() = ram.copyOf()
    override fun loadRamData(data: ByteArray) { data.copyInto(ram, 0, 0, minOf(data.size, ram.size)) }

    override fun reset() { ramEnabled = false; romBankLow = 1; romBankHigh = 0; ramBank = 0 }
}
