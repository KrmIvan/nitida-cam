package com.ivanjulval.nitida.core;

/**
 * Ajustes de un modo de cámara.
 *
 * Los valores de tono van de -1 a 1 (0 = sin cambio); nitidez y viñeta de 0 a 1.
 * Es Java puro (sin dependencias de Android) para poder probarlo en la JVM.
 */
public final class Adjustments {

    /** Fotos de la ráfaga que se fusionan (1 = una sola foto). Rango 1..8. */
    public int burstFrames = 4;

    /** Compensación de exposición que aplica la cámara al disparar, en EV. Rango -2..2. */
    public float exposureEv = 0f;

    /** Brillo (curva gamma). -1..1 */
    public float brightness = 0f;
    /** Contraste (curva en S). -1..1 */
    public float contrast = 0f;
    /** Altas luces: negativo recupera zonas quemadas. -1..1 */
    public float highlights = 0f;
    /** Sombras: positivo las levanta. -1..1 */
    public float shadows = 0f;
    /** Saturación. -1..1 */
    public float saturation = 0f;
    /** Calidez: positivo más cálido (amarillo), negativo más frío (azul). -1..1 */
    public float warmth = 0f;

    /** Nitidez (máscara de enfoque). 0..1 */
    public float sharpness = 0f;
    /** Oscurecido de esquinas. 0..1 */
    public float vignette = 0f;

    public Adjustments copy() {
        Adjustments a = new Adjustments();
        a.burstFrames = burstFrames;
        a.exposureEv = exposureEv;
        a.brightness = brightness;
        a.contrast = contrast;
        a.highlights = highlights;
        a.shadows = shadows;
        a.saturation = saturation;
        a.warmth = warmth;
        a.sharpness = sharpness;
        a.vignette = vignette;
        return a;
    }

    /** true si hay algún ajuste de color o tono que aplicar tras la fusión. */
    public boolean hasToneChanges() {
        return brightness != 0f || contrast != 0f || highlights != 0f || shadows != 0f
                || saturation != 0f || warmth != 0f || vignette != 0f;
    }

    /** Recorta todos los valores a su rango válido. */
    public Adjustments clamp() {
        burstFrames = Math.max(1, Math.min(8, burstFrames));
        exposureEv = clamp(exposureEv, -2f, 2f);
        brightness = clamp(brightness, -1f, 1f);
        contrast = clamp(contrast, -1f, 1f);
        highlights = clamp(highlights, -1f, 1f);
        shadows = clamp(shadows, -1f, 1f);
        saturation = clamp(saturation, -1f, 1f);
        warmth = clamp(warmth, -1f, 1f);
        sharpness = clamp(sharpness, 0f, 1f);
        vignette = clamp(vignette, 0f, 1f);
        return this;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
