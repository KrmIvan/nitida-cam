package com.ivanjulval.nitida

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guarda la foto en la galería (Imágenes/Nitida). No necesita permisos en Android 10+. */
object PhotoSaver {

    fun save(context: Context, bitmap: Bitmap): Uri {
        val name = "NITIDA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nitida")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("No se pudo crear la foto en la galería")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir la foto para escribir")
            out.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IOException("Error al comprimir la foto")
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
