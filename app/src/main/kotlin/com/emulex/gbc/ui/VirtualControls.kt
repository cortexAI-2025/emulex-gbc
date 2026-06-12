package com.emulex.gbc.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.emulex.gbc.core.Joypad
import com.emulex.gbc.core.GameBoy

/**
 * Vue superposée des contrôles tactiles virtuels.
 * D-Pad (gauche), boutons A/B (droite), Start/Select (bas centre).
 */
class VirtualControls @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var gameBoy: GameBoy? = null
    var overlayAlpha: Float = 0.6f

    // ── Géométrie (recalculée à chaque changement de taille) ──────────
    private var dpadCenterX = 0f; private var dpadCenterY = 0f
    private var dpadRadius = 0f; private var dpadArmSize = 0f
    private var aBtnX = 0f; private var aBtnY = 0f
    private var bBtnX = 0f; private var bBtnY = 0f
    private var startX = 0f; private var startY = 0f
    private var selectX = 0f; private var selectY = 0f
    private var btnRadius = 0f
    private var smallBtnRadius = 0f

    // ── Peinture ──────────────────────────────────────────────────────
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pressedColor = Color.argb(220, 255, 255, 255)
    private val normalColor = Color.argb(120, 100, 100, 100)

    // ── État boutons pressés ──────────────────────────────────────────
    private val pressed = BooleanArray(8)
    // Mapping index → Joypad constante
    private val btnMap = intArrayOf(
        Joypad.BTN_RIGHT, Joypad.BTN_LEFT, Joypad.BTN_UP, Joypad.BTN_DOWN,
        Joypad.BTN_A, Joypad.BTN_B, Joypad.BTN_START, Joypad.BTN_SELECT
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val dp = resources.displayMetrics.density

        btnRadius = w * 0.075f
        smallBtnRadius = w * 0.045f
        dpadRadius = w * 0.13f
        dpadArmSize = dpadRadius * 0.5f

        // D-Pad (coin bas-gauche)
        dpadCenterX = w * 0.18f
        dpadCenterY = h * 0.72f

        // Boutons A/B (coin bas-droite)
        aBtnX = w * 0.85f; aBtnY = h * 0.65f
        bBtnX = w * 0.72f; bBtnY = h * 0.75f

        // Start/Select (centre bas)
        startX = w * 0.60f; startY = h * 0.88f
        selectX = w * 0.40f; selectY = h * 0.88f

        textPaint.textSize = btnRadius * 0.7f
    }

    override fun onDraw(canvas: Canvas) {
        val count = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
            (overlayAlpha * 255).toInt())

        drawDpad(canvas)
        drawButton(canvas, aBtnX, aBtnY, btnRadius, "A", pressed[4])
        drawButton(canvas, bBtnX, bBtnY, btnRadius, "B", pressed[5])
        drawSmallButton(canvas, startX, startY, smallBtnRadius, "START", pressed[6])
        drawSmallButton(canvas, selectX, selectY, smallBtnRadius, "SEL", pressed[7])

        canvas.restoreToCount(count)
    }

    private fun drawDpad(canvas: Canvas) {
        val cx = dpadCenterX; val cy = dpadCenterY; val a = dpadArmSize
        btnPaint.color = if (pressed[2]) pressedColor else normalColor  // UP
        canvas.drawRect(cx - a * 0.5f, cy - dpadRadius, cx + a * 0.5f, cy, btnPaint)
        btnPaint.color = if (pressed[3]) pressedColor else normalColor  // DOWN
        canvas.drawRect(cx - a * 0.5f, cy, cx + a * 0.5f, cy + dpadRadius, btnPaint)
        btnPaint.color = if (pressed[1]) pressedColor else normalColor  // LEFT
        canvas.drawRect(cx - dpadRadius, cy - a * 0.5f, cx, cy + a * 0.5f, btnPaint)
        btnPaint.color = if (pressed[0]) pressedColor else normalColor  // RIGHT
        canvas.drawRect(cx, cy - a * 0.5f, cx + dpadRadius, cy + a * 0.5f, btnPaint)
        // Centre
        btnPaint.color = normalColor
        canvas.drawRect(cx - a * 0.5f, cy - a * 0.5f, cx + a * 0.5f, cy + a * 0.5f, btnPaint)

        textPaint.textSize = dpadArmSize * 0.8f
        canvas.drawText("▲", cx, cy - dpadRadius + dpadArmSize * 0.9f, textPaint)
        canvas.drawText("▼", cx, cy + dpadRadius - dpadArmSize * 0.1f, textPaint)
        canvas.drawText("◀", cx - dpadRadius + dpadArmSize * 0.2f, cy + dpadArmSize * 0.3f, textPaint)
        canvas.drawText("▶", cx + dpadRadius - dpadArmSize * 0.2f, cy + dpadArmSize * 0.3f, textPaint)
    }

    private fun drawButton(canvas: Canvas, x: Float, y: Float, r: Float, label: String, isPressed: Boolean) {
        btnPaint.color = if (isPressed) pressedColor else normalColor
        canvas.drawCircle(x, y, r, btnPaint)
        textPaint.textSize = r * 0.7f
        canvas.drawText(label, x, y + textPaint.textSize * 0.35f, textPaint)
    }

    private fun drawSmallButton(canvas: Canvas, x: Float, y: Float, r: Float, label: String, isPressed: Boolean) {
        btnPaint.color = if (isPressed) pressedColor else normalColor
        val rX = r * 2.5f; val rY = r * 0.7f
        canvas.drawRoundRect(x - rX, y - rY, x + rX, y + rY, rY, rY, btnPaint)
        textPaint.textSize = rY * 1.2f
        canvas.drawText(label, x, y + textPaint.textSize * 0.35f, textPaint)
    }

    // ─────────────────────────────────────────────────────────────────
    // Gestion du toucher
    // ─────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val newPressed = BooleanArray(8)

        // Collecter tous les pointeurs actifs
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                val tx = event.getX(i); val ty = event.getY(i)
                testDpad(tx, ty, newPressed)
                if (dist(tx, ty, aBtnX, aBtnY) < btnRadius) newPressed[4] = true
                if (dist(tx, ty, bBtnX, bBtnY) < btnRadius) newPressed[5] = true
                if (dist(tx, ty, startX, startY) < smallBtnRadius * 3f) newPressed[6] = true
                if (dist(tx, ty, selectX, selectY) < smallBtnRadius * 3f) newPressed[7] = true
            }
        }

        // Mettre à jour GameBoy
        val gb = gameBoy
        for (i in 0 until 8) {
            if (newPressed[i] != pressed[i]) {
                pressed[i] = newPressed[i]
                gb?.setButton(btnMap[i], pressed[i])
            }
        }

        invalidate()
        return true
    }

    private fun testDpad(x: Float, y: Float, out: BooleanArray) {
        val dx = x - dpadCenterX; val dy = y - dpadCenterY
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist > dpadRadius * 1.1f) return
        val half = dpadArmSize * 0.5f
        if (dy < -half && dist < dpadRadius) out[2] = true  // UP
        if (dy > half && dist < dpadRadius) out[3] = true   // DOWN
        if (dx < -half && dist < dpadRadius) out[1] = true  // LEFT
        if (dx > half && dist < dpadRadius) out[0] = true   // RIGHT
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
