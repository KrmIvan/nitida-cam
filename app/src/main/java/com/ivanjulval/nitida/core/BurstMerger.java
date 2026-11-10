package com.ivanjulval.nitida.core;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Fusiona una ráfaga de fotos para reducir el ruido (idea parecida al HDR+ de Google, en simple).
 *
 * Cómo funciona:
 *  1. Alineado: pirámide de luminancia (1/16, 1/4 y resolución completa) para encontrar el
 *     desplazamiento global; luego se afina por teselas de 256 px, así se corrigen también
 *     pequeños giros de la mano. Entre teselas el desplazamiento se interpola.
 *  2. Fusión robusta: por cada bloque de 4x4 se compara la foto alineada con la de referencia.
 *     Si se parecen (solo cambia el ruido) se promedia con peso completo; si algo se ha movido
 *     (una persona, una mancuerna...) el bloque pierde peso y se queda la referencia, así no salen
 *     "fantasmas".
 *
 * Uso: new BurstMerger(ref, w, h) → add(frame) para cada foto → result().
 * Los píxeles son ARGB (como Bitmap.getPixels). Java puro, sin Android, para poder probarlo en la JVM.
 */
public final class BurstMerger {

    /** Máximo de fotos fusionadas (incluida la referencia). Con 16, las sumas caben en un short. */
    public static final int MAX_FRAMES = 16;

    static final int Q = 4;           // tamaño de bloque / factor de reducción
    static final int TILE = 256;      // tesela para el alineado local (px reales)
    static final int FULL_WEIGHT = 8; // peso de un bloque que encaja bien

    private final int width, height;
    private final int qw, qh, q2w, q2h;
    private final int[] reference;
    private final byte[] refLuma;
    private final int[] refQ, refQ2;
    private final float[] refTexture;
    private final float textureFloor;
    private final double refMean;

    private final short[] sumR, sumG, sumB;
    private final short[] weightSum;

    private final int tilesX, tilesY;
    private final int[] xi0, xi1;
    private final float[] xw;

    private int merged = 1;
    private int rejected = 0;
    private boolean finished = false;

    private int lastDx, lastDy;
    private float lastAcceptedFraction = 1f;

    public BurstMerger(int[] reference, int width, int height) {
        if (width < 64 || height < 64) throw new IllegalArgumentException("Imagen demasiado pequeña");
        if (reference.length < width * height) throw new IllegalArgumentException("Faltan píxeles");
        this.width = width;
        this.height = height;
        this.reference = reference;
        this.qw = width / Q;
        this.qh = height / Q;
        this.q2w = Math.max(1, qw / Q);
        this.q2h = Math.max(1, qh / Q);

        refLuma = luma(reference, width, height);
        refQ = blockSum(refLuma, width, qw, qh);
        refQ2 = downsample(refQ, qw, qh, q2w, q2h);
        refMean = mean(refQ) / (Q * Q);
        refTexture = blockTexture(reference, width, height, qw, qh);
        textureFloor = Math.max(1f, percentile(refTexture, 0.25f));

        int n = width * height;
        sumR = new short[n];
        sumG = new short[n];
        sumB = new short[n];
        for (int i = 0; i < n; i++) {
            int c = reference[i];
            sumR[i] = (short) (((c >> 16) & 0xFF) * FULL_WEIGHT);
            sumG[i] = (short) (((c >> 8) & 0xFF) * FULL_WEIGHT);
            sumB[i] = (short) ((c & 0xFF) * FULL_WEIGHT);
        }
        weightSum = new short[qw * qh];
        Arrays.fill(weightSum, (short) FULL_WEIGHT);

        tilesX = (width + TILE - 1) / TILE;
        tilesY = (height + TILE - 1) / TILE;
        xi0 = new int[width];
        xi1 = new int[width];
        xw = new float[width];
        for (int x = 0; x < width; x++) {
            float fx = clampF((x - TILE / 2f) / TILE, 0f, tilesX - 1);
            xi0[x] = (int) fx;
            xi1[x] = Math.min(xi0[x] + 1, tilesX - 1);
            xw[x] = fx - xi0[x];
        }
    }

