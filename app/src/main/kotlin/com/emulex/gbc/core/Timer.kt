package com.emulex.gbc.core

/**
 * Timer du Game Boy (DIV, TIMA, TMA, TAC).
 * Le registre DIV est incrémenté à 16384 Hz (chaque 256 T-cycles).
 * Le timer TIMA est incrémenté selon TAC.
 */
class Timer {

    // Compteur interne 16 bits — l'octet haut est DIV (0xFF04)
    var internalCounter: Int = 0xABCC  // valeur post-boot GBC

    var tima: Int = 0x00   // 0xFF05
    var tma: Int = 0x00    // 0xFF06
    var tac: Int = 0xF8    // 0xFF07

    // Géré par la MMU pour déclencher l'interruption
    var interruptRequested: Boolean = false

    // Délai de rechargement TIMA (4 cycles après overflow)
    private var timaOverflowDelay: Int = 0
    private var timaReloading: Boolean = false

    // Fréquences TIMA selon TAC bits 1-0
    // Bit sélectionné du compteur interne pour détecter le front descendant
    private val tacBit = intArrayOf(9, 3, 5, 7)

    fun read(addr: Int): Int = when (addr) {
        0xFF04 -> (internalCounter shr 8) and 0xFF
        0xFF05 -> tima
        0xFF06 -> tma
        0xFF07 -> tac or 0xF8
        else -> 0xFF
    }

    fun write(addr: Int, value: Int) {
        when (addr) {
            0xFF04 -> {
                // Écriture sur DIV remet le compteur à zéro
                // Vérifie si front descendant sur le bit sélectionné
                val bit = tacBit[tac and 0x03]
                if ((tac and 0x04) != 0 && (internalCounter and (1 shl bit)) != 0) {
                    incrementTima()
                }
                internalCounter = 0
            }
            0xFF05 -> {
                // Si TIMA est en cours de rechargement, l'écriture annule le rechargement
                if (timaOverflowDelay > 0) {
                    // ignore
                } else {
                    tima = value and 0xFF
                    timaReloading = false
                }
            }
            0xFF06 -> {
                tma = value and 0xFF
                // Si en cours de rechargement, met à jour TIMA aussi
                if (timaReloading) tima = tma
            }
            0xFF07 -> {
                val oldBit = tacBit[tac and 0x03]
                val oldEnable = (tac and 0x04) != 0
                tac = (value and 0x07) or 0xF8
                val newBit = tacBit[tac and 0x03]
                val newEnable = (tac and 0x04) != 0
                // Front descendant : timer était actif ET bit était à 1, maintenant désactivé ou bit changé
                val wasHigh = (internalCounter and (1 shl oldBit)) != 0
                if (oldEnable && wasHigh && (!newEnable || (internalCounter and (1 shl newBit)) == 0)) {
                    incrementTima()
                }
            }
        }
    }

    fun step(cycles: Int) {
        var remaining = cycles
        while (remaining > 0) {
            val step = minOf(remaining, 4)
            remaining -= step
            tickInternal(step)
        }
    }

    private fun tickInternal(cycles: Int) {
        // Gestion du délai overflow TIMA
        if (timaOverflowDelay > 0) {
            timaOverflowDelay -= cycles
            if (timaOverflowDelay <= 0) {
                timaOverflowDelay = 0
                tima = tma
                timaReloading = true
                interruptRequested = true
            }
        } else {
            timaReloading = false
        }

        val timerEnable = (tac and 0x04) != 0
        val bit = tacBit[tac and 0x03]

        repeat(cycles / 4) {
            val oldCounter = internalCounter
            internalCounter = (internalCounter + 4) and 0xFFFF
            // Front descendant sur le bit sélectionné quand timer actif
            if (timerEnable) {
                val wasSet = (oldCounter and (1 shl bit)) != 0
                val isSet = (internalCounter and (1 shl bit)) != 0
                if (wasSet && !isSet) {
                    incrementTima()
                }
            }
        }
    }

    private fun incrementTima() {
        tima = (tima + 1) and 0xFF
        if (tima == 0) {
            // Overflow : délai de 4 cycles avant rechargement
            timaOverflowDelay = 4
        }
    }

    fun reset() {
        internalCounter = 0xABCC
        tima = 0
        tma = 0
        tac = 0xF8
        interruptRequested = false
        timaOverflowDelay = 0
        timaReloading = false
    }

    fun saveState(): TimerState = TimerState(
        internalCounter, tima, tma, tac, timaOverflowDelay, timaReloading
    )

    fun loadState(s: TimerState) {
        internalCounter = s.internalCounter
        tima = s.tima
        tma = s.tma
        tac = s.tac
        timaOverflowDelay = s.timaOverflowDelay
        timaReloading = s.timaReloading
    }
}

data class TimerState(
    val internalCounter: Int,
    val tima: Int,
    val tma: Int,
    val tac: Int,
    val timaOverflowDelay: Int,
    val timaReloading: Boolean
) : java.io.Serializable
