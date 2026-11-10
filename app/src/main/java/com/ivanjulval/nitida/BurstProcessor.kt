package com.ivanjulval.nitida

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.ivanjulval.nitida.core.Adjustments
import com.ivanjulval.nitida.core.BurstMerger
import com.ivanjulval.nitida.core.ImageOps
import com.ivanjulval.nitida.core.PhotoTuner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resultado: la foto a tamaño completo para guardar y dos versiones reducidas para comparar. */
class ProcessedPhoto(
    val full: Bitmap,
    val before: Bitmap,
    val after: Bitmap,
    val framesUsed: Int,
    val framesTotal: Int,
    val width: Int,
    val height: Int,
)

/** Une todo el proceso: elegir referencia → fusionar → ajustar → girar. Llamar fuera del hilo principal. */
object BurstProcessor {

    private const val PREVIEW_MAX = 2048
    private const val MAX_PIXELS = 13_000_000 // por encima (p. ej. 50/64 MP) se decodifica a la mitad

    fun process(
        frames: List<CapturedFrame>,
        adj: Adjustments,
        mirror: Boolean,
        format: PhotoFormat,
        exactSize: Boolean,
        progress: (String) -> Unit,
    ): ProcessedPhoto {
        require(frames.isNotEmpty()) { "No hay fotos" }

        // 1) La foto más nítida (menos movida) de la ráfaga será la referencia
        val refIndex = if (frames.size == 1) 0 else {
            progress("Eligiendo la foto más nítida…")
            frames.indices.maxBy { sharpnessOf(frames[it].jpeg) }
        }
        val ref = frames[refIndex]
        val rotation = ref.rotationDegrees

        // "Antes": la referencia sin tocar, reducida y con el mismo recorte, para la comparación
        val before = cropBitmap(orient(decode(ref.jpeg, sampleSize = 2), rotation, mirror), format)

        // 2) Fusión
        val sample = if (pixelCount(ref.jpeg) > MAX_PIXELS) 2 else 1
        val merged = merge(frames, refIndex, sample, progress)

        // 3) Recorte al formato. Se hace antes de girar: si la foto se girará 90°, la proporción
        //    en la imagen sin girar es la inversa. Antes de los ajustes para que la viñeta quede centrada.
        val quarter = rotation % 180 != 0
        val crop = if (quarter) ImageOps.cropSize(merged.width, merged.height, format.ratioH, format.ratioW)
                   else ImageOps.cropSize(merged.width, merged.height, format.ratioW, format.ratioH)
        val pixels = ImageOps.cropCenter(merged.pixels, merged.width, merged.height, crop[0], crop[1])

        // 4) Ajustes del modo
        progress("Aplicando el modo…")
        PhotoTuner.process(pixels, crop[0], crop[1], adj)

        // 5) Girar (y espejo en selfies), pasar a Bitmap y, si se pide, al tamaño exacto
        var full = toBitmap(pixels, crop[0], crop[1], rotation, mirror)
        if (exactSize) full = scaleExact(full, format)
        val after = scaleToMax(full, PREVIEW_MAX)
        return ProcessedPhoto(full, before, after, merged.used, frames.size, full.width, full.height)
    }

    /** Escala al tamaño recomendado del formato. "Completo" sigue la orientación de la foto. */
    private fun scaleExact(src: Bitmap, format: PhotoFormat): Bitmap {
        var tw = format.targetW
        var th = format.targetH
        if (format.ratioW == 0 && src.width > src.height) { tw = format.targetH; th = format.targetW }
        if (format.ratioW == 0) {
            // Sin recorte la proporción puede no ser exacta: se respeta la de la foto
            val f = min(tw, th) / min(src.width, src.height).toFloat()
            tw = (src.width * f).roundToInt()
            th = (src.height * f).roundToInt()
        }
        if (src.width == tw && src.height == th) return src
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }

    private fun cropBitmap(src: Bitmap, format: PhotoFormat): Bitmap {
        val c = ImageOps.cropSize(src.width, src.height, format.ratioW, format.ratioH)
        if (c[0] == src.width && c[1] == src.height) return src
        return Bitmap.createBitmap(src, (src.width - c[0]) / 2, (src.height - c[1]) / 2, c[0], c[1])
    }

    private class Merged(val pixels: IntArray, val width: Int, val height: Int, val used: Int)

    private fun merge(frames: List<CapturedFrame>, refIndex: Int, sample: Int, progress: (String) -> Unit): Merged {
        progress(if (frames.size > 1) "Fusionando 1/${frames.size}…" else "Procesando…")
        val refBmp = decode(frames[refIndex].jpeg, sample, mutable = true)
        val w = refBmp.width
        val h = refBmp.height
        val pixels = IntArray(w * h)
        refBmp.getPixels(pixels, 0, w, 0, 0, w, h)
        if (frames.size == 1) return Merged(pixels, w, h, 1)

        val merger = BurstMerger(pixels, w, h)
        // Se reutilizan el Bitmap y el array para no llenar la memoria con 8 fotos de 12 MP
        var scratch: Bitmap? = refBmp
        val px = IntArray(w * h)
        var n = 1
        for ((i, frame) in frames.withIndex()) {
            if (i == refIndex) continue
            n++
            progress("Fusionando $n/${frames.size}…")
            val bmp = decode(frame.jpeg, sample, mutable = true, reuse = scratch)
            scratch = bmp
            if (bmp.width != w || bmp.height != h) continue
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            merger.add(px)
        }
        return Merged(merger.result(), w, h, merger.mergedCount)
    }

    private fun toBitmap(pixels: IntArray, w: Int, h: Int, rotation: Int, mirror: Boolean): Bitmap {
        val rotated = ImageOps.rotate(pixels, w, h, rotation)
        val rw = if (rotation % 180 != 0) h else w
        val rh = if (rotation % 180 != 0) w else h
        if (mirror) ImageOps.mirror(rotated, rw, rh)
        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(rotated, 0, rw, 0, 0, rw, rh)
        return bmp
    }

    private fun sharpnessOf(jpeg: ByteArray): Double {
        val small = decode(jpeg, sampleSize = 8)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return ImageOps.sharpnessScore(px, small.width, small.height)
    }

    private fun pixelCount(jpeg: ByteArray): Long {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth.toLong() * o.outHeight
    }

    private fun decode(jpeg: ByteArray, sampleSize: Int, mutable: Boolean = false, reuse: Bitmap? = null): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutable || reuse != null
            inBitmap = reuse
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (e: IllegalArgumentException) {
            // El Bitmap a reutilizar no vale (tamaño distinto): se decodifica en uno nuevo
            opts.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        }
        return bmp ?: throw IllegalStateException("No se pudo leer la foto")
    }

    private fun orient(src: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        if (rotation == 0 && !mirror) return src
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src.copy(Bitmap.Config.ARGB_8888, false)
        val f = maxSide / longest.toFloat()
        return Bitmap.createScaledBitmap(src, (src.width * f).roundToInt(), (src.height * f).roundToInt(), true)
    }
}
