package com.emulex.gbc.core

/**
 * Picture Processing Unit du Game Boy Color.
 *
 * Résolution : 160×144 pixels.
 * Timing par scanline : 456 T-cycles
 *   Mode 2 (OAM Search)    : 80 cycles
 *   Mode 3 (Pixel Transfer) : ~172 cycles (variable)
 *   Mode 0 (HBlank)         : ~204 cycles
 * Mode 1 (VBlank) : lignes 144–153 (10 lignes × 456 cycles)
 * Frame complète : 70224 T-cycles ≈ 59.73 Hz
 */
class Ppu {
    // Injecté après construction pour éviter la dépendance circulaire PPU↔MMU
    lateinit var mmu: Mmu

    // ── Frame buffer (160×144 ARGB) ───────────────────────────────────
    val frameBuffer = IntArray(160 * 144)
    private val lineBuffer = IntArray(160)          // couleurs pixel ligne courante
    private val linePriority = BooleanArray(160)     // BG a priorité sur sprites

    // ── VRAM : 2 banks × 8 Ko ────────────────────────────────────────
    val vram = Array(2) { ByteArray(0x2000) }
    var vramBank = 0  // banque VRAM active (GBC seulement)

    // ── OAM : 40 sprites × 4 octets ──────────────────────────────────
    val oam = ByteArray(0xA0)

    // ── Registres LCD ────────────────────────────────────────────────
    var lcdc = 0x91
    var stat = 0x85
    var scy = 0x00; var scx = 0x00
    var ly = 0x00; var lyc = 0x00
    var bgp = 0xFC   // DMG background palette
    var obp0 = 0xFF; var obp1 = 0xFF
    var wy = 0x00; var wx = 0x00

    // ── Palettes GBC ─────────────────────────────────────────────────
    // 8 BG palettes × 4 couleurs × 2 octets = 64 octets
    val bgPaletteRam = ByteArray(64)
    var bgPaletteIndex = 0
    var bgPaletteAutoInc = false
    // 8 OBJ palettes × 4 couleurs × 2 octets = 64 octets
    val objPaletteRam = ByteArray(64)
    var objPaletteIndex = 0
    var objPaletteAutoInc = false

    // ── Timing ────────────────────────────────────────────────────────
    private var cycles = 0
    private var mode = 2   // Mode PPU courant (0,1,2,3)
    var frameComplete = false
    private var windowLine = 0  // Compteur interne de ligne fenêtre

    // ── DMA ───────────────────────────────────────────────────────────
    var dmaActive = false
    private var dmaSource = 0
    private var dmaCycles = 0

    // ── HDMA (GBC) ────────────────────────────────────────────────────
    var hdmaSource = 0x0000
    var hdmaDest = 0x8000
    var hdmaLength = 0
    var hdmaMode = 0     // 0=général, 1=HBlank
    var hdmaActive = false
    private var hdmaRemaining = 0

    // Couleurs DMG (vertes d'origine)
    private val dmgColors = intArrayOf(
        0xFFE8F8E0.toInt(),  // blanc/vert clair
        0xFF90C8A0.toInt(),  // vert moyen
        0xFF306850.toInt(),  // vert foncé
        0xFF081820.toInt()   // noir/vert très foncé
    )

    // ─────────────────────────────────────────────────────────────────
    // Accès VRAM / OAM (avec blocage selon mode)
    // ─────────────────────────────────────────────────────────────────
    fun readVram(addr: Int): Int {
        if (mode == 3) return 0xFF  // VRAM inaccessible en mode 3
        val bank = if (mmu.gbcMode) vramBank else 0
        return vram[bank][(addr - 0x8000) and 0x1FFF].toInt() and 0xFF
    }

    fun writeVram(addr: Int, value: Int) {
        if (mode == 3) return
        val bank = if (mmu.gbcMode) vramBank else 0
        vram[bank][(addr - 0x8000) and 0x1FFF] = value.toByte()
    }

    fun readOam(addr: Int): Int {
        if (mode == 2 || mode == 3) return 0xFF
        return oam[addr - 0xFE00].toInt() and 0xFF
    }

