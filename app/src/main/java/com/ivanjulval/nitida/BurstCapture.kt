package com.ivanjulval.nitida

import android.graphics.ImageFormat
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Una foto de la ráfaga: el JPEG tal cual sale de la cámara y cuánto hay que girarlo. */
class CapturedFrame(val jpeg: ByteArray, val rotationDegrees: Int)

/** Hace una foto y devuelve el JPEG en memoria (sin guardarlo todavía). */
suspend fun ImageCapture.takeJpeg(executor: Executor): CapturedFrame =
    suspendCancellableCoroutine { cont ->
        takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    check(image.format == ImageFormat.JPEG) { "Formato inesperado: ${image.format}" }
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    cont.resume(CapturedFrame(bytes, image.imageInfo.rotationDegrees))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

/**
 * Hace [count] fotos seguidas lo más rápido posible. [onProgress] se llama tras cada foto
 * (en el hilo de la corrutina que llama, normalmente el principal).
 */
suspend fun captureBurst(
    imageCapture: ImageCapture,
    count: Int,
    executor: Executor,
    onProgress: (Int) -> Unit,
): List<CapturedFrame> {
    val frames = ArrayList<CapturedFrame>(count)
    repeat(count) { i ->
        frames += imageCapture.takeJpeg(executor)
        onProgress(i + 1)
    }
    return frames
}
