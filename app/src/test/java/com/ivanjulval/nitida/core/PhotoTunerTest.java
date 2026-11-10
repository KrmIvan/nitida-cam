package com.ivanjulval.nitida.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhotoTunerTest {

    private static int[] gradient(int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int v = x * 255 / (w - 1);
                int g = y * 255 / (h - 1);
                px[y * w + x] = 0xFF000000 | (v << 16) | (g << 8) | ((v + g) / 2);
            }
        return px;
    }

    @Test
    public void ajustesNeutrosNoTocanLaFoto() {
        int[] px = gradient(64, 48);
        int[] copy = px.clone();
        PhotoTuner.process(px, 64, 48, new Adjustments());
        assertArrayEquals(copy, px);
    }

    @Test
    public void curvaSiempreCrecienteConValoresExtremos() {
        float[] vals = {-1f, -0.5f, 0f, 0.5f, 1f};
        for (float b : vals) for (float c : vals) for (float hl : vals) for (float sh : vals) {
            Adjustments a = new Adjustments();
            a.brightness = b; a.contrast = c; a.highlights = hl; a.shadows = sh;
            float[] lut = PhotoTuner.buildToneCurve(a);
            for (int i = 1; i < lut.length; i++) assertTrue(lut[i] >= lut[i - 1]);
            assertTrue(lut[0] >= 0f && lut[lut.length - 1] <= 1f);
        }
    }

    @Test
    public void curvaNeutraEsIdentidad() {
        float[] lut = PhotoTuner.buildToneCurve(new Adjustments());
        for (int i = 0; i < lut.length; i++) assertEquals(i / 1023f, lut[i], 1e-5f);
    }

    @Test
    public void altasLucesNegativasBajanLasZonasClaras() {
        Adjustments a = new Adjustments();
        a.highlights = -0.6f;
        float[] lut = PhotoTuner.buildToneCurve(a);
        assertTrue(lut[(int) (0.8 * 1023)] < 0.8f - 0.05f);
        assertEquals(0.05f, lut[(int) (0.05 * 1023)], 0.01f); // sombras casi igual
    }

    @Test
    public void saturacionMenosUnoDejaGris() {
        int[] px = {0xFFFF0000, 0xFF00FF00, 0xFF3366CC};
        Adjustments a = new Adjustments();
        a.saturation = -1f;
        PhotoTuner.applyTone(px, 3, 1, a);
        for (int c : px) {
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            assertTrue(Math.abs(r - g) <= 1 && Math.abs(g - b) <= 1);
        }
    }

    @Test
    public void vinetaOscureceEsquinasYNoElCentro() {
        int w = 101, h = 81;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF808080);
        Adjustments a = new Adjustments();
        a.vignette = 1f;
        PhotoTuner.applyTone(px, w, h, a);
        int center = px[40 * w + 50] & 0xFF, corner = px[0] & 0xFF;
        assertEquals(128, center, 1);
        assertTrue(corner < 80);
    }

    @Test
    public void nitidezRealzaBordes() {
        int w = 40, h = 10;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = x < 20 ? 0xFF404040 : 0xFFC0C0C0;
        PhotoTuner.sharpen(px, w, h, 1f);
        int dark = px[5 * w + 19] & 0xFF, light = px[5 * w + 20] & 0xFF;
        assertTrue(dark < 0x40 && light > 0xC0);
        assertEquals(0x40, px[5 * w + 5] & 0xFF); // zonas lisas intactas
    }

    @Test
    public void girarCuatroVecesDejaIgual() {
        int w = 7, h = 5;
        int[] px = gradient(w, h);
        int[] r = ImageOps.rotate(px, w, h, 90);
        r = ImageOps.rotate(r, h, w, 90);
        r = ImageOps.rotate(r, w, h, 90);
        r = ImageOps.rotate(r, h, w, 90);
        assertArrayEquals(px, r);
        int[] r180 = ImageOps.rotate(px, w, h, 180);
        assertArrayEquals(ImageOps.rotate(ImageOps.rotate(px, w, h, 90), h, w, 90), r180);
        assertArrayEquals(ImageOps.rotate(ImageOps.rotate(px, w, h, 180), w, h, 90),
                ImageOps.rotate(px, w, h, 270));
    }

    @Test
    public void modoGymBajaAltasLucesYSubeContraste() {
        int w = 256, h = 4;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = 0xFF000000 | (x << 16) | (x << 8) | x;
        Adjustments gym = new Adjustments();
        gym.contrast = 0.25f; gym.highlights = -0.4f; gym.shadows = 0.1f; gym.saturation = -0.1f;
        gym.warmth = 0.05f; gym.vignette = 0f;
        PhotoTuner.applyTone(px, w, h, gym);
        int g220 = (px[w + 220] >> 8) & 0xFF;
        assertTrue("las zonas brillantes deberían bajar: " + g220, g220 < 215);
    }
}
