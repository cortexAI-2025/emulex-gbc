package com.emulex.gbc.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.emulex.gbc.core.GameBoy
import com.emulex.gbc.core.Joypad
import kotlin.math.*

/**
 * Vue complète émulant l'aspect physique d'une Game Boy Color.
 *
 * Dessine le boîtier, l'écran, le D-Pad, les boutons A/B,
 * Start/Select, la LED d'alimentation et la grille du haut-parleur.
 * Gère tous les événements tactiles multi-points.
 */
class GameBoyShellView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Émulation ─────────────────────────────────────────────────────
    var gameBoy: GameBoy? = null
    /** Callback (bouton index, pressé) : index = constantes Joypad.BTN_* */
    var onButton: ((Int, Boolean) -> Unit)? = null
    /** Callback tap sur l'écran → afficher menu */
    var onScreenTap: (() -> Unit)? = null

    // Bitmap du rendu GBC (160×144)
    private val screenBitmap = Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888)
    private val screenMatrix = Matrix()

    // ── Géométrie du boîtier (calculée dans onSizeChanged) ────────────
    private var bL = 0f; private var bT = 0f   // origine du corps
    private var bW = 0f; private var bH = 0f   // dimensions du corps

    // Zones de l'écran
    private val bezelRect = RectF()
    private val screenRect = RectF()

    // D-Pad
    private val dpad = PointF()
    private var dpadArm = 0f   // demi-largeur du bras du D-Pad
    private var dpadLen = 0f   // longueur du bras (du centre au bout)

    // Boutons A / B
    private val aBtn = PointF(); private var aBtnR = 0f
    private val bBtn = PointF(); private var bBtnR = 0f

    // Start / Select
    private val startRect = RectF()
    private val selectRect = RectF()

    // LED
    private val ledPos = PointF(); private var ledR = 0f

    // Haut-parleur
    private val speakerDots = mutableListOf<PointF>()
    private var dotR = 0f

    // Bouton menu (petit cercle haut-droite du boîtier)
    private val menuBtn = PointF(); private var menuR = 0f

    // ── État des boutons ──────────────────────────────────────────────
    private val pressed = BooleanArray(8)
    private val btnMap = intArrayOf(
        Joypad.BTN_RIGHT, Joypad.BTN_LEFT, Joypad.BTN_UP, Joypad.BTN_DOWN,
        Joypad.BTN_A, Joypad.BTN_B, Joypad.BTN_START, Joypad.BTN_SELECT
    )

    // ── Paints (pré-calculés dans onSizeChanged) ──────────────────────
    // Corps
    private val bodyPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyRimPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hlPaint       = Paint(Paint.ANTI_ALIAS_FLAG)  // reflet gauche
    // Écran
    private val bezelPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D0820.toInt() }
    private val screenBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101828.toInt() }
    private val screenGlarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    // D-Pad
    private val dpadPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dpadCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dpadArrowPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x50FFFFFF; style = Paint.Style.FILL
    }
    // Boutons A / B
    private val aPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnRimPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000; style = Paint.Style.STROKE
    }
    // Start / Select
    private val ssPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Labels
    private val labelPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallLabel  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8B4FE.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val brandPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B5CF6.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val nintendoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6D28D9.toInt(); textAlign = Paint.Align.CENTER
    }
    // LED
    private val ledPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
    private val ledGlowPaint= Paint(Paint.ANTI_ALIAS_FLAG)
    // Haut-parleur
    private val speakerPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B0764.toInt() }
    // Ombre portée
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70000000.toInt(); style = Paint.Style.FILL
    }
    // Menu
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Chemins réutilisables ─────────────────────────────────────────
    private val bodyPath = Path()
    private val dpadPath = Path()   // croix du D-Pad
    private val arrowPath = Path()  // flèche

    // ─────────────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout(w, h)
        buildPaints()
        buildPaths()
    }

    // ─────────────────────────────────────────────────────────────────
    // Calcul de la géométrie
    // ─────────────────────────────────────────────────────────────────
    private fun layout(vW: Int, vH: Int) {
        // Ratio GBC : 0.556 (80 mm × 144 mm)
        val ratio = 0.556f
        if (vH * ratio <= vW) {
            bH = vH.toFloat(); bW = bH * ratio
        } else {
            bW = vW.toFloat(); bH = bW / ratio
        }
        bL = (vW - bW) / 2f
        bT = (vH - bH) / 2f

        // ── Écran ─────────────────────────────────────────────────────
        val bezelW  = bW * 0.82f
        val bezelH  = bezelW * 1.02f
        val bezelL  = bL + (bW - bezelW) / 2f
        val bezelTop= bT + bH * 0.09f
        bezelRect.set(bezelL, bezelTop, bezelL + bezelW, bezelTop + bezelH)

        val sW = bezelW * 0.84f
        val sH = sW * 144f / 160f
        val sL = bezelL + (bezelW - sW) / 2f
        val sT = bezelTop + (bezelH - sH) / 2f + bezelH * 0.04f
        screenRect.set(sL, sT, sL + sW, sT + sH)

        val sx = screenRect.width() / 160f
        val sy = screenRect.height() / 144f
        screenMatrix.setScale(sx, sy)
        screenMatrix.postTranslate(screenRect.left, screenRect.top)

        // ── D-Pad ─────────────────────────────────────────────────────
        dpad.set(bL + bW * 0.22f, bT + bH * 0.685f)
        dpadArm = bW * 0.075f
        dpadLen = bW * 0.14f

        // ── Boutons A / B (disposition oblique comme sur la vraie GBC)
        aBtnR = bW * 0.075f
        bBtnR = aBtnR * 0.92f
        aBtn.set(bL + bW * 0.80f, bT + bH * 0.645f)
        bBtn.set(bL + bW * 0.675f, bT + bH * 0.705f)

        // ── Start / Select ────────────────────────────────────────────
        val ssRW = bW * 0.12f
        val ssRH = bH * 0.021f
        val ssY  = bT + bH * 0.838f
        selectRect.set(bL + bW * 0.345f - ssRW, ssY - ssRH,
                       bL + bW * 0.345f + ssRW, ssY + ssRH)
        startRect.set( bL + bW * 0.570f - ssRW, ssY - ssRH,
                       bL + bW * 0.570f + ssRW, ssY + ssRH)

        // ── LED ───────────────────────────────────────────────────────
        ledR  = bW * 0.013f
        ledPos.set(bL + bW * 0.13f, bezelRect.bottom + bH * 0.008f)

        // ── Haut-parleur (grille oblique, 4×4 points) ─────────────────
        speakerDots.clear()
        dotR  = bW * 0.011f
        val sp = bW * 0.038f
        val s0x = bL + bW * 0.625f
        val s0y = bT + bH * 0.765f
        repeat(4) { row ->
            repeat(4) { col ->
                val ox = col * sp + (row % 2) * sp * 0.5f
                val oy = row * sp * 0.85f
                speakerDots.add(PointF(s0x + ox, s0y + oy))
            }
        }

        // ── Bouton menu ───────────────────────────────────────────────
        menuR = bW * 0.04f
        menuBtn.set(bL + bW - menuR * 2f, bT + bH * 0.05f)
    }

    // ─────────────────────────────────────────────────────────────────
    // Initialisation des paints (shaders = taille dépendante)
    // ─────────────────────────────────────────────────────────────────
    private fun buildPaints() {
        // Corps du boîtier — dégradé violet profond
        bodyPaint.shader = LinearGradient(
            bL, bT, bL + bW, bT + bH,
            intArrayOf(0xFF9333EA.toInt(), 0xFF7C3AED.toInt(),
                       0xFF6D28D9.toInt(), 0xFF4C1D95.toInt()),
            floatArrayOf(0f, 0.25f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        // Liseré du corps
        bodyRimPaint.style = Paint.Style.STROKE
        bodyRimPaint.strokeWidth = bW * 0.012f
        bodyRimPaint.shader = LinearGradient(
            bL, bT, bL + bW * 0.5f, bT,
            intArrayOf(0xFFB197FC.toInt(), 0xFF7C3AED.toInt(), 0xFF4C1D95.toInt()),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        // Reflet gauche (brillance)
        hlPaint.shader = LinearGradient(
            bL, bT, bL + bW * 0.15f, bT,
            0x25FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        // Reflet bas du boîtier
        screenGlarePaint.shader = LinearGradient(
            screenRect.left, screenRect.top,
            screenRect.left + screenRect.width() * 0.4f,
            screenRect.top + screenRect.height() * 0.4f,
            0x18FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        // D-Pad
        refreshDpadPaint()
        refreshABPaints()
        // Start / Select
        ssPaint.color = 0xFF374151.toInt()
        // LED glow
        ledGlowPaint.shader = RadialGradient(
            ledPos.x, ledPos.y, ledR * 3.5f,
            intArrayOf(0x9022C55E.toInt(), 0x3022C55E.toInt(), 0x0022C55E.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // Bouton menu
        menuPaint.shader = RadialGradient(
            menuBtn.x, menuBtn.y, menuR,
            0xFF5B21B6.toInt(), 0xFF3B0764.toInt(),
            Shader.TileMode.CLAMP
        )
        // Tailles de texte
        labelPaint.textSize  = aBtnR * 0.85f
        smallLabel.textSize  = bW * 0.032f
        brandPaint.textSize  = bW * 0.052f
        nintendoPaint.textSize = bW * 0.034f
        btnRimPaint.strokeWidth = bW * 0.007f
    }

    private fun refreshDpadPaint() {
        val isPressed = pressed.take(4).any { it }
        dpadPaint.shader = RadialGradient(
            dpad.x - dpadLen * 0.2f, dpad.y - dpadLen * 0.2f,
            dpadLen * 1.8f,
            if (isPressed) 0xFF312E81.toInt() else 0xFF1E1B4B.toInt(),
            if (isPressed) 0xFF1E1B4B.toInt() else 0xFF0D0820.toInt(),
            Shader.TileMode.CLAMP
        )
        dpadCenterPaint.color = if (isPressed) 0xFF3730A3.toInt() else 0xFF2D2270.toInt()
    }

    private fun refreshABPaints() {
        aPaint.shader = RadialGradient(
            aBtn.x - aBtnR * 0.35f, aBtn.y - aBtnR * 0.35f, aBtnR * 1.4f,
            if (pressed[4]) 0xFFF87171.toInt() else 0xFFEF4444.toInt(),
            if (pressed[4]) 0xFFDC2626.toInt() else 0xFF991B1B.toInt(),
            Shader.TileMode.CLAMP
        )
        bPaint.shader = RadialGradient(
            bBtn.x - bBtnR * 0.35f, bBtn.y - bBtnR * 0.35f, bBtnR * 1.4f,
            if (pressed[5]) 0xFFF87171.toInt() else 0xFFEF4444.toInt(),
            if (pressed[5]) 0xFFDC2626.toInt() else 0xFF991B1B.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Pré-calcul des chemins (forme du boîtier, D-Pad)
    // ─────────────────────────────────────────────────────────────────
    private fun buildPaths() {
        val r = bW * 0.09f     // rayon des coins principaux
        val rBot = bW * 0.13f  // rayon plus grand pour les coins bas
        bodyPath.reset()
        bodyPath.moveTo(bL + r, bT)
        bodyPath.lineTo(bL + bW - r, bT)
        bodyPath.arcTo(RectF(bL + bW - 2*r, bT, bL + bW, bT + 2*r), -90f, 90f)
        bodyPath.lineTo(bL + bW, bT + bH - rBot)
        bodyPath.arcTo(RectF(bL + bW - 2*rBot, bT + bH - 2*rBot, bL + bW, bT + bH), 0f, 90f)
        bodyPath.lineTo(bL + rBot, bT + bH)
        bodyPath.arcTo(RectF(bL, bT + bH - 2*rBot, bL + 2*rBot, bT + bH), 90f, 90f)
        bodyPath.lineTo(bL, bT + r)
        bodyPath.arcTo(RectF(bL, bT, bL + 2*r, bT + 2*r), 180f, 90f)
        bodyPath.close()

        buildDpadPath()
    }

    private fun buildDpadPath() {
        val cx = dpad.x; val cy = dpad.y
        val a = dpadArm; val l = dpadLen
        dpadPath.reset()
        // Croix = deux rectangles arrondis superposés
        // Vertical
        dpadPath.addRoundRect(cx-a, cy-l, cx+a, cy+l, a*0.25f, a*0.25f, Path.Direction.CW)
        // Horizontal
        dpadPath.addRoundRect(cx-l, cy-a, cx+l, cy+a, a*0.25f, a*0.25f, Path.Direction.CW)
        dpadPath.fillType = Path.FillType.WINDING
    }

    // ─────────────────────────────────────────────────────────────────
    // Dessin principal
    // ─────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        if (bW == 0f) return
        drawShadow(canvas)
        drawBody(canvas)
        drawBezelAndScreen(canvas)
        drawDpad(canvas)
        drawABButtons(canvas)
        drawStartSelect(canvas)
        drawLed(canvas)
        drawSpeaker(canvas)
        drawBranding(canvas)
        drawMenuButton(canvas)
    }

    // ── Ombre portée du boîtier ───────────────────────────────────────
    private fun drawShadow(canvas: Canvas) {
        val offset = bW * 0.03f
        canvas.save()
        canvas.translate(offset, offset * 1.5f)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x60000000.toInt()
            maskFilter = BlurMaskFilter(bW * 0.04f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(bodyPath, sp)
        canvas.restore()
    }

    // ── Boîtier principal ─────────────────────────────────────────────
    private fun drawBody(canvas: Canvas) {
        canvas.drawPath(bodyPath, bodyPaint)
        // Liseret subtil
        canvas.drawPath(bodyPath, bodyRimPaint)
        // Reflet latéral gauche
        canvas.save()
        canvas.clipPath(bodyPath)
        canvas.drawRect(bL, bT, bL + bW, bT + bH, hlPaint)
        canvas.restore()
        // Rainures des poignées (lignes horizontales subtiles)
        val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x15000000; style = Paint.Style.STROKE; strokeWidth = bW * 0.003f
        }
        val gripCount = 5
        val gripStart = bT + bH * 0.57f
        val gripSpacing = bH * 0.025f
        for (i in 0 until gripCount) {
            val y = gripStart + i * gripSpacing
            canvas.drawLine(bL + bW * 0.03f, y, bL + bW * 0.12f, y, gripPaint)
            canvas.drawLine(bL + bW * 0.88f, y, bL + bW * 0.97f, y, gripPaint)
        }
    }

    // ── Écran ─────────────────────────────────────────────────────────
    private fun drawBezelAndScreen(canvas: Canvas) {
        // Biseau du bezel (rebord en creux)
        val outerBezel = RectF(bezelRect)
        val outerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A0D3D.toInt()
        }
        val bR = bW * 0.04f
        canvas.drawRoundRect(outerBezel, bR, bR, outerBezelPaint)

        // Bordure intérieure plus sombre
        val innerBezel = RectF(
            bezelRect.left + bW * 0.015f, bezelRect.top + bH * 0.008f,
            bezelRect.right - bW * 0.015f, bezelRect.bottom - bH * 0.008f
        )
        bezelPaint.color = 0xFF0D0820.toInt()
        canvas.drawRoundRect(innerBezel, bR * 0.7f, bR * 0.7f, bezelPaint)

        // Surface de l'écran (fond éteint)
        canvas.drawRect(screenRect, screenBgPaint)

        // Rendu du jeu
        val gb = gameBoy
        if (gb != null) {
            screenBitmap.setPixels(gb.frameBuffer, 0, 160, 0, 0, 160, 144)
            canvas.drawBitmap(screenBitmap, screenMatrix, screenBitmapPaint)
        } else {
            // Écran éteint — dégradé subtil
            val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    screenRect.left, screenRect.top, screenRect.left, screenRect.bottom,
                    0xFF0F1929.toInt(), 0xFF0A1020.toInt(), Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(screenRect, offPaint)
            // Message centré
            val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x3089B4FA.toInt(); textAlign = Paint.Align.CENTER
                textSize = screenRect.height() * 0.08f
            }
            canvas.drawText("GB COLOR", screenRect.centerX(),
                screenRect.centerY() - msgPaint.textSize, msgPaint)
            canvas.drawText("EMULATOR", screenRect.centerX(),
                screenRect.centerY() + msgPaint.textSize * 0.2f, msgPaint)
        }

        // Reflet de l'écran (coin haut-gauche)
        canvas.drawRect(screenRect, screenGlarePaint)

        // Cadre de l'écran (liseré)
        val screenBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x40000000
            strokeWidth = bW * 0.005f
        }
        canvas.drawRect(screenRect, screenBorder)

        // Label "GAME BOY" au-dessus du bezel
        brandPaint.textSize = bW * 0.052f
        canvas.drawText("GAME BOY", bL + bW * 0.5f, bezelRect.top - bH * 0.025f, brandPaint)
        brandPaint.color = 0xFFA78BFA.toInt()
        brandPaint.textSize = bW * 0.032f
        // "COLOR" en caractères italiques colorés (rouge+bleu comme sur la vraie)
        val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = bW * 0.035f
        }
        // C-O-L-O-R avec couleurs alternées
        val word = "COLOR"
        val colors = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF8000.toInt(), 0xFFFFFF00.toInt(),
            0xFF00CC00.toInt(), 0xFF0088FF.toInt()
        )
        val totalW = colorPaint.measureText(word)
        var cx = bL + bW * 0.5f - totalW / 2f
        for ((i, ch) in word.withIndex()) {
            colorPaint.color = colors[i]
            val cw = colorPaint.measureText(ch.toString())
            canvas.drawText(ch.toString(), cx + cw / 2f,
                bezelRect.top - bH * 0.006f, colorPaint)
            cx += cw
        }
    }

    // ── D-Pad ─────────────────────────────────────────────────────────
    private fun drawDpad(canvas: Canvas) {
        // Ombre
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x60000000; maskFilter = BlurMaskFilter(dpadArm * 0.8f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(dpadArm * 0.15f, dpadArm * 0.2f)
        canvas.drawPath(dpadPath, sp)
        canvas.restore()

        // Corps du D-Pad
        refreshDpadPaint()
        canvas.drawPath(dpadPath, dpadPaint)

        // Centre (légèrement surélevé)
        val a = dpadArm
        canvas.drawRoundRect(dpad.x-a, dpad.y-a, dpad.x+a, dpad.y+a,
                             a*0.2f, a*0.2f, dpadCenterPaint)

        // Flèches directionnelles
        val arrowR = dpadArm * 0.5f
        canvas.save()
        canvas.translate(dpad.x, dpad.y - dpadLen * 0.72f)
        canvas.drawPath(makeArrow(arrowR, 0f), dpadArrowPaint)
        canvas.restore()
        canvas.save()
        canvas.translate(dpad.x, dpad.y + dpadLen * 0.72f)
        canvas.drawPath(makeArrow(arrowR, 180f), dpadArrowPaint)
        canvas.restore()
        canvas.save()
        canvas.translate(dpad.x - dpadLen * 0.72f, dpad.y)
        canvas.drawPath(makeArrow(arrowR, 270f), dpadArrowPaint)
        canvas.restore()
        canvas.save()
        canvas.translate(dpad.x + dpadLen * 0.72f, dpad.y)
        canvas.drawPath(makeArrow(arrowR, 90f), dpadArrowPaint)
        canvas.restore()
    }

    /** Petit triangle isocèle centré en (0,0) pointant vers le haut, tourné d'`angleDeg` degrés. */
    private fun makeArrow(size: Float, angleDeg: Float): Path {
        val p = Path()
        p.moveTo(0f, -size)
        p.lineTo(size * 0.65f, size * 0.6f)
        p.lineTo(-size * 0.65f, size * 0.6f)
        p.close()
        val m = Matrix()
        m.postRotate(angleDeg)
        p.transform(m)
        return p
    }

    // ── Boutons A / B ─────────────────────────────────────────────────
    private fun drawABButtons(canvas: Canvas) {
        refreshABPaints()

        // Ombres
        val shadowB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x70000000
            maskFilter = BlurMaskFilter(aBtnR * 0.4f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(bBtn.x, bBtn.y + bBtnR * 0.15f, bBtnR, shadowB)
        canvas.drawCircle(aBtn.x, aBtn.y + aBtnR * 0.15f, aBtnR, shadowB)

        // Bouton B
        canvas.drawCircle(bBtn.x, bBtn.y, bBtnR, bPaint)
        canvas.drawCircle(bBtn.x, bBtn.y, bBtnR, btnRimPaint)
        // Reflet
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                bBtn.x - bBtnR*0.4f, bBtn.y - bBtnR*0.4f, bBtnR*0.7f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(bBtn.x, bBtn.y, bBtnR, refPaint)
        // Label B
        labelPaint.textSize = bBtnR * 0.90f
        canvas.save()
        canvas.rotate(-30f, bBtn.x, bBtn.y)
        canvas.drawText("B", bBtn.x, bBtn.y + labelPaint.textSize * 0.37f, labelPaint)
        canvas.restore()

        // Bouton A
        canvas.drawCircle(aBtn.x, aBtn.y, aBtnR, aPaint)
        canvas.drawCircle(aBtn.x, aBtn.y, aBtnR, btnRimPaint)
        val refPA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                aBtn.x - aBtnR*0.4f, aBtn.y - aBtnR*0.4f, aBtnR*0.7f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(aBtn.x, aBtn.y, aBtnR, refPA)
        labelPaint.textSize = aBtnR * 0.90f
        canvas.save()
        canvas.rotate(-30f, aBtn.x, aBtn.y)
        canvas.drawText("A", aBtn.x, aBtn.y + labelPaint.textSize * 0.37f, labelPaint)
        canvas.restore()
    }

    // ── Start / Select ────────────────────────────────────────────────
    private fun drawStartSelect(canvas: Canvas) {
        val r = selectRect.height() / 2f

        // Select
        ssPaint.color = if (pressed[7]) 0xFF6B7280.toInt() else 0xFF374151.toInt()
        canvas.drawRoundRect(selectRect, r, r, ssPaint)
        // reflet
        val selRef = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(selectRect.left, selectRect.top,
                selectRect.left, selectRect.centerY(),
                0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(selectRect, r, r, selRef)

        // Start
        ssPaint.color = if (pressed[6]) 0xFF6B7280.toInt() else 0xFF374151.toInt()
        canvas.drawRoundRect(startRect, r, r, ssPaint)
        val stRef = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(startRect.left, startRect.top,
                startRect.left, startRect.centerY(),
                0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(startRect, r, r, stRef)

        // Labels
        smallLabel.textSize = bW * 0.028f
        smallLabel.color = 0xFFB4A4E0.toInt()
        canvas.drawText("SELECT", selectRect.centerX(),
            selectRect.bottom + bH * 0.018f, smallLabel)
        canvas.drawText("START", startRect.centerX(),
            startRect.bottom + bH * 0.018f, smallLabel)
    }

    // ── LED ───────────────────────────────────────────────────────────
    private fun drawLed(canvas: Canvas) {
        // Glow externe
        canvas.drawCircle(ledPos.x, ledPos.y, ledR * 3.5f, ledGlowPaint)
        // Corps de la LED
        canvas.drawCircle(ledPos.x, ledPos.y, ledR, ledPaint)
        // Reflet
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80BBFFD5.toInt()
        }
        canvas.drawCircle(ledPos.x - ledR*0.3f, ledPos.y - ledR*0.3f, ledR*0.45f, refPaint)
        // Label
        smallLabel.textSize = bW * 0.022f
        smallLabel.color = 0xFF86EFAC.toInt()
        canvas.drawText("POWER", ledPos.x + ledR * 3f, ledPos.y + smallLabel.textSize * 0.4f, smallLabel)
    }

    // ── Haut-parleur ─────────────────────────────────────────────────
    private fun drawSpeaker(canvas: Canvas) {
        // Label
        smallLabel.textSize = bW * 0.024f
        smallLabel.color = 0x608B5CF6.toInt()
        canvas.drawText("♪", bL + bW * 0.85f, bT + bH * 0.76f, smallLabel)

        for (dot in speakerDots) {
            canvas.drawCircle(dot.x, dot.y, dotR, speakerPaint)
            // Reflet sur chaque trou
            val rf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x15FFFFFF }
            canvas.drawCircle(dot.x - dotR*0.3f, dot.y - dotR*0.3f, dotR*0.5f, rf)
        }
    }

    // ── Logo Nintendo / branding ──────────────────────────────────────
    private fun drawBranding(canvas: Canvas) {
        nintendoPaint.textSize = bW * 0.036f
        nintendoPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        canvas.drawText("Nintendo", bL + bW * 0.5f, bT + bH * 0.962f, nintendoPaint)
    }

    // ── Bouton menu ───────────────────────────────────────────────────
    private fun drawMenuButton(canvas: Canvas) {
        canvas.drawCircle(menuBtn.x, menuBtn.y, menuR, menuPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8B4FE.toInt() }
        val d = menuR * 0.22f
        for (i in -1..1) {
            canvas.drawCircle(menuBtn.x, menuBtn.y + i * d * 2.2f, d, dotPaint)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Mise à jour de l'écran depuis le thread d'émulation
    // ─────────────────────────────────────────────────────────────────
    fun updateFrame() {
        postInvalidate()
    }

    // ─────────────────────────────────────────────────────────────────
    // Gestion des événements tactiles
    // ─────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val newPressed = BooleanArray(8)

        if (action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                val tx = event.getX(i); val ty = event.getY(i)
                checkDpadHit(tx, ty, newPressed)
                if (circHit(tx, ty, aBtn, aBtnR * 1.4f)) newPressed[4] = true
                if (circHit(tx, ty, bBtn, bBtnR * 1.4f)) newPressed[5] = true
                if (rectHit(tx, ty, startRect, 2.0f)) newPressed[6] = true
                if (rectHit(tx, ty, selectRect, 2.0f)) newPressed[7] = true

                // Tap sur le menu button
                if (action == MotionEvent.ACTION_DOWN &&
                    circHit(tx, ty, menuBtn, menuR * 1.5f)) {
                    onScreenTap?.invoke()
                }
                // Tap sur l'écran → menu
                if (action == MotionEvent.ACTION_DOWN &&
                    screenRect.contains(tx, ty)) {
                    onScreenTap?.invoke()
                }
            }
        }

        var changed = false
        for (i in 0 until 8) {
            if (newPressed[i] != pressed[i]) {
                pressed[i] = newPressed[i]
                onButton?.invoke(btnMap[i], pressed[i])
                changed = true
            }
        }
        if (changed) {
            refreshDpadPaint()
            refreshABPaints()
            invalidate()
        }
        return true
    }

    private fun checkDpadHit(x: Float, y: Float, out: BooleanArray) {
        val dx = x - dpad.x; val dy = y - dpad.y
        val a = dpadArm; val l = dpadLen
        if (abs(dx) <= l && abs(dy) <= a) {
            if (dx > a) out[0] = true   // RIGHT
            if (dx < -a) out[1] = true  // LEFT
        }
        if (abs(dy) <= l && abs(dx) <= a) {
            if (dy < -a) out[2] = true  // UP
            if (dy > a) out[3] = true   // DOWN
        }
    }

    private fun circHit(x: Float, y: Float, c: PointF, r: Float): Boolean {
        val dx = x - c.x; val dy = y - c.y
        return dx * dx + dy * dy <= r * r
    }

    private fun rectHit(x: Float, y: Float, r: RectF, expand: Float): Boolean {
        val ex = (r.width() * (expand - 1f)) / 2f
        val ey = (r.height() * (expand - 1f)) / 2f
        return x >= r.left - ex && x <= r.right + ex &&
               y >= r.top - ey && y <= r.bottom + ey
    }
}
