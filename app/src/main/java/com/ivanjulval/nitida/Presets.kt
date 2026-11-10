package com.ivanjulval.nitida

import android.content.Context
import androidx.core.content.edit
import com.ivanjulval.nitida.core.Adjustments

/** Modos de la cámara con sus valores de fábrica. Cada modo se puede retocar y se guarda. */
enum class Preset(val label: String, val description: String) {
    NATURAL("Natural", "Ráfaga para quitar ruido, colores sin tocar"),
    GYM("Gym", "Menos exposición, más contraste y definición: adiós a los brillos de los focos"),
    NOCHE("Noche", "Más fotos en la ráfaga y sombras levantadas para poca luz"),
    SELFIE("Selfie", "Tono algo más cálido y luz suave"),
    EXTERIOR("Exterior", "Recupera el cielo y da más vida a los colores");

    fun defaults(): Adjustments = Adjustments().apply {
        when (this@Preset) {
            NATURAL -> {
                burstFrames = 4
                sharpness = 0.2f
            }
            GYM -> {
                burstFrames = 4
                exposureEv = -0.7f
                contrast = 0.25f
                highlights = -0.4f
                shadows = 0.1f
                saturation = -0.1f
                warmth = 0.05f
                sharpness = 0.4f
                vignette = 0.25f
            }
            NOCHE -> {
                burstFrames = 8
                shadows = 0.3f
                highlights = -0.3f
                contrast = 0.05f
                sharpness = 0.2f
            }
            SELFIE -> {
                burstFrames = 4
                warmth = 0.1f
                contrast = -0.05f
                shadows = 0.1f
                highlights = -0.2f
                sharpness = 0.15f
            }
            EXTERIOR -> {
                burstFrames = 3
                exposureEv = -0.3f
                highlights = -0.5f
                shadows = 0.2f
                saturation = 0.15f
                contrast = 0.1f
                sharpness = 0.3f
            }
        }
    }
}

/** Guarda el modo elegido y los retoques de cada modo en SharedPreferences. */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("nitida_presets", Context.MODE_PRIVATE)

    var current: Preset
        get() = Preset.entries.firstOrNull { it.name == prefs.getString(KEY_CURRENT, null) } ?: Preset.NATURAL
        set(value) = prefs.edit { putString(KEY_CURRENT, value.name) }

    var mirrorSelfies: Boolean
        get() = prefs.getBoolean(KEY_MIRROR, true)
        set(value) = prefs.edit { putBoolean(KEY_MIRROR, value) }

    var format: PhotoFormat
        get() = PhotoFormat.entries.firstOrNull { it.name == prefs.getString(KEY_FORMAT, null) } ?: PhotoFormat.FULL
        set(value) = prefs.edit { putString(KEY_FORMAT, value.name) }

    /** Guardar al tamaño exacto recomendado (p. ej. 1080 × 1920) en vez de a máxima resolución. */
    var exactSize: Boolean
        get() = prefs.getBoolean(KEY_EXACT, false)
        set(value) = prefs.edit { putBoolean(KEY_EXACT, value) }

    fun load(preset: Preset): Adjustments {
        val d = preset.defaults()
        val k = preset.name
        if (!prefs.getBoolean("$k.saved", false)) return d
        return Adjustments().apply {
            burstFrames = prefs.getInt("$k.burstFrames", d.burstFrames)
            exposureEv = prefs.getFloat("$k.exposureEv", d.exposureEv)
            brightness = prefs.getFloat("$k.brightness", d.brightness)
            contrast = prefs.getFloat("$k.contrast", d.contrast)
            highlights = prefs.getFloat("$k.highlights", d.highlights)
            shadows = prefs.getFloat("$k.shadows", d.shadows)
            saturation = prefs.getFloat("$k.saturation", d.saturation)
            warmth = prefs.getFloat("$k.warmth", d.warmth)
            sharpness = prefs.getFloat("$k.sharpness", d.sharpness)
            vignette = prefs.getFloat("$k.vignette", d.vignette)
        }.clamp()
    }

    fun save(preset: Preset, a: Adjustments) {
        val k = preset.name
        prefs.edit {
            putBoolean("$k.saved", true)
            putInt("$k.burstFrames", a.burstFrames)
            putFloat("$k.exposureEv", a.exposureEv)
            putFloat("$k.brightness", a.brightness)
            putFloat("$k.contrast", a.contrast)
            putFloat("$k.highlights", a.highlights)
            putFloat("$k.shadows", a.shadows)
            putFloat("$k.saturation", a.saturation)
            putFloat("$k.warmth", a.warmth)
            putFloat("$k.sharpness", a.sharpness)
            putFloat("$k.vignette", a.vignette)
        }
    }

    /** Vuelve a los valores de fábrica del modo. */
    fun reset(preset: Preset) {
        val prefix = "${preset.name}."
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        prefs.edit { keys.forEach { remove(it) } }
    }

    private companion object {
        const val KEY_CURRENT = "current"
        const val KEY_MIRROR = "mirror_selfies"
        const val KEY_FORMAT = "format"
        const val KEY_EXACT = "exact_size"
    }
}