    /**
     * Alinea y añade una foto. Devuelve false si se descarta (demasiado distinta o ya hay MAX_FRAMES).
     * La foto debe tener el mismo tamaño que la referencia.
     */
    public boolean add(int[] frame) {
        if (finished) throw new IllegalStateException("result() ya se ha llamado");
        if (frame.length < width * height) throw new IllegalArgumentException("Tamaño distinto");
        if (merged >= MAX_FRAMES) return false;

        // Luminancia y compensación de brillo (por si la exposición varía un poco entre fotos)
        byte[] lum = luma(frame, width, height);
        int[] fq = blockSum(lum, width, qw, qh);
        double fMean = mean(fq) / (Q * Q);
        int gain256 = 256;
        if (fMean > 1) {
            double g = clampD(refMean / fMean, 0.5, 2.0);
            if (Math.abs(g - 1) > 0.02) gain256 = (int) Math.round(g * 256);
        }
        if (gain256 != 256) {
            for (int i = 0; i < lum.length; i++) {
                int v = ((lum[i] & 0xFF) * gain256) >> 8;
                lum[i] = (byte) Math.min(255, v);
            }
            fq = blockSum(lum, width, qw, qh);
        }
        int[] fq2 = downsample(fq, qw, qh, q2w, q2h);

        // 1) Desplazamiento global, de grueso a fino
        int[] g2 = searchInt(refQ2, fq2, q2w, q2h, 0, 0, q2w, q2h, 0, 0, 8, 1);
        int[] g1 = searchInt(refQ, fq, qw, qh, 0, 0, qw, qh, g2[0] * Q, g2[1] * Q, 2, 2);
        int[] g0 = searchByte(refLuma, lum, width, height, 0, 0, width, height, g1[0] * Q, g1[1] * Q, 2, 4);
        lastDx = g0[0];
        lastDy = g0[1];

        // 2) Ajuste fino por teselas (corrige giros pequeños)
        final int[] tdx = new int[tilesX * tilesY];
        final int[] tdy = new int[tilesX * tilesY];
        final int gqx = Math.round(g0[0] / (float) Q), gqy = Math.round(g0[1] / (float) Q);
        final byte[] flum = lum;
        final int[] ffq = fq;
        IntStream.range(0, tilesX * tilesY).parallel().forEach(t -> {
            int tx = t % tilesX, ty = t / tilesX;
            int x0 = tx * TILE, y0 = ty * TILE;
            int x1 = Math.min(x0 + TILE, width), y1 = Math.min(y0 + TILE, height);
            int qx0 = x0 / Q, qy0 = y0 / Q;
            int qx1 = Math.min(qw, (x1 + Q - 1) / Q), qy1 = Math.min(qh, (y1 + Q - 1) / Q);
            int[] t1 = searchInt(refQ, ffq, qw, qh, qx0, qy0, qx1, qy1, gqx, gqy, 3, 1);
            int[] t0 = searchByte(refLuma, flum, width, height, x0, y0, x1, y1, t1[0] * Q, t1[1] * Q, 2, 3);
            // En zonas lisas (pared, cielo) el alineado local no es fiable: se usa el global
            double sBest = sadByte(refLuma, flum, width, height, x0, y0, x1, y1, t0[0], t0[1], 3);
            double sGlob = sadByte(refLuma, flum, width, height, x0, y0, x1, y1, g0[0], g0[1], 3);
            if (!(sBest < 0.98 * sGlob)) t0 = g0;
            tdx[t] = t0[0];
            tdy[t] = t0[1];
        });

        final int bands = Math.min(qh, Runtime.getRuntime().availableProcessors() * 4);

        // 3) Diferencia media por bloque entre referencia y foto alineada (en paralelo por franjas)
        final int[] blockDiff = new int[qw * qh];
        final short[] blockValid = new short[qw * qh];
        final int g = gain256;
        IntStream.range(0, bands).parallel().forEach(band -> {
            int[] rowDx = new int[width], rowDy = new int[width];
            float[] colDx = new float[tilesX], colDy = new float[tilesX];
            int qyStart = band * qh / bands, qyEnd = (band + 1) * qh / bands;
            for (int y = qyStart * Q; y < qyEnd * Q; y++) {
                fillRowOffsets(y, tdx, tdy, colDx, colDy, rowDx, rowDy);
                int rowBase = (y / Q) * qw;
                int ri = y * width;
                for (int x = 0; x < qw * Q; x++) {
                    int sx = x + rowDx[x], sy = y + rowDy[x];
                    if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
                    // Se compara el color (no solo el brillo): un objeto morado sobre un fondo
                    // igual de claro también cuenta como movimiento
                    int b = rowBase + x / Q;
                    blockDiff[b] += colorDiff(reference[ri + x], frame[sy * width + sx], g);
                    blockValid[b]++;
                }
            }
        });
        // Media por bloque; los bloques que caen fuera de la foto alineada no cuentan
        final int minValid = Q * Q / 2;
        float[] diffMean = new float[blockDiff.length];
        int validBlocks = 0;
        for (int i = 0; i < blockDiff.length; i++) {
            if (blockValid[i] >= minValid) {
                diffMean[i] = blockDiff[i] / (float) blockValid[i];
                validBlocks++;
            } else {
                diffMean[i] = -1f;
            }
        }
        if (validBlocks < blockDiff.length / 2) { // apenas se solapan: foto inservible
            lastAcceptedFraction = 0f;
            rejected++;
            return false;
        }
        float[] validDiffs = new float[validBlocks];
        for (int i = 0, k = 0; i < diffMean.length; i++) if (diffMean[i] >= 0f) validDiffs[k++] = diffMean[i];
        // Nivel de ruido entre fotos: percentil bajo (zonas quietas y lisas), así aunque gran parte
        // de la escena se mueva (alguien entrenando) no se infla. Limitado por la textura de la referencia.
        float noiseFloor = Math.max(3f, Math.min(1.25f * percentile(validDiffs, 0.2f), 2.5f * textureFloor));

        byte[] w = new byte[qw * qh];
        int zero = 0;
        for (int i = 0; i < w.length; i++) {
            if (diffMean[i] < 0f) { w[i] = 0; zero++; continue; }
            float t = Math.max(Math.max(refTexture[i], textureFloor), noiseFloor);
            float r = diffMean[i] / t;
            int wi;
            if (r <= 1.5f) wi = FULL_WEIGHT;
            else if (r >= 3.5f) wi = 0;
            else wi = Math.round(FULL_WEIGHT * (3.5f - r) / 2f);
            w[i] = (byte) wi;
            if (wi == 0) zero++;
        }
        lastAcceptedFraction = 1f - zero / (float) w.length;
        if (lastAcceptedFraction < 0.4f) { // casi todo se ha movido: mejor no usar esta foto
            rejected++;
            return false;
        }

        // 4) Acumular (en paralelo por franjas; cada píxel lo escribe un solo hilo)
        final byte[] fw = w;
        final int rowsPerBand = (height + bands - 1) / bands;
        IntStream.range(0, bands).parallel().forEach(band -> {
            int[] rowDx = new int[width], rowDy = new int[width];
            float[] colDx = new float[tilesX], colDy = new float[tilesX];
            int yEnd = Math.min(height, (band + 1) * rowsPerBand);
            for (int y = band * rowsPerBand; y < yEnd; y++) {
                fillRowOffsets(y, tdx, tdy, colDx, colDy, rowDx, rowDy);
                int by = Math.min(y / Q, qh - 1) * qw;
                int ri = y * width;
                for (int x = 0; x < width; x++) {
                    int wi = fw[by + Math.min(x / Q, qw - 1)];
                    if (wi == 0) continue;
                    int sx = x + rowDx[x], sy = y + rowDy[x];
                    int c = (sx < 0 || sy < 0 || sx >= width || sy >= height) ? reference[ri + x] : frame[sy * width + sx];
                    int r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, b = c & 0xFF;
                    if (g != 256) {
                        r = Math.min(255, (r * g) >> 8);
                        gg = Math.min(255, (gg * g) >> 8);
                        b = Math.min(255, (b * g) >> 8);
                    }
                    int i = ri + x;
                    sumR[i] = (short) (sumR[i] + wi * r);
                    sumG[i] = (short) (sumG[i] + wi * gg);
                    sumB[i] = (short) (sumB[i] + wi * b);
                }
            }
        });
        for (int i = 0; i < weightSum.length; i++) weightSum[i] = (short) (weightSum[i] + w[i]);
        merged++;
        return true;
    }