    fun writeOam(addr: Int, value: Int) {
        if (mode == 2 || mode == 3) return
        oam[addr - 0xFE00] = value.toByte()
    }

    // ─────────────────────────────────────────────────────────────────
    // Lecture / écriture registres PPU
    // ─────────────────────────────────────────────────────────────────
    fun readReg(addr: Int): Int = when (addr) {
        0xFF40 -> lcdc
        0xFF41 -> (stat and 0xF8) or mode or (if (ly == lyc) 0x04 else 0)
        0xFF42 -> scy; 0xFF43 -> scx
        0xFF44 -> ly;  0xFF45 -> lyc
        0xFF47 -> bgp; 0xFF48 -> obp0; 0xFF49 -> obp1
        0xFF4A -> wy;  0xFF4B -> wx
        0xFF4F -> (if (mmu.gbcMode) vramBank else 0) or 0xFE
        0xFF68 -> (bgPaletteIndex and 0x3F) or (if (bgPaletteAutoInc) 0x80 else 0) or 0x40
        0xFF69 -> if (mmu.gbcMode) bgPaletteRam[bgPaletteIndex and 0x3F].toInt() and 0xFF else 0xFF
        0xFF6A -> (objPaletteIndex and 0x3F) or (if (objPaletteAutoInc) 0x80 else 0) or 0x40
        0xFF6B -> if (mmu.gbcMode) objPaletteRam[objPaletteIndex and 0x3F].toInt() and 0xFF else 0xFF
        else -> 0xFF
    }

