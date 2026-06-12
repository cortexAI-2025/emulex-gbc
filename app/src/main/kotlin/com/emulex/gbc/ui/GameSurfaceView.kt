package com.emulex.gbc.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.emulex.gbc.core.GameBoy

/**
 * SurfaceView dédié au rendu de l'écran Game Boy.
 * Rendu passif : appelé explicitement via drawFrame() depuis le thread d'émulation.
 * Met à l'échelle le buffer 160×144 vers la taille de la vue avec gestion du ratio.
 */
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val bitmap = Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false  // pixel sharp par défaut
    }
    private val matrix = Matrix()
    private var gameBoy: GameBoy? = null
    var surfaceReady = false
        private set

    // Option d'interpolation bilinéaire (filtre doux)
    var enableFilter: Boolean = false
        set(value) {
            field = value
            paint.isFilterBitmap = value
        }

    init {
        holder.addCallback(this)
        // Optimisation : garder le format de pixel par défaut
        holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
    }

    fun attachGameBoy(gb: GameBoy) {
        gameBoy = gb
    }

    // ─────────────────────────────────────────────────────────────────
    // SurfaceHolder.Callback
    // ─────────────────────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        updateMatrix(width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateMatrix(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun updateMatrix(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        // Maintient le ratio 10:9 (160:144) avec letterbox/pillarbox
        val scaleX = viewW / 160f
        val scaleY = viewH / 144f
        val scale = minOf(scaleX, scaleY)
        val offsetX = (viewW - 160f * scale) / 2f
        val offsetY = (viewH - 144f * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
    }

    // ─────────────────────────────────────────────────────────────────
    // Rendu d'une frame — appelé depuis le thread d'émulation
    // ─────────────────────────────────────────────────────────────────
    fun drawFrame() {
        if (!surfaceReady) return
        val gb = gameBoy ?: return

        // Copier les pixels du frame buffer dans le bitmap
        bitmap.setPixels(gb.frameBuffer, 0, 160, 0, 0, 160, 144)

        val canvas: Canvas = try {
            holder.lockCanvas() ?: return
        } catch (_: Exception) {
            return
        }
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bitmap, matrix, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // Dessin du bitmap préalablement chargé (pour les refreshs UI)
    fun drawCurrentBitmap() {
        if (!surfaceReady) return
        val canvas: Canvas = try {
            holder.lockCanvas() ?: return
        } catch (_: Exception) {
            return
        }
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bitmap, matrix, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