    /**
     * Devuelve la imagen fusionada. Para ahorrar memoria se escribe encima del array de referencia
     * (el mismo que se pasó al constructor). Después no se pueden añadir más fotos.
     */
    public int[] result() {
        finished = true;
        for (int y = 0; y < height; y++) {
            int by = Math.min(y / Q, qh - 1) * qw;
            int ri = y * width;
            for (int x = 0; x < width; x++) {
                int ws = weightSum[by + Math.min(x / Q, qw - 1)];
                int i = ri + x;
                int half = ws >> 1;
                int r = (sumR[i] + half) / ws;
                int g = (sumG[i] + half) / ws;
                int b = (sumB[i] + half) / ws;
                reference[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return reference;
    }

    public int getMergedCount() { return merged; }
    public int getRejectedCount() { return rejected; }
    public int getLastDx() { return lastDx; }
    public int getLastDy() { return lastDy; }
    public float getLastAcceptedFraction() { return lastAcceptedFraction; }

    // ---------------------------------------------------------------- utilidades

    private void fillRowOffsets(int y, int[] tdx, int[] tdy, float[] colDx, float[] colDy, int[] rowDx, int[] rowDy) {
        float fy = clampF((y - TILE / 2f) / TILE, 0f, tilesY - 1);
        int j0 = (int) fy, j1 = Math.min(j0 + 1, tilesY - 1);
        float wy = fy - j0;
        for (int i = 0; i < tilesX; i++) {
            colDx[i] = tdx[j0 * tilesX + i] * (1 - wy) + tdx[j1 * tilesX + i] * wy;
            colDy[i] = tdy[j0 * tilesX + i] * (1 - wy) + tdy[j1 * tilesX + i] * wy;
        }
        for (int x = 0; x < width; x++) {
            float a = xw[x];
            rowDx[x] = Math.round(colDx[xi0[x]] * (1 - a) + colDx[xi1[x]] * a);
            rowDy[x] = Math.round(colDy[xi0[x]] * (1 - a) + colDy[xi1[x]] * a);
        }
    }

    /** Luminancia 0..255 (aprox. Rec.601). */
    static byte[] luma(int[] px, int w, int h) {
        int n = w * h;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int v = (77 * ((c >> 16) & 0xFF) + 150 * ((c >> 8) & 0xFF) + 29 * (c & 0xFF)) >> 8;
            out[i] = (byte) v;
        }
        return out;
    }

    /** Suma de cada bloque QxQ (0..4080). */
    static int[] blockSum(byte[] lum, int w, int qw, int qh) {
        int[] out = new int[qw * qh];
        for (int y = 0; y < qh * Q; y++) {
            int row = (y / Q) * qw;
            int base = y * w;
            for (int x = 0; x < qw * Q; x++) out[row + x / Q] += lum[base + x] & 0xFF;
        }
        return out;
    }

    /** Reduce otra vez por Q manteniendo la escala (media de las sumas). */
    static int[] downsample(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int s = 0, c = 0;
                for (int yy = y * Q; yy < Math.min(sh, y * Q + Q); yy++)
                    for (int xx = x * Q; xx < Math.min(sw, x * Q + Q); xx++) { s += src[yy * sw + xx]; c++; }
                out[y * dw + x] = c == 0 ? 0 : s / c;
            }
        }
        return out;
    }