    fun writeReg(addr: Int, value: Int) {
        val v = value and 0xFF
        when (addr) {
            0xFF40 -> {
                val lcdWasOn = (lcdc and 0x80) != 0
                lcdc = v
                if (lcdWasOn && (v and 0x80) == 0) {
                    // Éteindre LCD : réinitialiser LY et mode
                    ly = 0; mode = 0; cycles = 0
                    frameBuffer.fill(dmgColors[0])
                    frameComplete = true
                }
            }
            0xFF41 -> stat = (v and 0xF8) or (stat and 0x07)
            0xFF42 -> scy = v; 0xFF43 -> scx = v
            0xFF44 -> Unit  // LY lecture seule
            0xFF45 -> {
                lyc = v
                updateLycStat()
            }
            0xFF46 -> startDma(v)
            0xFF47 -> bgp = v; 0xFF48 -> obp0 = v; 0xFF49 -> obp1 = v
            0xFF4A -> wy = v; 0xFF4B -> wx = v
            0xFF4F -> if (mmu.gbcMode) vramBank = v and 0x01
            0xFF51 -> hdmaSource = (hdmaSource and 0x00FF) or (v shl 8)
            0xFF52 -> hdmaSource = (hdmaSource and 0xFF00) or (v and 0xF0)
            0xFF53 -> hdmaDest = (hdmaDest and 0x00FF) or ((v and 0x1F) shl 8) or 0x8000
            0xFF54 -> hdmaDest = (hdmaDest and 0xFF00) or (v and 0xF0)
            0xFF55 -> startHdma(v)
            0xFF68 -> {
                bgPaletteIndex = v and 0x3F
                bgPaletteAutoInc = (v and 0x80) != 0
            }
            0xFF69 -> {
                if (mmu.gbcMode) {
                    bgPaletteRam[bgPaletteIndex and 0x3F] = v.toByte()
                    if (bgPaletteAutoInc) bgPaletteIndex = (bgPaletteIndex + 1) and 0x3F
                }
            }
            0xFF6A -> {
                objPaletteIndex = v and 0x3F
                objPaletteAutoInc = (v and 0x80) != 0
            }
            0xFF6B -> {
                if (mmu.gbcMode) {
                    objPaletteRam[objPaletteIndex and 0x3F] = v.toByte()
                    if (objPaletteAutoInc) objPaletteIndex = (objPaletteIndex + 1) and 0x3F
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // DMA OAM (160 cycles)
    // ─────────────────────────────────────────────────────────────────
    private fun startDma(value: Int) {
        dmaSource = value shl 8
        dmaActive = true
        dmaCycles = 0
    }

    fun tickDma(cycles: Int) {
        if (!dmaActive) return
        dmaCycles += cycles
        val bytesToCopy = minOf(dmaCycles / 4, 160)
        for (i in 0 until bytesToCopy) {
            oam[i] = mmu.readDma((dmaSource + i) and 0xFFFF).toByte()
        }
        if (dmaCycles >= 640) dmaActive = false
    }

    // ─────────────────────────────────────────────────────────────────
    // HDMA (GBC)
    // ─────────────────────────────────────────────────────────────────
    private fun startHdma(value: Int) {
        if (!mmu.gbcMode) return
        if (hdmaActive && (value and 0x80) == 0) {
            // Arrêt HDMA HBlank
            hdmaActive = false; hdmaLength = hdmaRemaining or 0x80; return
        }
        val len = ((value and 0x7F) + 1) * 0x10
        hdmaMode = (value shr 7) and 0x01
        hdmaRemaining = len
        if (hdmaMode == 0) {
            // Transfert général immédiat
            executeHdma(len)
            hdmaLength = 0xFF
        } else {
            // HBlank DMA
            hdmaActive = true
            hdmaLength = value and 0x7F
        }
    }

    private fun executeHdma(bytes: Int) {
        var src = hdmaSource; var dst = hdmaDest
        repeat(bytes) {
            vram[vramBank][(dst - 0x8000) and 0x1FFF] = mmu.readDma(src).toByte()
            src = (src + 1) and 0xFFFF
            dst = (dst + 1) and 0xFFFF
        }
        hdmaSource = src; hdmaDest = dst
    }

    fun tickHdmaHBlank() {
        if (!hdmaActive || !mmu.gbcMode) return
        executeHdma(0x10)
        hdmaRemaining -= 0x10
        if (hdmaRemaining <= 0) {
            hdmaActive = false
            hdmaLength = 0xFF
        } else {
            hdmaLength = (hdmaRemaining / 0x10 - 1) and 0x7F
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Avance le PPU de `cycles` T-cycles
    // ─────────────────────────────────────────────────────────────────
    fun step(tCycles: Int): Boolean {
        frameComplete = false
        if ((lcdc and 0x80) == 0) return false  // LCD éteint

        tickDma(tCycles)
        cycles += tCycles

        when (mode) {
            2 -> { // OAM Search
                if (cycles >= 80) {
                    cycles -= 80
                    mode = 3
                }
            }
            3 -> { // Pixel Transfer
                if (cycles >= 172) {
                    cycles -= 172
                    renderScanline()
                    mode = 0
                    updateStat()
                    if ((stat and 0x08) != 0) requestStatInt()
                    tickHdmaHBlank()
                }
            }
            0 -> { // HBlank
                if (cycles >= 204) {
                    cycles -= 204
                    ly++
                    if (ly == 144) {
                        mode = 1
                        updateStat()
                        mmu.ifReg = mmu.ifReg or 0x01  // VBlank interrupt
                        if ((stat and 0x10) != 0) requestStatInt()
                        frameComplete = true
                        windowLine = 0
                    } else {
                        mode = 2
                        updateStat()
                        if ((stat and 0x20) != 0) requestStatInt()
                    }
                    updateLycStat()
                }
            }
            1 -> { // VBlank
                if (cycles >= 456) {
                    cycles -= 456
                    ly++
                    if (ly > 153) {
                        ly = 0
                        mode = 2
                        updateStat()
                        if ((stat and 0x20) != 0) requestStatInt()
                    }
                    updateLycStat()
                }
            }
        }
        return frameComplete
    }

    private fun updateStat() {
        stat = (stat and 0xFC) or mode
    }

    private fun updateLycStat() {
        val coincidence = ly == lyc
        stat = if (coincidence) stat or 0x04 else stat and 0xFB
        if (coincidence && (stat and 0x40) != 0) requestStatInt()
    }

    private fun requestStatInt() {
        mmu.ifReg = mmu.ifReg or 0x02
    }

    // ─────────────────────────────────────────────────────────────────
    // Rendu d'une scanline complète
    // ─────────────────────────────────────────────────────────────────
    private fun renderScanline() {
        lineBuffer.fill(0)
        linePriority.fill(false)

        if ((lcdc and 0x01) != 0) renderBackground()
        if ((lcdc and 0x20) != 0) renderWindow()
        if ((lcdc and 0x02) != 0) renderSprites()

        val offset = ly * 160
        lineBuffer.copyInto(frameBuffer, offset, 0, 160)
    }

    // ─────────────────────────────────────────────────────────────────
    // Rendu arrière-plan
    // ─────────────────────────────────────────────────────────────────
    private fun renderBackground() {
        val tileMapBase = if ((lcdc and 0x08) != 0) 0x9C00 else 0x9800
        val tileDataBase = (lcdc and 0x10) != 0  // true = 0x8000 (unsigned), false = 0x8800 (signed)
        val y = (scy + ly) and 0xFF
        val tileRow = y / 8
        val tileY = y % 8

        for (px in 0 until 160) {
            val x = (scx + px) and 0xFF
            val tileCol = x / 8
            val tileX = x % 8

            val mapAddr = tileMapBase + tileRow * 32 + tileCol - 0x8000
            val tileIndex = vram[0][mapAddr].toInt() and 0xFF
            val tileNum = if (tileDataBase) tileIndex else (tileIndex.toByte().toInt() + 256)
            val tileAddr = tileNum * 16 + tileY * 2

            // Attributs GBC (VRAM bank 1)
            val attr = if (mmu.gbcMode) vram[1][mapAddr].toInt() and 0xFF else 0

            val flipX = (attr and 0x20) != 0
            val flipY = (attr and 0x40) != 0
            val bank = if (mmu.gbcMode) (attr shr 3) and 0x01 else 0
            val paletteNum = attr and 0x07
            val bgPriority = (attr and 0x80) != 0

            val tileYFlipped = if (flipY) 7 - tileY else tileY
            val tileXFlipped = if (flipX) 7 - tileX else tileX
            val tileAddrFlip = tileNum * 16 + tileYFlipped * 2

            val lo = vram[bank][tileAddrFlip].toInt() and 0xFF
            val hi = vram[bank][tileAddrFlip + 1].toInt() and 0xFF
            val bit = 7 - tileXFlipped
            val colorIdx = ((lo shr bit) and 1) or (((hi shr bit) and 1) shl 1)

            if (mmu.gbcMode) {
                lineBuffer[px] = getGbcBgColor(paletteNum, colorIdx)
                linePriority[px] = bgPriority && colorIdx != 0
            } else {
                lineBuffer[px] = getDmgBgColor(colorIdx)
                linePriority[px] = colorIdx != 0
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Rendu fenêtre
    // ─────────────────────────────────────────────────────────────────
    private fun renderWindow() {
        if (wy > ly) return
        val tileMapBase = if ((lcdc and 0x40) != 0) 0x9C00 else 0x9800
        val tileDataBase = (lcdc and 0x10) != 0
        val tileY = windowLine % 8
        val tileRow = windowLine / 8
        val wxOffset = wx - 7

        for (px in 0 until 160) {
            if (px < wxOffset) continue
            val tileCol = (px - wxOffset) / 8
            val tileX = (px - wxOffset) % 8

            val mapAddr = tileMapBase + tileRow * 32 + tileCol - 0x8000
            val tileIndex = vram[0][mapAddr].toInt() and 0xFF
            val tileNum = if (tileDataBase) tileIndex else (tileIndex.toByte().toInt() + 256)
            val tileAddr = tileNum * 16 + tileY * 2

            val attr = if (mmu.gbcMode) vram[1][mapAddr].toInt() and 0xFF else 0
            val flipX = (attr and 0x20) != 0
            val flipY = (attr and 0x40) != 0
            val bank = if (mmu.gbcMode) (attr shr 3) and 0x01 else 0
            val paletteNum = attr and 0x07
            val bgPriority = (attr and 0x80) != 0

            val tileYFlipped = if (flipY) 7 - tileY else tileY
            val tileXFlipped = if (flipX) 7 - tileX else tileX
            val tileAddrFlip = tileNum * 16 + tileYFlipped * 2

            val lo = vram[bank][tileAddrFlip].toInt() and 0xFF
            val hi = vram[bank][tileAddrFlip + 1].toInt() and 0xFF
            val bit = 7 - tileXFlipped
            val colorIdx = ((lo shr bit) and 1) or (((hi shr bit) and 1) shl 1)

            if (mmu.gbcMode) {
                lineBuffer[px] = getGbcBgColor(paletteNum, colorIdx)
                linePriority[px] = bgPriority && colorIdx != 0
            } else {
                lineBuffer[px] = getDmgBgColor(colorIdx)
                linePriority[px] = colorIdx != 0
            }
        }
        windowLine++
    }

    // ─────────────────────────────────────────────────────────────────
    // Rendu sprites (OAM)
    // ─────────────────────────────────────────────────────────────────
    private fun renderSprites() {
        val spriteHeight = if ((lcdc and 0x04) != 0) 16 else 8
        data class Sprite(val x: Int, val y: Int, val tile: Int, val attr: Int, val oamIdx: Int)

        // OAM search : collecte max 10 sprites visibles sur cette ligne
        val sprites = mutableListOf<Sprite>()
        for (i in 0 until 40) {
            val base = i * 4
            val sy = oam[base].toInt() and 0xFF - 16
            val sx = oam[base + 1].toInt() and 0xFF - 8
            val tile = oam[base + 2].toInt() and 0xFF
            val attr = oam[base + 3].toInt() and 0xFF
            if (ly >= sy && ly < sy + spriteHeight) {
                sprites.add(Sprite(sx, sy, tile, attr, i))
                if (sprites.size == 10) break
            }
        }

        // Trier : priorité X (DMG) ou ordre OAM (GBC)
        if (!mmu.gbcMode) sprites.sortBy { it.x }

        // Dessiner en ordre inverse (premier sprite = plus haute priorité)
        for (sprite in sprites.reversed()) {
            val tileIndex = if (spriteHeight == 16) sprite.tile and 0xFE else sprite.tile
            val flipX = (sprite.attr and 0x20) != 0
            val flipY = (sprite.attr and 0x40) != 0
            val bgOverSprite = (sprite.attr and 0x80) != 0
            val bank = if (mmu.gbcMode) (sprite.attr shr 3) and 0x01 else 0
            val palette = if (mmu.gbcMode) sprite.attr and 0x07
                          else if ((sprite.attr and 0x10) != 0) 1 else 0

            var spriteRow = ly - sprite.y
            if (flipY) spriteRow = spriteHeight - 1 - spriteRow
            val tileAddr = tileIndex * 16 + spriteRow * 2

            val lo = vram[bank][tileAddr].toInt() and 0xFF
            val hi = vram[bank][tileAddr + 1].toInt() and 0xFF

            for (bit in 7 downTo 0) {
                val px = sprite.x + (7 - bit)
                if (px < 0 || px >= 160) continue
                val xBit = if (flipX) 7 - bit else bit
                val colorIdx = ((lo shr xBit) and 1) or (((hi shr xBit) and 1) shl 1)
                if (colorIdx == 0) continue  // couleur transparente

                // Gestion de la priorité BG/sprite
                if (bgOverSprite && linePriority[px]) continue
                if (!mmu.gbcMode && bgOverSprite && (lineBuffer[px] != dmgColors[0])) continue

                lineBuffer[px] = if (mmu.gbcMode) getGbcObjColor(palette, colorIdx)
                                  else getDmgObjColor(palette, colorIdx)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Couleurs
    // ─────────────────────────────────────────────────────────────────
    private fun getDmgBgColor(idx: Int): Int {
        val shade = (bgp shr (idx * 2)) and 0x03
        return dmgColors[shade]
    }

    private fun getDmgObjColor(palette: Int, idx: Int): Int {
        val pal = if (palette == 0) obp0 else obp1
        val shade = (pal shr (idx * 2)) and 0x03
        return dmgColors[shade]
    }

    private fun getGbcBgColor(palette: Int, colorIdx: Int): Int {
        val base = (palette * 4 + colorIdx) * 2
        val lo = bgPaletteRam[base].toInt() and 0xFF
        val hi = bgPaletteRam[base + 1].toInt() and 0xFF
        return gbcColorToArgb((hi shl 8) or lo)
    }

    private fun getGbcObjColor(palette: Int, colorIdx: Int): Int {
        val base = (palette * 4 + colorIdx) * 2
        val lo = objPaletteRam[base].toInt() and 0xFF
        val hi = objPaletteRam[base + 1].toInt() and 0xFF
        return gbcColorToArgb((hi shl 8) or lo)
    }

    // Conversion couleur GBC 15 bits → ARGB 32 bits
    // Format GBC : 0bbbbbgggggrrrrr (little-endian 2 bytes)
    private fun gbcColorToArgb(color15: Int): Int {
        val r = color15 and 0x1F
        val g = (color15 shr 5) and 0x1F
        val b = (color15 shr 10) and 0x1F
        // Étirer de 5 bits à 8 bits
        val r8 = (r * 255 + 15) / 31
        val g8 = (g * 255 + 15) / 31
        val b8 = (b * 255 + 15) / 31
        return 0xFF000000.toInt() or (r8 shl 16) or (g8 shl 8) or b8
    }

    // ─────────────────────────────────────────────────────────────────
    // Reset
    // ─────────────────────────────────────────────────────────────────
    fun reset() {
        lcdc = 0x91; stat = 0x85; scy = 0; scx = 0
        ly = 0; lyc = 0; bgp = 0xFC; obp0 = 0xFF; obp1 = 0xFF
        wy = 0; wx = 0; cycles = 0; mode = 2; windowLine = 0
        frameComplete = false; dmaActive = false; dmaCycles = 0
        hdmaActive = false; hdmaRemaining = 0; hdmaLength = 0xFF
        vramBank = 0
        bgPaletteIndex = 0; bgPaletteAutoInc = false
        objPaletteIndex = 0; objPaletteAutoInc = false
        vram[0].fill(0); vram[1].fill(0); oam.fill(0)
        bgPaletteRam.fill(0xFF.toByte()); objPaletteRam.fill(0xFF.toByte())
        frameBuffer.fill(0xFFE8F8E0.toInt())
    }

    // ─────────────────────────────────────────────────────────────────
    // Save/Load state
    // ─────────────────────────────────────────────────────────────────
    fun saveState() = PpuState(
        lcdc, stat, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx,
        cycles, mode, windowLine, vramBank,
        bgPaletteIndex, bgPaletteAutoInc, objPaletteIndex, objPaletteAutoInc,
        vram[0].copyOf(), vram[1].copyOf(), oam.copyOf(),
        bgPaletteRam.copyOf(), objPaletteRam.copyOf(),
        frameBuffer.copyOf()
    )

    fun loadState(s: PpuState) {
        lcdc = s.lcdc; stat = s.stat; scy = s.scy; scx = s.scx
        ly = s.ly; lyc = s.lyc; bgp = s.bgp; obp0 = s.obp0; obp1 = s.obp1
        wy = s.wy; wx = s.wx; cycles = s.cycles; mode = s.mode
        windowLine = s.windowLine; vramBank = s.vramBank
        bgPaletteIndex = s.bgPaletteIndex; bgPaletteAutoInc = s.bgPaletteAutoInc
        objPaletteIndex = s.objPaletteIndex; objPaletteAutoInc = s.objPaletteAutoInc
        s.vram0.copyInto(vram[0]); s.vram1.copyInto(vram[1])
        s.oam.copyInto(oam); s.bgPaletteRam.copyInto(bgPaletteRam)
        s.objPaletteRam.copyInto(objPaletteRam); s.frameBuffer.copyInto(frameBuffer)
    }
}

data class PpuState(
    val lcdc: Int, val stat: Int, val scy: Int, val scx: Int,
    val ly: Int, val lyc: Int, val bgp: Int, val obp0: Int, val obp1: Int,
    val wy: Int, val wx: Int, val cycles: Int, val mode: Int, val windowLine: Int,
    val vramBank: Int, val bgPaletteIndex: Int, val bgPaletteAutoInc: Boolean,
    val objPaletteIndex: Int, val objPaletteAutoInc: Boolean,
    val vram0: ByteArray, val vram1: ByteArray, val oam: ByteArray,
    val bgPaletteRam: ByteArray, val objPaletteRam: ByteArray,
    val frameBuffer: IntArray
) : java.io.Serializable
