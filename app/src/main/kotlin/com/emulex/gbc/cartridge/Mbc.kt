package com.emulex.gbc.cartridge

/**
 * Interface commune pour tous les Memory Bank Controllers.
 * Chaque implémentation gère le bank-switching selon les spécifications hardware.
 */
sealed interface Mbc {
    fun readRom(addr: Int): Int
    fun readRam(addr: Int): Int
    fun writeRom(addr: Int, value: Int)
    fun writeRam(addr: Int, value: Int)
    fun hasBattery(): Boolean
    fun getRamData(): ByteArray
    fun loadRamData(data: ByteArray)
    fun reset()
}

/** ROM-only : pas de bank-switching (ex: Tetris, DR Mario). */
class RomOnly(private val rom: ByteArray) : Mbc {
    override fun readRom(addr: Int): Int = rom[addr].toInt() and 0xFF
    override fun readRam(addr: Int): Int = 0xFF
    override fun writeRom(addr: Int, value: Int) = Unit
    override fun writeRam(addr: Int, value: Int) = Unit
    override fun hasBattery() = false
    override fun getRamData() = ByteArray(0)
    override fun loadRamData(data: ByteArray) = Unit
    override fun reset() = Unit
}