    /** Diferencia de color entre dos píxeles (media ponderada de |dR|, |dG|, |dB|), aplicando ganancia al segundo. */
    static int colorDiff(int ref, int c, int gain256) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (gain256 != 256) {
            r = Math.min(255, (r * gain256) >> 8);
            g = Math.min(255, (g * gain256) >> 8);
            b = Math.min(255, (b * gain256) >> 8);
        }
        int dr = Math.abs(r - ((ref >> 16) & 0xFF));
        int dg = Math.abs(g - ((ref >> 8) & 0xFF));
        int db = Math.abs(b - (ref & 0xFF));
        return (dr + 2 * dg + db) >> 2;
    }

    /** Textura local de cada bloque: diferencia de color media con el vecino derecho y el de abajo. */
    static float[] blockTexture(int[] px, int w, int h, int qw, int qh) {
        int[] acc = new int[qw * qh];
        for (int y = 0; y < qh * Q; y++) {
            int row = (y / Q) * qw;
            int base = y * w;
            boolean hasDown = y + 1 < h;
            for (int x = 0; x < qw * Q; x++) {
                int c = px[base + x];
                int dh = x + 1 < w ? colorDiff(c, px[base + x + 1], 256) : 0;
                int dv = hasDown ? colorDiff(c, px[base + w + x], 256) : 0;
                acc[row + x / Q] += dh + dv;
            }
        }
        float[] out = new float[acc.length];
        for (int i = 0; i < acc.length; i++) out[i] = acc[i] / (2f * Q * Q);
        return out;
    }

    /** Busca (dx,dy) en [c-r, c+r] que minimiza la diferencia media en la región [x0,x1)x[y0,y1). */
    static int[] searchInt(int[] ref, int[] img, int w, int h, int x0, int y0, int x1, int y1,
                           int cx, int cy, int radius, int step) {
        int bx = cx, by = cy;
        double best = sadInt(ref, img, w, h, x0, y0, x1, y1, cx, cy, step);
        for (int dy = cy - radius; dy <= cy + radius; dy++) {
            for (int dx = cx - radius; dx <= cx + radius; dx++) {
                if (dx == cx && dy == cy) continue;
                double s = sadInt(ref, img, w, h, x0, y0, x1, y1, dx, dy, step);
                if (s < best - 1e-9) { best = s; bx = dx; by = dy; }
            }
        }
        return new int[]{bx, by};
    }

    static int[] searchByte(byte[] ref, byte[] img, int w, int h, int x0, int y0, int x1, int y1,
                            int cx, int cy, int radius, int step) {
        int bx = cx, by = cy;
        double best = sadByte(ref, img, w, h, x0, y0, x1, y1, cx, cy, step);
        for (int dy = cy - radius; dy <= cy + radius; dy++) {
            for (int dx = cx - radius; dx <= cx + radius; dx++) {
                if (dx == cx && dy == cy) continue;
                double s = sadByte(ref, img, w, h, x0, y0, x1, y1, dx, dy, step);
                if (s < best - 1e-9) { best = s; bx = dx; by = dy; }
            }
        }
        return new int[]{bx, by};
    }

    static double sadInt(int[] ref, int[] img, int w, int h, int x0, int y0, int x1, int y1, int dx, int dy, int step) {
        int xs = Math.max(x0, -dx), xe = Math.min(x1, w - dx);
        int ys = Math.max(y0, -dy), ye = Math.min(y1, h - dy);
        if (xe <= xs || ye <= ys) return Double.MAX_VALUE;
        long sum = 0, count = 0;
        for (int y = ys; y < ye; y += step) {
            int rb = y * w, ib = (y + dy) * w + dx;
            for (int x = xs; x < xe; x += step) {
                int d = ref[rb + x] - img[ib + x];
                sum += d < 0 ? -d : d;
                count++;
            }
        }
        long full = (long) ((x1 - x0 + step - 1) / step) * ((y1 - y0 + step - 1) / step);
        if (count * 4 < full) return Double.MAX_VALUE; // muy poco solape
        return sum / (double) count;
    }

    static double sadByte(byte[] ref, byte[] img, int w, int h, int x0, int y0, int x1, int y1, int dx, int dy, int step) {
        int xs = Math.max(x0, -dx), xe = Math.min(x1, w - dx);
        int ys = Math.max(y0, -dy), ye = Math.min(y1, h - dy);
        if (xe <= xs || ye <= ys) return Double.MAX_VALUE;
        long sum = 0, count = 0;
        for (int y = ys; y < ye; y += step) {
            int rb = y * w, ib = (y + dy) * w + dx;
            for (int x = xs; x < xe; x += step) {
                int d = (ref[rb + x] & 0xFF) - (img[ib + x] & 0xFF);
                sum += d < 0 ? -d : d;
                count++;
            }
        }
        long full = (long) ((x1 - x0 + step - 1) / step) * ((y1 - y0 + step - 1) / step);
        if (count * 4 < full) return Double.MAX_VALUE;
        return sum / (double) count;
    }

    static double mean(int[] a) {
        long s = 0;
        for (int v : a) s += v;
        return a.length == 0 ? 0 : s / (double) a.length;
    }

    static float percentile(float[] a, float p) {
        if (a.length == 0) return 0f;
        float[] c = a.clone();
        Arrays.sort(c);
        int i = Math.min(c.length - 1, Math.max(0, Math.round(p * (c.length - 1))));
        return c[i];
    }

    private static float clampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double clampD(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
