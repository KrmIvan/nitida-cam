package com.ivanjulval.nitida

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.ivanjulval.nitida.core.Adjustments
import java.util.Locale
import kotlin.math.roundToInt

/** Panel inferior con un deslizador por ajuste. Los cambios se aplican y guardan al momento. */
object AdjustmentsSheet {

    /** Un deslizador. Todos trabajan con valores enteros para evitar problemas de redondeo. */
    private class Spec(
        val name: String,
        val from: Float,
        val to: Float,
        val get: (Adjustments) -> Float,
        val set: (Adjustments, Float) -> Unit,
        val format: (Float) -> String,
    )

    private fun signed(v: Float) = if (v > 0) "+${v.roundToInt()}" else "${v.roundToInt()}"

    private fun tone(name: String, get: (Adjustments) -> Float, set: (Adjustments, Float) -> Unit) =
        Spec(name, -100f, 100f, { get(it) * 100f }, { a, v -> set(a, v / 100f) }, { signed(it) })

    private fun amount(name: String, get: (Adjustments) -> Float, set: (Adjustments, Float) -> Unit) =
        Spec(name, 0f, 100f, { get(it) * 100f }, { a, v -> set(a, v / 100f) }, { "${it.roundToInt()}" })

    private val SPECS = listOf(
        Spec("Fotos en ráfaga", 1f, 8f, { it.burstFrames.toFloat() }, { a, v -> a.burstFrames = v.roundToInt() },
            { if (it.roundToInt() == 1) "1 (sin fusión)" else "${it.roundToInt()}" }),
        Spec("Exposición al disparar", -20f, 20f, { it.exposureEv * 10f }, { a, v -> a.exposureEv = v / 10f },
            { String.format(Locale.getDefault(), "%+.1f EV", it / 10f) }),
        tone("Brillo", { it.brightness }, { a, v -> a.brightness = v }),
        tone("Contraste", { it.contrast }, { a, v -> a.contrast = v }),
        tone("Altas luces", { it.highlights }, { a, v -> a.highlights = v }),
        tone("Sombras", { it.shadows }, { a, v -> a.shadows = v }),
        tone("Saturación", { it.saturation }, { a, v -> a.saturation = v }),
        tone("Calidez", { it.warmth }, { a, v -> a.warmth = v }),
        amount("Nitidez", { it.sharpness }, { a, v -> a.sharpness = v }),
        amount("Viñeta", { it.vignette }, { a, v -> a.vignette = v }),
    )

    fun show(
        context: Context,
        preset: Preset,
        initial: Adjustments,
        mirrorSelfies: Boolean,
        onChange: (Adjustments) -> Unit,
        onReset: () -> Adjustments,
        onMirrorChange: (Boolean) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val current = initial.copy()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(28))
        }
        column.addView(TextView(context).apply {
            text = "Modo ${preset.label}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(context).apply {
            text = preset.description
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(4))
        })
        column.addView(TextView(context).apply {
            text = "El visor muestra una vista aproximada; la foto final lleva el ajuste completo."
            alpha = 0.5f
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })

        val refreshers = mutableListOf<() -> Unit>()
        for (spec in SPECS) {
            val label = TextView(context).apply { setPadding(0, dp(10), 0, 0) }
            val slider = Slider(context).apply {
                valueFrom = spec.from
                valueTo = spec.to
                stepSize = 1f
                setLabelFormatter { spec.format(it) }
            }
            val refresh = {
                val v = spec.get(current).roundToInt().toFloat().coerceIn(spec.from, spec.to)
                slider.value = v
                label.text = "${spec.name}: ${spec.format(v)}"
            }
            refresh()
            refreshers += refresh
            slider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                spec.set(current, value)
                label.text = "${spec.name}: ${spec.format(value)}"
                onChange(current.copy())
            }
            column.addView(label)
            column.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        column.addView(MaterialSwitch(context).apply {
            text = context.getString(R.string.mirror_selfies)
            isChecked = mirrorSelfies
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, checked -> onMirrorChange(checked) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.reset_mode)
            setOnClickListener {
                val fresh = onReset()
                copyInto(fresh, current)
                refreshers.forEach { it() }
                onChange(current.copy())
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        dialog.setContentView(NestedScrollView(context).apply { addView(column) })
        dialog.show()
    }

    private fun copyInto(src: Adjustments, dst: Adjustments) {
        dst.burstFrames = src.burstFrames
        dst.exposureEv = src.exposureEv
        dst.brightness = src.brightness
        dst.contrast = src.contrast
        dst.highlights = src.highlights
        dst.shadows = src.shadows
        dst.saturation = src.saturation
        dst.warmth = src.warmth
        dst.sharpness = src.sharpness
        dst.vignette = src.vignette
    }
}
