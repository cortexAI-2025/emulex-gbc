package com.emulex.gbc.utils

import com.emulex.gbc.core.*
import java.io.*

/**
 * Conteneur de sauvegarde d'état complet.
 * Sérialise/désérialise via Java ObjectOutputStream pour simplicité.
 */
data class SaveState(
    val cpuState: CpuState,
    val mmuState: MmuState,
    val ppuState: PpuState,
    val timerState: TimerState,
    val joypadState: JoypadState,
    val version: Int = CURRENT_VERSION
) : Serializable {

    companion object {
        const val CURRENT_VERSION = 1
        private const val MAGIC = 0x47424353L  // "GBCS"

        fun serialize(state: SaveState): ByteArray {
            val baos = ByteArrayOutputStream()
            ObjectOutputStream(baos).use { oos ->
                oos.writeLong(MAGIC)
                oos.writeInt(CURRENT_VERSION)
                oos.writeObject(state)
            }
            return baos.toByteArray()
        }

        fun deserialize(data: ByteArray): SaveState? {
            return try {
                val bais = ByteArrayInputStream(data)
                ObjectInputStream(bais).use { ois ->
                    val magic = ois.readLong()
                    if (magic != MAGIC) return null
                    val version = ois.readInt()
                    if (version != CURRENT_VERSION) return null
                    ois.readObject() as SaveState
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
