package com.ivanjulval.nitida

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/** Panel para elegir el formato de la foto, con su forma, medidas y dónde se usa. */
object FormatSheet {

    private const val ACCENT = 0xFFB4E43A.toInt()

    fun show(
        context: Context,
        current: PhotoFormat,
        exactSize: Boolean,
        onSelect: (PhotoFormat) -> Unit,
        onExactChange: (Boolean) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(28))
        }
        column.addView(TextView(context).apply {
            text = "Formato de la foto"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(context).apply {
            text = "La cámara saca la foto completa y la recorta al formato. En el visor verás la zona que se queda."
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(8))
        })

        for (f in PhotoFormat.entries) {
            val selected = f == current
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    if (selected) {
                        setColor(Color.argb(40, 180, 228, 58))
                        setStroke(dp(2), ACCENT)
                    } else {
                        setColor(Color.argb(18, 255, 255, 255))
                    }
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onSelect(f)
                    dialog.dismiss()
                }
            }

            // Dibujo de la forma del formato
            val box = dp(44)
            val rw = if (f.ratioW == 0) 3 else f.ratioW
            val rh = if (f.ratioW == 0) 4 else f.ratioH
            val shapeW: Int
            val shapeH: Int
            if (rw >= rh) { shapeW = box; shapeH = (box * rh / rw.toFloat()).roundToInt() } else { shapeH = box; shapeW = (box * rw / rh.toFloat()).roundToInt() }
            val holder = FrameLayout(context)
            holder.addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setStroke(dp(2), if (selected) ACCENT else Color.WHITE)
                }
            }, FrameLayout.LayoutParams(shapeW, shapeH, Gravity.CENTER))
            row.addView(holder, LinearLayout.LayoutParams(box, box).apply { marginEnd = dp(14) })

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = "${f.label}  ·  ${f.ratioText}"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                if (selected) setTextColor(ACCENT)
            })
            texts.addView(TextView(context).apply {
                text = f.sizeText
                textSize = 13f
                alpha = 0.85f
            })
            texts.addView(TextView(context).apply {
                text = f.uses
                textSize = 12f
                alpha = 0.6f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }

        column.addView(MaterialSwitch(context).apply {
            text = "Guardar al tamaño exacto para redes (p. ej. 1080 × 1920)"
            isChecked = exactSize
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked -> onExactChange(checked) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(TextView(context).apply {
            text = "Apagado: se guarda recortada pero con toda la resolución, mejor si luego quieres editarla o imprimirla."
            textSize = 12f
            alpha = 0.6f
        })

        dialog.setContentView(NestedScrollView(context).apply { addView(column) })
        dialog.show()
    }
}
