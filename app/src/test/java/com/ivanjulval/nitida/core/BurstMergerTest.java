package com.ivanjulval.nitida.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BurstMergerTest {

    private static final double[][] SHAKE = {
            {0, 0}, {7, -3}, {-5, 4}, {12, 9}, {-9, -11}, {3, 14}, {-14, 2}, {6, -8}};

    @Test
    public void fusionarRafagaQuietaReduceRuido() {
        int w = 640, h = 480;
        SyntheticBurst s = new SyntheticBurst(w + 80, h + 80, 1);
        int[] clean = s.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 0, 0);
        int[] ref = s.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 10, 100);
        double single = SyntheticBurst.psnr(ref, clean, w, h, 16);

        BurstMerger m = new BurstMerger(ref.clone(), w, h);
        for (int k = 1; k < 6; k++) {
            int[] f = s.frame(w, h, SHAKE[k][0], SHAKE[k][1], 0, 1, -1, 0, 0, 10, 100 + k);
            assertTrue("foto " + k + " descartada", m.add(f));
            assertEquals(-SHAKE[k][0], m.getLastDx(), 0.0);
            assertEquals(-SHAKE[k][1], m.getLastDy(), 0.0);
        }
        double merged = SyntheticBurst.psnr(m.result(), clean, w, h, 16);
        System.out.printf("quieta: 1 foto %.2f dB -> 6 fotos %.2f dB%n", single, merged);
        assertTrue("mejora insuficiente", merged - single > 4.0);
    }

    @Test
    public void conGiroDeLaManoTambienMejora() {
        int w = 1024, h = 768;
        SyntheticBurst s = new SyntheticBurst(w + 120, h + 120, 2);
        int[] clean = s.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 0, 0);
        int[] ref = s.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 10, 200);
        double single = SyntheticBurst.psnr(ref, clean, w, h, 24);
        double[] rot = {0, 0.3, -0.25, 0.2, -0.35, 0.15};
        BurstMerger m = new BurstMerger(ref.clone(), w, h);
        for (int k = 1; k < rot.length; k++) {
            m.add(s.frame(w, h, SHAKE[k][0], SHAKE[k][1], rot[k], 1, -1, 0, 0, 10, 200 + k));
        }
        double merged = SyntheticBurst.psnr(m.result(), clean, w, h, 24);
        System.out.printf("con giro: 1 foto %.2f dB -> %d fotos %.2f dB%n", single, m.getMergedCount(), merged);
        assertTrue(m.getMergedCount() >= 5);
        assertTrue("mejora insuficiente con giro", merged - single > 3.0);
    }

    @Test
    public void objetoEnMovimientoNoDejaFantasmas() {
        int w = 640, h = 480, size = 70;
        SyntheticBurst s = new SyntheticBurst(w + 80, h + 80, 3);
        // En la referencia el objeto está a la izquierda; en las demás fotos va moviéndose a la derecha
        int[] clean = s.frame(w, h, 0, 0, 0, 1, 60, 200, size, 0, 0);
        int[] ref = s.frame(w, h, 0, 0, 0, 1, 60, 200, size, 8, 300);
        BurstMerger m = new BurstMerger(ref.clone(), w, h);
        for (int k = 1; k < 6; k++) {
            int[] f = s.frame(w, h, SHAKE[k][0], SHAKE[k][1], 0, 1, 60 + k * 90, 200, size, 8, 300 + k);
            m.add(f);
        }
        int[] out = m.result();
        // Donde estuvo el objeto en otras fotos, el resultado debe parecerse al fondo limpio
        double worst = 0;
        for (int k = 1; k < 6; k++) {
            int ox = 60 + k * 90;
            double e = SyntheticBurst.mae(out, clean, w, ox, 200, Math.min(w, ox + size), 200 + size);
            worst = Math.max(worst, e);
        }
        double objErr = SyntheticBurst.mae(out, clean, w, 60, 200, 60 + size, 200 + size);
        System.out.printf("fantasmas: error máx. donde pasó el objeto %.2f, en el objeto %.2f%n", worst, objErr);
        assertTrue("hay fantasma", worst < 7.0);
        // El objeto solo está en la referencia: ahí no se promedia y queda el ruido de una foto (~6-8)
        assertTrue("el objeto de la referencia se ha borrado", objErr < 10.0);
        double vsRef = SyntheticBurst.mae(out, ref, w, 60 + 4, 200 + 4, 60 + size - 4, 200 + size - 4);
        assertTrue("el objeto debería quedar como en la referencia: " + vsRef, vsRef < 1.0);
    }

    @Test
    public void toleraCambiosDeExposicionEntreFotos() {
        int w = 640, h = 480;
        SyntheticBurst s = new SyntheticBurst(w + 80, h + 80, 4);
        int[] clean = s.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 0, 0);
        int[] ref = s.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 10, 400);
        double single = SyntheticBurst.psnr(ref, clean, w, h, 16);
        double[] gains = {1, 0.92, 1.07, 0.95, 1.05};
        BurstMerger m = new BurstMerger(ref.clone(), w, h);
        for (int k = 1; k < gains.length; k++) {
            m.add(s.frame(w, h, SHAKE[k][0], SHAKE[k][1], 0, gains[k], -1, 0, 0, 10, 400 + k));
        }
        double merged = SyntheticBurst.psnr(m.result(), clean, w, h, 16);
        System.out.printf("exposición variable: %.2f dB -> %.2f dB (%d fotos)%n", single, merged, m.getMergedCount());
        assertTrue(merged - single > 3.0);
    }

    @Test
    public void descartaFotoQueNoTieneNadaQueVer() {
        int w = 320, h = 240;
        SyntheticBurst a = new SyntheticBurst(w + 40, h + 40, 5);
        SyntheticBurst b = new SyntheticBurst(w + 40, h + 40, 99);
        int[] ref = a.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 5, 1);
        int[] other = b.frame(w, h, 0, 0, 0, 1, -1, 0, 0, 5, 2);
        for (int i = 0; i < other.length; i++) other[i] = 0xFF000000 | (~other[i] & 0xFFFFFF); // negativo
        BurstMerger m = new BurstMerger(ref, w, h);
        boolean added = m.add(other);
        System.out.printf("foto ajena: aceptado %.0f%% de bloques%n", 100 * m.getLastAcceptedFraction());
        assertTrue(!added);
        assertEquals(1, m.getMergedCount());
    }

    @Test
    public void personaGrandeMoviendoseNoDejaFantasmas() {
        // Como en el gym: alguien ocupa media foto y se mueve durante la ráfaga
        int w = 640, h = 480, size = 300;
        SyntheticBurst s = new SyntheticBurst(w + 80, h + 80, 6);
        int[] clean = s.frame(w, h, 0, 0, 0, 1, 40, 90, size, 0, 0);
        int[] ref = s.frame(w, h, 0, 0, 0, 1, 40, 90, size, 8, 600);
        BurstMerger m = new BurstMerger(ref.clone(), w, h);
        for (int k = 1; k < 6; k++) {
            m.add(s.frame(w, h, SHAKE[k][0], SHAKE[k][1], 0, 1, 40 + k * 50, 90, size, 8, 600 + k));
        }
        int[] out = m.result();
        // Zona a la derecha del objeto de la referencia, por donde pasó en las otras fotos
        double e = SyntheticBurst.mae(out, clean, w, 40 + size + 4, 94, w - 16, 90 + size - 4);
        System.out.printf("persona grande: error en la zona por donde pasó %.2f (%d fotos)%n", e, m.getMergedCount());
        assertTrue("hay fantasma con un objeto grande", e < 7.0);
    }
}
