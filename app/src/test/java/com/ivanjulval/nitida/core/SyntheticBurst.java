package com.ivanjulval.nitida.core;

import java.util.Random;

/** Genera ráfagas sintéticas (escena + temblor de mano + ruido) para probar la fusión. */
final class SyntheticBurst {

    final int baseW, baseH;
    final float[] baseR, baseG, baseB;

    SyntheticBurst(int w, int h, long seed) {
        baseW = w;
        baseH = h;
        baseR = new float[w * h];
        baseG = new float[w * h];
        baseB = new float[w * h];
        Random rnd = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x / (float) w, gy = y / (float) h;
                float tex = (float) (20 * Math.sin(x * 0.11) * Math.sin(y * 0.07) + 12 * Math.sin((x + 2 * y) * 0.31));
                baseR[i] = 70 + 110 * gx + tex;
                baseG[i] = 60 + 90 * gy + tex;
                baseB[i] = 80 + 60 * (1 - gx) + tex * 0.5f;
            }
        }
        // Rectángulos de colores (bordes nítidos) y "letras" pequeñas
        for (int k = 0; k < 60; k++) {
            int rw = 8 + rnd.nextInt(90), rh = 8 + rnd.nextInt(90);
            int rx = rnd.nextInt(Math.max(1, w - rw)), ry = rnd.nextInt(Math.max(1, h - rh));
            float cr = rnd.nextInt(256), cg = rnd.nextInt(256), cb = rnd.nextInt(256);
            fillRect(rx, ry, rw, rh, cr, cg, cb);
        }
        for (int k = 0; k < 400; k++) {
            int rx = rnd.nextInt(w - 6), ry = rnd.nextInt(h - 6);
            float v = rnd.nextBoolean() ? 20 : 235;
            fillRect(rx, ry, 2 + rnd.nextInt(4), 2 + rnd.nextInt(4), v, v, v);
        }
    }

    void fillRect(int rx, int ry, int rw, int rh, float r, float g, float b) {
        for (int y = ry; y < Math.min(baseH, ry + rh); y++)
            for (int x = rx; x < Math.min(baseW, rx + rw); x++) {
                int i = y * baseW + x;
                baseR[i] = r;
                baseG[i] = g;
                baseB[i] = b;
            }
    }

    /**
     * Saca una foto de w x h: centro de la escena desplazado (dx,dy), girado 'deg' grados,
     * con un cuadrado "en movimiento" opcional (objX < 0 = sin objeto) y ruido de desviación sigma.
     */
    int[] frame(int w, int h, double dx, double dy, double deg, double gain,
                int objX, int objY, int objSize, double sigma, long seed) {
        int[] out = new int[w * h];
        double cos = Math.cos(Math.toRadians(deg)), sin = Math.sin(Math.toRadians(deg));
        double cx = w / 2.0, cy = h / 2.0;
        double bcx = baseW / 2.0 + dx, bcy = baseH / 2.0 + dy;
        Random rnd = new Random(seed);
        // ruido algo correlado espacialmente, como el de un JPEG de móvil
        float[] noise = new float[(w + 1) * (h + 1)];
        for (int i = 0; i < noise.length; i++) noise[i] = (float) rnd.nextGaussian();
        double ns = sigma * 2.0; // la media de 4 muestras tiene sigma/2: se compensa
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double px = x - cx, py = y - cy;
                double sx = bcx + px * cos - py * sin;
                double sy = bcy + px * sin + py * cos;
                float[] c = sample(sx, sy);
                double r = c[0], g = c[1], b = c[2];
                if (objX >= 0 && x >= objX && x < objX + objSize && y >= objY && y < objY + objSize) {
                    r = 230; g = 40; b = 200;
                }
                double n = (noise[y * (w + 1) + x] + noise[y * (w + 1) + x + 1]
                        + noise[(y + 1) * (w + 1) + x] + noise[(y + 1) * (w + 1) + x + 1]) / 4.0 * ns;
                r = r * gain + n; g = g * gain + n * 0.9; b = b * gain + n * 1.1;
                out[y * w + x] = 0xFF000000 | (c255(r) << 16) | (c255(g) << 8) | c255(b);
            }
        }
        return out;
    }

    private float[] sample(double x, double y) {
        x = Math.max(0, Math.min(baseW - 1.001, x));
        y = Math.max(0, Math.min(baseH - 1.001, y));
        int x0 = (int) x, y0 = (int) y;
        float fx = (float) (x - x0), fy = (float) (y - y0);
        int i = y0 * baseW + x0;
        return new float[]{
                bil(baseR, i, fx, fy), bil(baseG, i, fx, fy), bil(baseB, i, fx, fy)};
    }

    private float bil(float[] a, int i, float fx, float fy) {
        float top = a[i] * (1 - fx) + a[i + 1] * fx;
        float bot = a[i + baseW] * (1 - fx) + a[i + baseW + 1] * fx;
        return top * (1 - fy) + bot * fy;
    }

    static int c255(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** PSNR en una región (márgenes excluidos). */
    static double psnr(int[] a, int[] b, int w, int h, int margin) {
        double se = 0;
        long n = 0;
        for (int y = margin; y < h - margin; y++)
            for (int x = margin; x < w - margin; x++) {
                int ca = a[y * w + x], cb = b[y * w + x];
                for (int s = 0; s <= 16; s += 8) {
                    int d = ((ca >> s) & 0xFF) - ((cb >> s) & 0xFF);
                    se += d * d;
                    n++;
                }
            }
        double mse = se / n;
        return 10 * Math.log10(255.0 * 255.0 / mse);
    }

    /** Diferencia media absoluta en un rectángulo. */
    static double mae(int[] a, int[] b, int w, int x0, int y0, int x1, int y1) {
        double s = 0;
        long n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int ca = a[y * w + x], cb = b[y * w + x];
                for (int k = 0; k <= 16; k += 8) {
                    s += Math.abs(((ca >> k) & 0xFF) - ((cb >> k) & 0xFF));
                    n++;
                }
            }
        return s / n;
    }
}
