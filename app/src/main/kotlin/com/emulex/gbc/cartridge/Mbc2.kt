package com.emulex.gbc.cartridge

/**
 * MBC2 — jusqu'à 256 Ko ROM, RAM interne de 512 × 4 bits.
 * Le bit 8 de l'adresse différencie activation RAM / sélection bank ROM.
 */
class Mbc2(
    private val rom: ByteArray,
    private val numRomBanks: Int,
    private val hasBat: Boolean
) : Mbc {

    // 512 nibbles (4 bits each), stockés en octet bas
    private val ram = ByteArray(512)
    private var ramEnabled = false
    private var romBank = 1

    override fun readRom(addr: Int): Int {
        val offset = if (addr < 0x4000) {
            addr
        } else {
            val bank = maxOf(romBank % numRomBanks, 1)
            bank * 0x4000 + (addr - 0x4000)
        }
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun readRam(addr: Int): Int {
        if (!ramEnabled) return 0xFF
        return ram[(addr - 0xA000) and 0x1FF].toInt() and 0x0F or 0xF0
    }

    override fun writeRom(addr: Int, value: Int) {
        if (addr > 0x3FFF) return
        if ((addr and 0x0100) == 0) {
            ramEnabled = (value and 0x0F) == 0x0A
        } else {
            romBank = maxOf(value and 0x0F, 1)
        }
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramEnabled) return
        ram[(addr - 0xA000) and 0x1FF] = (value and 0x0F).toByte()
    }

    override fun hasBattery() = hasBat
    override fun getRamData() = ram.copyOf()
    override fun loadRamData(data: ByteArray) { data.copyInto(ram, 0, 0, minOf(data.size, ram.size)) }

    override fun reset() { ramEnabled = false; romBank = 1 }
}
