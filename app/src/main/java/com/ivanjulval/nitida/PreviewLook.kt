package com.ivanjulval.nitida

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import com.ivanjulval.nitida.core.Adjustments

/**
 * Vista previa aproximada del modo en el visor (contraste, brillo, saturación y calidez)
 * con un filtro de color. El resultado final lo calcula PhotoTuner con más precisión.
 * Necesita Android 12+ y que el PreviewView use TextureView (modo COMPATIBLE).
 */
object PreviewLook {

    fun apply(view: View, a: Adjustments) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (a.contrast == 0f && a.brightness == 0f && a.saturation == 0f && a.warmth == 0f) {
            view.setRenderEffect(null)
            return
        }
        val m = ColorMatrix().apply { setSaturation(1f + a.saturation) }

        val c = 1f + a.contrast * 0.5f
        val t = 128f * (1f - c) + a.brightness * 40f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        m.postConcat(ColorMatrix().apply { setScale(1f + 0.12f * a.warmth, 1f, 1f - 0.12f * a.warmth, 1f) })
        view.setRenderEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m)))
    }
}
