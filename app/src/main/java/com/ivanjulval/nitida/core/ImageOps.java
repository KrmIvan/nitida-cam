package com.ivanjulval.nitida.core;

/** Operaciones sencillas sobre píxeles ARGB. */
public final class ImageOps {

    private ImageOps() {}

    /**
     * Gira la imagen 0, 90, 180 o 270 grados en sentido horario. Devuelve un array nuevo
     * (o el mismo si son 0 grados). Con 90/270 el ancho y el alto se intercambian.
     */
    public static int[] rotate(int[] px, int w, int h, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) return px;
        int[] out = new int[w * h];
        switch (d) {
            case 90: // nuevo ancho = h
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) out[x * h + (h - 1 - y)] = px[y * w + x];
                break;
            case 180:
                for (int i = 0, n = w * h; i < n; i++) out[n - 1 - i] = px[i];
                break;
            case 270:
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) out[(w - 1 - x) * h + y] = px[y * w + x];
                break;
            default:
                throw new IllegalArgumentException("Solo 0, 90, 180 o 270 grados");
        }
        return out;
    }

    /** Espejo horizontal en el sitio (para selfies "como en el espejo"). */
    public static void mirror(int[] px, int w, int h) {
        for (int y = 0; y < h; y++) {
            int a = y * w, b = a + w - 1;
            while (a < b) {
                int t = px[a];
                px[a++] = px[b];
                px[b--] = t;
            }
        }
    }

    /**
     * Puntuación de nitidez (energía del gradiente). Sirve para elegir la foto menos movida
     * de la ráfaga como referencia. Pensado para una versión reducida de la foto.
     */
    public static double sharpnessScore(int[] px, int w, int h) {
        if (w < 3 || h < 3) return 0;
        long sum = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int c = lum(px[i]);
                int gx = lum(px[i + 1]) - lum(px[i - 1]);
                int gy = lum(px[i + w]) - lum(px[i - w]);
                int lap = 4 * c - lum(px[i + 1]) - lum(px[i - 1]) - lum(px[i + w]) - lum(px[i - w]);
                sum += (long) gx * gx + (long) gy * gy + (long) lap * lap;
            }
        }
        return sum / (double) ((w - 2) * (h - 2));
    }

    private static int lum(int c) {
        return (77 * ((c >> 16) & 0xFF) + 150 * ((c >> 8) & 0xFF) + 29 * (c & 0xFF)) >> 8;
    }
}
