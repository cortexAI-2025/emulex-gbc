package com.emulex.gbc.cartridge

/**
 * MBC3 — jusqu'à 2 Mo ROM / 32 Ko RAM + RTC optionnel.
 * ROM bank : 7 bits (0→1). RAM/RTC : sélection 0-3 (RAM) ou 0x08-0x0C (RTC).
 */
class Mbc3(
    private val rom: ByteArray,
    private val numRomBanks: Int,
    private val numRamBanks: Int,
    private val hasBat: Boolean,
    private val hasRtc: Boolean
) : Mbc {

    private val ram = ByteArray(maxOf(numRamBanks, 1) * 0x2000)
    private var ramRtcEnabled = false
    private var romBank = 1
    private var ramRtcBank = 0  // 0-3 = RAM bank, 0x08-0x0C = RTC

    // Registres RTC
    private var rtcSeconds = 0
    private var rtcMinutes = 0
    private var rtcHours = 0
    private var rtcDayLow = 0
    private var rtcDayHigh = 0  // bit0=day high, bit6=halt, bit7=carry
    private var rtcLatch = 0
    private var rtcLatched = false
    // RTC latché (copie pour lecture stable)
    private var latchedSeconds = 0
    private var latchedMinutes = 0
    private var latchedHours = 0
    private var latchedDayLow = 0
    private var latchedDayHigh = 0
    private var lastTimeMs = System.currentTimeMillis()

    override fun readRom(addr: Int): Int {
        val offset = if (addr < 0x4000) addr
        else maxOf(romBank % numRomBanks, 1) * 0x4000 + (addr - 0x4000)
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun readRam(addr: Int): Int {
        if (!ramRtcEnabled) return 0xFF
        return when {
            ramRtcBank in 0..3 -> {
                val off = ramRtcBank * 0x2000 + (addr - 0xA000)
                if (off < ram.size) ram[off].toInt() and 0xFF else 0xFF
            }
            hasRtc && ramRtcBank in 0x08..0x0C -> readRtc()
            else -> 0xFF
        }
    }

    private fun readRtc(): Int = when (ramRtcBank) {
        0x08 -> latchedSeconds
        0x09 -> latchedMinutes
        0x0A -> latchedHours
        0x0B -> latchedDayLow
        0x0C -> latchedDayHigh
        else -> 0xFF
    }

    override fun writeRom(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            in 0x0000..0x1FFF -> ramRtcEnabled = (v and 0x0F) == 0x0A
            in 0x2000..0x3FFF -> romBank = maxOf(v and 0x7F, 1)
            in 0x4000..0x5FFF -> ramRtcBank = v
            in 0x6000..0x7FFF -> {
                // Latch RTC : écriture 0x00 puis 0x01
                if (hasRtc) {
                    if (rtcLatch == 0 && v == 1) latchRtc()
                    rtcLatch = v
                }
            }
        }
    }

    override fun writeRam(addr: Int, value: Int) {
        if (!ramRtcEnabled) return
        val v = value and 0xFF
        when {
            ramRtcBank in 0..3 -> {
                val off = ramRtcBank * 0x2000 + (addr - 0xA000)
                if (off < ram.size) ram[off] = v.toByte()
            }
            hasRtc && ramRtcBank in 0x08..0x0C -> writeRtc(v)
        }
    }

    private fun writeRtc(v: Int) {
        tickRtc()
        when (ramRtcBank) {
            0x08 -> rtcSeconds = v and 0x3F
            0x09 -> rtcMinutes = v and 0x3F
            0x0A -> rtcHours = v and 0x1F
            0x0B -> rtcDayLow = v
            0x0C -> rtcDayHigh = v and 0xC1
        }
    }

    private fun latchRtc() {
        tickRtc()
        latchedSeconds = rtcSeconds; latchedMinutes = rtcMinutes
        latchedHours = rtcHours; latchedDayLow = rtcDayLow
        latchedDayHigh = rtcDayHigh
    }

    private fun tickRtc() {
        if ((rtcDayHigh and 0x40) != 0) { lastTimeMs = System.currentTimeMillis(); return }
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - lastTimeMs) / 1000).toInt()
        lastTimeMs = now
        if (elapsedSec <= 0) return

        var s = rtcSeconds + elapsedSec
        rtcSeconds = s % 60; s /= 60
        var m = rtcMinutes + s
        rtcMinutes = m % 60; m /= 60
        var h = rtcHours + m
        rtcHours = h % 24; h /= 24
        var days = (rtcDayLow or ((rtcDayHigh and 1) shl 8)) + h
        if (days > 511) { rtcDayHigh = rtcDayHigh or 0x80; days = days and 0x1FF }
        rtcDayLow = days and 0xFF
        rtcDayHigh = (rtcDayHigh and 0xFE) or ((days shr 8) and 1)
    }

    override fun hasBattery() = hasBat
    override fun getRamData() = ram.copyOf()
    override fun loadRamData(data: ByteArray) { data.copyInto(ram, 0, 0, minOf(data.size, ram.size)) }

    override fun reset() { ramRtcEnabled = false; romBank = 1; ramRtcBank = 0 }
}
