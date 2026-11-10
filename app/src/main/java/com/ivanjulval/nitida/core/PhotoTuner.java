package com.ivanjulval.nitida.core;

import java.util.stream.IntStream;

/**
 * Ajustes de imagen tras la fusión: nitidez, curva tonal (brillo, contraste, altas luces,
 * sombras), saturación, calidez y viñeta. Trabaja sobre píxeles ARGB en el sitio.
 */
public final class PhotoTuner {

    private static final int LUT_SIZE = 1024;

    private PhotoTuner() {}

    /** Aplica todos los ajustes (primero nitidez, luego tono y color). */
    public static void process(int[] px, int w, int h, Adjustments a) {
        if (a.sharpness > 0f) sharpen(px, w, h, a.sharpness);
        if (a.hasToneChanges()) applyTone(px, w, h, a);
    }

    /**
     * Máscara de enfoque sobre la luminancia (radio 2). Como la ráfaga ya ha quitado ruido,
     * se puede enfocar sin que el grano se dispare. Un pequeño umbral evita realzar el ruido que quede.
     */
    public static void sharpen(int[] px, int w, int h, float amount) {
        if (amount <= 0f) return;
        int n = w * h;
        short[] lum = new short[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            lum[i] = (short) ((77 * ((c >> 16) & 0xFF) + 150 * ((c >> 8) & 0xFF) + 29 * (c & 0xFF)) >> 4); // x16
        }
        short[] blur = boxBlur(lum, w, h, 2);
        int gain = Math.round(amount * 1.2f * 256);
        final int thr = 2 * 16;
        final int maxDelta = 48;
        for (int i = 0; i < n; i++) {
            int d = lum[i] - blur[i];
            if (d > thr) d -= thr;
            else if (d < -thr) d += thr;
            else continue;
            int delta = (d * gain) >> 12; // /16 (escala luma) y /256 (ganancia)
            if (delta > maxDelta) delta = maxDelta;
            else if (delta < -maxDelta) delta = -maxDelta;
            if (delta == 0) continue;
            int c = px[i];
            int r = clamp255(((c >> 16) & 0xFF) + delta);
            int g = clamp255(((c >> 8) & 0xFF) + delta);
            int b = clamp255((c & 0xFF) + delta);
            px[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    /** Desenfoque de caja separable (valores en escala x16). */
    static short[] boxBlur(short[] src, int w, int h, int r) {
        int n = w * h;
        short[] tmp = new short[n];
        short[] out = new short[n];
        int k = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w;
            int sum = 0;
            for (int x = -r; x <= r; x++) sum += src[base + clampI(x, 0, w - 1)];
            for (int x = 0; x < w; x++) {
                tmp[base + x] = (short) (sum / k);
                sum += src[base + clampI(x + r + 1, 0, w - 1)] - src[base + clampI(x - r, 0, w - 1)];
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -r; y <= r; y++) sum += tmp[clampI(y, 0, h - 1) * w + x];
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (short) (sum / k);
                sum += tmp[clampI(y + r + 1, 0, h - 1) * w + x] - tmp[clampI(y - r, 0, h - 1) * w + x];
            }
        }
        return out;
    }

    /** Curva tonal (luminancia 0..1 → 0..1) con brillo, sombras, altas luces y contraste. Siempre creciente. */
    public static float[] buildToneCurve(Adjustments a) {
        float[] lut = new float[LUT_SIZE];
        double gamma = Math.pow(2.0, -a.brightness * 0.8);
        double ks = a.shadows > 0 ? 1.5 * a.shadows : 0.9 * a.shadows;
        double kh = a.highlights > 0 ? 0.9 * a.highlights : 1.5 * a.highlights;
        float prev = 0f;
        for (int i = 0; i < LUT_SIZE; i++) {
            double y = i / (double) (LUT_SIZE - 1);
            y = Math.pow(y, gamma);
            y = y + ks * y * (1 - y) * (1 - y);
            y = y + kh * y * y * (1 - y);
            y = clampD(y, 0, 1);
            if (a.contrast > 0) {
                double s = y * y * (3 - 2 * y);
                y = y + (s - y) * a.contrast;
            } else if (a.contrast < 0) {
                double flat = 0.5 + (y - 0.5) * 0.6;
                y = y + (flat - y) * -a.contrast;
            }
            float v = (float) clampD(y, 0, 1);
            if (v < prev) v = prev; // garantizar que la curva no baja
            lut[i] = v;
            prev = v;
        }
        return lut;
    }

    /** Curva tonal + saturación + calidez + viñeta. */
    public static void applyTone(int[] px, int w, int h, Adjustments a) {
        float[] lut = buildToneCurve(a);
        float warmR = 1f + 0.12f * a.warmth;
        float warmB = 1f - 0.12f * a.warmth;
        float sat = 1f + a.saturation;
        float vig = a.vignette * 0.5f;
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f;

        // Cada fila es independiente: se reparte entre los núcleos
        IntStream.range(0, h).parallel().forEach(y -> {
            float ny = (y - cy) / cy;
            float ny2 = ny * ny;
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int i = base + x;
                int c = px[i];
                float r = ((c >> 16) & 0xFF) / 255f;
                float g = ((c >> 8) & 0xFF) / 255f;
                float b = (c & 0xFF) / 255f;

                r *= warmR;
                b *= warmB;

                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                int li = (int) (clampF(lum, 0f, 1f) * (LUT_SIZE - 1) + 0.5f);
                float target = lut[li];

                // Escalar mantiene el color; si la escala es enorme (negros puros) se suma el resto
                float scale = lum > 1e-4f ? target / lum : 1f;
                float extra = 0f;
                if (scale > 3f) {
                    extra = target - lum * 3f;
                    scale = 3f;
                }
                r = r * scale + extra;
                g = g * scale + extra;
                b = b * scale + extra;

                if (sat != 1f) {
                    float l2 = 0.299f * r + 0.587f * g + 0.114f * b;
                    r = l2 + (r - l2) * sat;
                    g = l2 + (g - l2) * sat;
                    b = l2 + (b - l2) * sat;
                }

                if (vig > 0f) {
                    float nx = (x - cx) / cx;
                    float d2 = (nx * nx + ny2) * 0.5f; // 0 centro, 1 esquina
                    float t = clampF((d2 - 0.2f) / 0.8f, 0f, 1f);
                    float f = 1f - vig * t * t * (3 - 2 * t);
                    r *= f;
                    g *= f;
                    b *= f;
                }

                px[i] = (c & 0xFF000000)
                        | (clamp255(Math.round(r * 255f)) << 16)
                        | (clamp255(Math.round(g * 255f)) << 8)
                        | clamp255(Math.round(b * 255f));
            }
        });
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    private static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float clampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double clampD(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
