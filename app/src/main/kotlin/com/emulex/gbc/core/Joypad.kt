package com.emulex.gbc.core

/**
 * Registre JOYP (0xFF00) — boutons du Game Boy.
 *
 * Bits 5-4 : sélection (0 = actif)
 *   Bit 5 : sélectionne boutons (A, B, Select, Start)
 *   Bit 4 : sélectionne directions (Right, Left, Up, Down)
 * Bits 3-0 : état (0 = pressé)
 */
class Joypad {

    // Boutons physiques pressés (true = pressé)
    var right: Boolean = false
    var left: Boolean = false
    var up: Boolean = false
    var down: Boolean = false
    var a: Boolean = false
    var b: Boolean = false
    var select: Boolean = false
    var start: Boolean = false

    // Registre de sélection écrit par le CPU
    private var selection: Int = 0x30  // bits 4 et 5

    var interruptRequested: Boolean = false
    private var previousState: Int = 0xFF

    fun read(addr: Int): Int {
        if (addr != 0xFF00) return 0xFF
        val result = buildJoyp()
        return result
    }

    fun write(addr: Int, value: Int) {
        if (addr != 0xFF00) return
        selection = value and 0x30
    }

    private fun buildJoyp(): Int {
        var low = 0x0F
        val selectButtons = (selection and 0x20) == 0
        val selectDirections = (selection and 0x10) == 0

        if (selectDirections) {
            if (right) low = low and 0x0E
            if (left) low = low and 0x0D
            if (up) low = low and 0x0B
            if (down) low = low and 0x07
        }
        if (selectButtons) {
            if (a) low = low and 0x0E
            if (b) low = low and 0x0D
            if (select) low = low and 0x0B
            if (start) low = low and 0x07
        }

        val result = 0xC0 or selection or low

        // Interruption joypad sur front descendant (bouton pressé)
        if ((previousState and 0x0F) != (result and 0x0F)) {
            val wasLow = previousState and 0x0F
            val isLow = result and 0x0F
            // Si un bit passe de 1 à 0 (bouton pressé), déclenche interruption
            if ((wasLow and isLow.inv()) != 0) {
                interruptRequested = true
            }
        }
        previousState = result
        return result
    }

    fun reset() {
        right = false; left = false; up = false; down = false
        a = false; b = false; select = false; start = false
        selection = 0x30
        interruptRequested = false
        previousState = 0xFF
    }

    // Constantes boutons
    companion object {
        const val BTN_RIGHT = 0
        const val BTN_LEFT = 1
        const val BTN_UP = 2
        const val BTN_DOWN = 3
        const val BTN_A = 4
        const val BTN_B = 5
        const val BTN_SELECT = 6
        const val BTN_START = 7

        fun buttonName(btn: Int): String = when (btn) {
            BTN_RIGHT -> "Right"; BTN_LEFT -> "Left"
            BTN_UP -> "Up"; BTN_DOWN -> "Down"
            BTN_A -> "A"; BTN_B -> "B"
            BTN_SELECT -> "Select"; BTN_START -> "Start"
            else -> "?"
        }
    }

    fun setButton(btn: Int, pressed: Boolean) {
        when (btn) {
            BTN_RIGHT -> right = pressed
            BTN_LEFT -> left = pressed
            BTN_UP -> up = pressed
            BTN_DOWN -> down = pressed
            BTN_A -> a = pressed
            BTN_B -> b = pressed
            BTN_SELECT -> select = pressed
            BTN_START -> start = pressed
        }
        // Force recalcul pour déclencher interruption si nécessaire
        buildJoyp()
    }

    fun saveState(): JoypadState = JoypadState(
        right, left, up, down, a, b, select, start, selection
    )

    fun loadState(s: JoypadState) {
        right = s.right; left = s.left; up = s.up; down = s.down
        a = s.a; b = s.b; select = s.select; start = s.start
        selection = s.selection
    }
}

data class JoypadState(
    val right: Boolean,
    val left: Boolean,
    val up: Boolean,
    val down: Boolean,
    val a: Boolean,
    val b: Boolean,
    val select: Boolean,
    val start: Boolean,
    val selection: Int
) : java.io.Serializable
