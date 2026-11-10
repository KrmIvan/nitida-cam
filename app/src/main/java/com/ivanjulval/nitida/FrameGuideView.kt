package com.ivanjulval.nitida

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Oscurece en el visor lo que quedará fuera del formato elegido (9:16, 4:5...).
 * Supone que el visor muestra la imagen 3:4 centrada (PreviewView en fitCenter, pantalla en vertical).
 */
class FrameGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var format: PhotoFormat = PhotoFormat.FULL
        set(value) { field = value; invalidate() }

    /** true si el móvil se sostiene en horizontal: la foto final está girada respecto al visor. */
    var landscape: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val density = resources.displayMetrics.density
    private val shade = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(220, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
    }
    private val content = RectF()
    private val crop = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (format.ratioW == 0 || width == 0 || height == 0) return

        // Zona donde se ve la imagen de la cámara (3:4 centrada)
        val w = width.toFloat()
        val h = height.toFloat()
        val cw: Float
        val ch: Float
        if (w / h > 0.75f) { ch = h; cw = h * 0.75f } else { cw = w; ch = w / 0.75f }
        content.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)

        // Proporción del recorte vista en el visor (invertida si el móvil está en horizontal)
        val aspect = if (landscape) format.ratioH.toFloat() / format.ratioW else format.ratioW.toFloat() / format.ratioH
        val rw: Float
        val rh: Float
        if (cw / ch > aspect) { rh = ch; rw = ch * aspect } else { rw = cw; rh = cw / aspect }
        crop.set(content.centerX() - rw / 2f, content.centerY() - rh / 2f, content.centerX() + rw / 2f, content.centerY() + rh / 2f)

        // Sombra fuera del recorte (cuatro bandas)
        canvas.drawRect(content.left, content.top, content.right, crop.top, shade)
        canvas.drawRect(content.left, crop.bottom, content.right, content.bottom, shade)
        canvas.drawRect(content.left, crop.top, crop.left, crop.bottom, shade)
        canvas.drawRect(crop.right, crop.top, content.right, crop.bottom, shade)
        canvas.drawRect(crop, border)

        val label = "${format.label} · ${format.ratioText}"
        val pad = 8f * density
        val y = min(crop.bottom - pad, crop.top + pad + text.textSize)
        canvas.drawText(label, crop.left + pad, y, text)
    }
}
