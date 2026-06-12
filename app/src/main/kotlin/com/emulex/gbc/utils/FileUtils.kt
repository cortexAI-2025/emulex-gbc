package com.emulex.gbc.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    // ── Chargement ROM ────────────────────────────────────────────────
    fun loadRom(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Sauvegarde / Chargement RAM cartouche (.sav) ──────────────────
    fun getSavFile(context: Context, romName: String): File {
        val saveDir = File(context.filesDir, "saves")
        saveDir.mkdirs()
        val name = romName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return File(saveDir, "$name.sav")
    }

    fun loadSav(context: Context, romName: String): ByteArray? {
        val file = getSavFile(context, romName)
        return if (file.exists()) file.readBytes() else null
    }

    fun saveSav(context: Context, romName: String, data: ByteArray) {
        getSavFile(context, romName).writeBytes(data)
    }

    // ── Save States ───────────────────────────────────────────────────
    fun getStateFile(context: Context, romName: String, slot: Int): File {
        val stateDir = File(context.filesDir, "states")
        stateDir.mkdirs()
        val name = romName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return File(stateDir, "${name}_$slot.gbs")
    }

    fun loadState(context: Context, romName: String, slot: Int): ByteArray? {
        val file = getStateFile(context, romName, slot)
        return if (file.exists()) file.readBytes() else null
    }

    fun saveState(context: Context, romName: String, slot: Int, data: ByteArray) {
        getStateFile(context, romName, slot).writeBytes(data)
    }

    // ── ROMs récentes (SharedPreferences) ────────────────────────────
    private const val PREFS_NAME = "emulex_prefs"
    private const val KEY_RECENT = "recent_roms"
    private const val MAX_RECENT = 10

    fun addRecentRom(context: Context, uriString: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getRecentRoms(context).toMutableList()
        current.remove(uriString)
        current.add(0, uriString)
        while (current.size > MAX_RECENT) current.removeLast()
        prefs.edit().putString(KEY_RECENT, current.joinToString("|")).apply()
    }

    fun getRecentRoms(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("|").filter { it.isNotEmpty() }
    }

    fun getRomNameFromUri(context: Context, uri: Uri): String {
        var name = "unknown"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex("_display_name")
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return name.removeSuffix(".gbc").removeSuffix(".gb").removeSuffix(".GBC").removeSuffix(".GB")
    }
}
