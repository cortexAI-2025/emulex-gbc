package com.emulex.gbc.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.abs

/**
 * Audio Processing Unit du Game Boy Color.
 *
 * 4 canaux :
 *  CH1 : Pulse avec sweep de fréquence
 *  CH2 : Pulse sans sweep
 *  CH3 : Wave (forme d'onde programmable)
 *  CH4 : Noise (LFSR)
 *
 * Frame sequencer (512 Hz) :
 *  Step 0     : longueur
 *  Step 2     : longueur + sweep
 *  Step 4     : longueur
 *  Step 6     : longueur + sweep
 *  Step 7     : enveloppe volume
 */
class Apu {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CPU_FREQ = 4194304
        // Cycles CPU par échantillon audio
        private const val CYCLES_PER_SAMPLE = CPU_FREQ / SAMPLE_RATE
        // Taille du buffer audio (environ 40 ms)
        private const val BUFFER_SIZE = SAMPLE_RATE / 25  // 1764 échantillons
    }

    // ── État global APU ───────────────────────────────────────────────
    var soundEnabled = true
    private var nr50 = 0x77  // contrôle volume gauche/droite
    private var nr51 = 0xF3  // sélection canal gauche/droite
    private var nr52 = 0xF1  // contrôle activation (bit7=power)

    // ── Canal 1 : Pulse + Sweep ───────────────────────────────────────
    private var ch1 = PulseChannel()
    private var ch1SweepPace = 0; private var ch1SweepDir = 0; private var ch1SweepShift = 0
    private var ch1SweepTimer = 0; private var ch1SweepEnabled = false
    private var ch1SweepFreq = 0  // shadow frequency register

    // ── Canal 2 : Pulse ───────────────────────────────────────────────
    private var ch2 = PulseChannel()

    // ── Canal 3 : Wave ────────────────────────────────────────────────
    private var ch3 = WaveChannel()

    // ── Canal 4 : Noise ───────────────────────────────────────────────
    private var ch4 = NoiseChannel()

    // ── Frame Sequencer (512 Hz = toutes les 8192 cycles) ─────────────
    private var fsTimer = 0
    private var fsStep = 0

    // ── Génération de samples ─────────────────────────────────────────
    private var sampleTimer = 0
    private val sampleBuffer = ShortArray(BUFFER_SIZE * 2)  // stéréo
    private var samplePos = 0

    // ── AudioTrack ────────────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private var running = false

    // ─────────────────────────────────────────────────────────────────
    // Démarrage / Arrêt
    // ─────────────────────────────────────────────────────────────────
    fun start() {
        if (running) return
        running = true

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, BUFFER_SIZE * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    fun stop() {
        running = false
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
    }

    // ─────────────────────────────────────────────────────────────────
    // Avance l'APU de `cycles` T-cycles
    // ─────────────────────────────────────────────────────────────────
    fun step(cycles: Int) {
        if ((nr52 and 0x80) == 0) return  // APU éteint

        var remaining = cycles
        while (remaining > 0) {
            val tick = minOf(remaining, 4)
            remaining -= tick

            // Frame Sequencer
            fsTimer += tick
            if (fsTimer >= 8192) {
                fsTimer -= 8192
                tickFrameSequencer()
            }

            // Canaux
            ch1.tick(tick); ch2.tick(tick); ch3.tick(tick); ch4.tick(tick)

            // Génération de samples
            sampleTimer += tick
            if (sampleTimer >= CYCLES_PER_SAMPLE) {
                sampleTimer -= CYCLES_PER_SAMPLE
                if (samplePos < sampleBuffer.size - 1) {
                    val (left, right) = mixSample()
                    sampleBuffer[samplePos++] = left
                    sampleBuffer[samplePos++] = right
                }
            }
        }

        // Envoi du buffer audio si plein
        if (samplePos >= sampleBuffer.size) {
            flushBuffer()
        }
    }

    private fun tickFrameSequencer() {
        when (fsStep) {
            0, 4 -> tickLength()
            2, 6 -> { tickLength(); tickSweep() }
            7 -> tickEnvelope()
        }
        fsStep = (fsStep + 1) and 7
    }

    private fun tickLength() {
        ch1.tickLength(); ch2.tickLength(); ch3.tickLength(); ch4.tickLength()
    }

    private fun tickEnvelope() {
        ch1.tickEnvelope(); ch2.tickEnvelope(); ch4.tickEnvelope()
    }

    private fun tickSweep() {
        if (ch1SweepTimer > 0) ch1SweepTimer--
        if (ch1SweepTimer == 0) {
            ch1SweepTimer = if (ch1SweepPace > 0) ch1SweepPace else 8
            if (ch1SweepEnabled && ch1SweepPace > 0) {
                val newFreq = calcSweepFreq()
                if (newFreq <= 2047 && ch1SweepShift > 0) {
                    ch1SweepFreq = newFreq
                    ch1.freq = newFreq
                    calcSweepFreq()  // overflow check
                }
                if (calcSweepFreq() > 2047) ch1.active = false
            }
        }
    }

    private fun calcSweepFreq(): Int {
        val delta = ch1SweepFreq shr ch1SweepShift
        return if (ch1SweepDir == 0) ch1SweepFreq + delta else ch1SweepFreq - delta
    }

    // ─────────────────────────────────────────────────────────────────
    // Mix des canaux → short stéréo
    // ─────────────────────────────────────────────────────────────────
    private fun mixSample(): Pair<Short, Short> {
        if (!soundEnabled || (nr52 and 0x80) == 0) return Pair(0, 0)

        val s1 = if (ch1.active) ch1.sample() else 0
        val s2 = if (ch2.active) ch2.sample() else 0
        val s3 = if (ch3.active) ch3.sample() else 0
        val s4 = if (ch4.active) ch4.sample() else 0

        var leftSum = 0; var rightSum = 0
        // nr51 : bits 7-4 = left, bits 3-0 = right
        if ((nr51 and 0x80) != 0) leftSum += s4
        if ((nr51 and 0x40) != 0) leftSum += s3
        if ((nr51 and 0x20) != 0) leftSum += s2
        if ((nr51 and 0x10) != 0) leftSum += s1
        if ((nr51 and 0x08) != 0) rightSum += s4
        if ((nr51 and 0x04) != 0) rightSum += s3
        if ((nr51 and 0x02) != 0) rightSum += s2
        if ((nr51 and 0x01) != 0) rightSum += s1

        val leftVol = ((nr50 shr 4) and 0x07) + 1
        val rightVol = (nr50 and 0x07) + 1

        val left = clampShort((leftSum * leftVol * 400).toLong())
        val right = clampShort((rightSum * rightVol * 400).toLong())
        return Pair(left, right)
    }

    private fun clampShort(v: Long): Short =
        minOf(32767L, maxOf(-32768L, v)).toShort()

    private fun flushBuffer() {
        audioTrack?.write(sampleBuffer, 0, samplePos)
        samplePos = 0
    }

    // ─────────────────────────────────────────────────────────────────
    // Lecture registres APU
    // ─────────────────────────────────────────────────────────────────
    fun read(addr: Int): Int = when (addr) {
        // CH1
        0xFF10 -> 0x80 or (ch1SweepPace shl 4) or (ch1SweepDir shl 3) or ch1SweepShift
        0xFF11 -> 0x3F or (ch1.dutyMode shl 6)
        0xFF12 -> (ch1.initVolume shl 4) or (ch1.envDir shl 3) or ch1.envPace
        0xFF13 -> 0xFF  // NR13 write-only
        0xFF14 -> 0xBF or (if (ch1.lengthEnabled) 0x40 else 0)
        // CH2
        0xFF16 -> 0x3F or (ch2.dutyMode shl 6)
        0xFF17 -> (ch2.initVolume shl 4) or (ch2.envDir shl 3) or ch2.envPace
        0xFF18 -> 0xFF
        0xFF19 -> 0xBF or (if (ch2.lengthEnabled) 0x40 else 0)
        // CH3
        0xFF1A -> (if (ch3.dacEnabled) 0x80 else 0) or 0x7F
        0xFF1B -> 0xFF
        0xFF1C -> 0x9F or (ch3.outputLevel shl 5)
        0xFF1D -> 0xFF
        0xFF1E -> 0xBF or (if (ch3.lengthEnabled) 0x40 else 0)
        // CH4
        0xFF20 -> 0xFF
        0xFF21 -> (ch4.initVolume shl 4) or (ch4.envDir shl 3) or ch4.envPace
        0xFF22 -> (ch4.clockShift shl 4) or (ch4.lfsrWidth shl 3) or ch4.divisor
        0xFF23 -> 0xBF or (if (ch4.lengthEnabled) 0x40 else 0)
        // Global
        0xFF24 -> nr50; 0xFF25 -> nr51
        0xFF26 -> (nr52 and 0x80) or 0x70 or
                  (if (ch4.active) 0x08 else 0) or (if (ch3.active) 0x04 else 0) or
                  (if (ch2.active) 0x02 else 0) or (if (ch1.active) 0x01 else 0)
        // Wave RAM
        in 0xFF30..0xFF3F -> ch3.waveRam[addr - 0xFF30].toInt() and 0xFF
        else -> 0xFF
    }

    // ─────────────────────────────────────────────────────────────────
    // Écriture registres APU
    // ─────────────────────────────────────────────────────────────────
    fun write(addr: Int, value: Int) {
        val v = value and 0xFF
        // Si APU éteint, seuls NR52 et wave RAM sont accessibles
        if ((nr52 and 0x80) == 0 && addr != 0xFF26 && addr !in 0xFF30..0xFF3F) return

        when (addr) {
            // CH1
            0xFF10 -> {
                ch1SweepPace = (v shr 4) and 0x07
                ch1SweepDir = (v shr 3) and 0x01
                ch1SweepShift = v and 0x07
            }
            0xFF11 -> { ch1.dutyMode = (v shr 6) and 0x03; ch1.length = 64 - (v and 0x3F) }
            0xFF12 -> {
                ch1.initVolume = (v shr 4) and 0x0F; ch1.envDir = (v shr 3) and 0x01
                ch1.envPace = v and 0x07
                if ((v and 0xF8) == 0) ch1.active = false
            }
            0xFF13 -> ch1.freq = (ch1.freq and 0x700) or v
            0xFF14 -> {
                ch1.freq = (ch1.freq and 0x0FF) or ((v and 0x07) shl 8)
                ch1.lengthEnabled = (v and 0x40) != 0
                if ((v and 0x80) != 0) triggerCh1()
            }
            // CH2
            0xFF16 -> { ch2.dutyMode = (v shr 6) and 0x03; ch2.length = 64 - (v and 0x3F) }
            0xFF17 -> {
                ch2.initVolume = (v shr 4) and 0x0F; ch2.envDir = (v shr 3) and 0x01
                ch2.envPace = v and 0x07
                if ((v and 0xF8) == 0) ch2.active = false
            }
            0xFF18 -> ch2.freq = (ch2.freq and 0x700) or v
            0xFF19 -> {
                ch2.freq = (ch2.freq and 0x0FF) or ((v and 0x07) shl 8)
                ch2.lengthEnabled = (v and 0x40) != 0
                if ((v and 0x80) != 0) triggerCh2()
            }
            // CH3
            0xFF1A -> { ch3.dacEnabled = (v and 0x80) != 0; if (!ch3.dacEnabled) ch3.active = false }
            0xFF1B -> ch3.length = 256 - v
            0xFF1C -> ch3.outputLevel = (v shr 5) and 0x03
            0xFF1D -> ch3.freq = (ch3.freq and 0x700) or v
            0xFF1E -> {
                ch3.freq = (ch3.freq and 0x0FF) or ((v and 0x07) shl 8)
                ch3.lengthEnabled = (v and 0x40) != 0
                if ((v and 0x80) != 0) triggerCh3()
            }
            // CH4
            0xFF20 -> ch4.length = 64 - (v and 0x3F)
            0xFF21 -> {
                ch4.initVolume = (v shr 4) and 0x0F; ch4.envDir = (v shr 3) and 0x01
                ch4.envPace = v and 0x07
                if ((v and 0xF8) == 0) ch4.active = false
            }
            0xFF22 -> {
                ch4.clockShift = (v shr 4) and 0x0F; ch4.lfsrWidth = (v shr 3) and 0x01
                ch4.divisor = v and 0x07
            }
            0xFF23 -> {
                ch4.lengthEnabled = (v and 0x40) != 0
                if ((v and 0x80) != 0) triggerCh4()
            }
            // Global
            0xFF24 -> nr50 = v
            0xFF25 -> nr51 = v
            0xFF26 -> {
                if ((v and 0x80) == 0 && (nr52 and 0x80) != 0) {
                    // Éteindre APU : réinitialiser tous les registres
                    resetAllChannels()
                }
                nr52 = (nr52 and 0x7F) or (v and 0x80)
            }
            in 0xFF30..0xFF3F -> ch3.waveRam[addr - 0xFF30] = v.toByte()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Trigger (déclenchement) des canaux
    // ─────────────────────────────────────────────────────────────────
    private fun triggerCh1() {
        ch1.active = ch1.initVolume > 0 || ch1.envDir != 0
        ch1.volume = ch1.initVolume; ch1.envTimer = ch1.envPace
        ch1.freqTimer = (2048 - ch1.freq) * 4
        if (ch1.length == 0) ch1.length = 64
        // Sweep init
        ch1SweepFreq = ch1.freq
        ch1SweepTimer = if (ch1SweepPace > 0) ch1SweepPace else 8
        ch1SweepEnabled = ch1SweepPace > 0 || ch1SweepShift > 0
        if (ch1SweepShift > 0 && calcSweepFreq() > 2047) ch1.active = false
    }

    private fun triggerCh2() {
        ch2.active = ch2.initVolume > 0 || ch2.envDir != 0
        ch2.volume = ch2.initVolume; ch2.envTimer = ch2.envPace
        ch2.freqTimer = (2048 - ch2.freq) * 4
        if (ch2.length == 0) ch2.length = 64
    }

    private fun triggerCh3() {
        ch3.active = ch3.dacEnabled
        ch3.freqTimer = (2048 - ch3.freq) * 2
        ch3.wavePos = 0
        if (ch3.length == 0) ch3.length = 256
    }

    private fun triggerCh4() {
        ch4.active = ch4.initVolume > 0 || ch4.envDir != 0
        ch4.volume = ch4.initVolume; ch4.envTimer = ch4.envPace
        ch4.lfsr = 0x7FFF
        if (ch4.length == 0) ch4.length = 64
    }

    private fun resetAllChannels() {
        ch1 = PulseChannel(); ch2 = PulseChannel()
        ch3 = WaveChannel(); ch4 = NoiseChannel()
        ch1SweepPace = 0; ch1SweepDir = 0; ch1SweepShift = 0
        nr50 = 0; nr51 = 0
    }

    fun reset() {
        resetAllChannels()
        nr52 = 0xF1; nr50 = 0x77; nr51 = 0xF3
        fsTimer = 0; fsStep = 0; sampleTimer = 0; samplePos = 0
    }

    // ─────────────────────────────────────────────────────────────────
    // Classes internes des canaux
    // ─────────────────────────────────────────────────────────────────
    inner class PulseChannel {
        var active = false
        var dutyMode = 2  // 50% duty par défaut
        var length = 64; var lengthEnabled = false
        var initVolume = 0; var volume = 0
        var envDir = 0; var envPace = 0; var envTimer = 0
        var freq = 0; var freqTimer = 0
        var dutyPos = 0

        // Duty wave tables
        private val dutyTable = arrayOf(
            intArrayOf(0,0,0,0,0,0,0,1),  // 12.5%
            intArrayOf(1,0,0,0,0,0,0,1),  // 25%
            intArrayOf(1,0,0,0,0,1,1,1),  // 50%
            intArrayOf(0,1,1,1,1,1,1,0)   // 75%
        )

        fun tick(cycles: Int) {
            freqTimer -= cycles
            if (freqTimer <= 0) {
                freqTimer += (2048 - freq) * 4
                dutyPos = (dutyPos + 1) and 7
            }
        }

        fun sample(): Int = if (dutyTable[dutyMode][dutyPos] != 0) volume else 0

        fun tickLength() {
            if (lengthEnabled && length > 0) {
                length--
                if (length == 0) active = false
            }
        }

        fun tickEnvelope() {
            if (envPace == 0) return
            if (envTimer > 0) envTimer--
            if (envTimer == 0) {
                envTimer = envPace
                if (envDir == 1 && volume < 15) volume++
                else if (envDir == 0 && volume > 0) volume--
            }
        }
    }

    inner class WaveChannel {
        var active = false; var dacEnabled = false
        var length = 256; var lengthEnabled = false
        var outputLevel = 0  // 0=mute, 1=100%, 2=50%, 3=25%
        var freq = 0; var freqTimer = 0
        var wavePos = 0
        val waveRam = ByteArray(16)

        fun tick(cycles: Int) {
            freqTimer -= cycles
            if (freqTimer <= 0) {
                freqTimer += (2048 - freq) * 2
                wavePos = (wavePos + 1) and 31
            }
        }

        fun sample(): Int {
            if (!dacEnabled) return 0
            val byte = waveRam[wavePos / 2].toInt() and 0xFF
            val nibble = if (wavePos and 1 == 0) byte shr 4 else byte and 0x0F
            return when (outputLevel) {
                0 -> 0; 1 -> nibble; 2 -> nibble shr 1; else -> nibble shr 2
            }
        }

        fun tickLength() {
            if (lengthEnabled && length > 0) {
                length--
                if (length == 0) active = false
            }
        }
    }

    inner class NoiseChannel {
        var active = false
        var length = 64; var lengthEnabled = false
        var initVolume = 0; var volume = 0
        var envDir = 0; var envPace = 0; var envTimer = 0
        var clockShift = 0; var lfsrWidth = 0; var divisor = 0
        var lfsr = 0x7FFF; var freqTimer = 0

        private val divisorTable = intArrayOf(8, 16, 32, 48, 64, 80, 96, 112)

        fun tick(cycles: Int) {
            freqTimer -= cycles
            if (freqTimer <= 0) {
                freqTimer += divisorTable[divisor] shl clockShift
                val xor = (lfsr xor (lfsr shr 1)) and 1
                lfsr = (lfsr shr 1) or (xor shl 14)
                if (lfsrWidth == 1) {
                    lfsr = (lfsr and 0x7FBF) or (xor shl 6)
                }
            }
        }

        fun sample(): Int = if ((lfsr and 1) == 0) volume else 0

        fun tickLength() {
            if (lengthEnabled && length > 0) {
                length--
                if (length == 0) active = false
            }
        }

        fun tickEnvelope() {
            if (envPace == 0) return
            if (envTimer > 0) envTimer--
            if (envTimer == 0) {
                envTimer = envPace
                if (envDir == 1 && volume < 15) volume++
                else if (envDir == 0 && volume > 0) volume--
            }
        }
    }
}
