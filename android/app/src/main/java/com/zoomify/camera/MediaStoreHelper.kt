package com.zoomify.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object MediaStoreHelper {
    private const val FOLDER = "DCIM/Zoomify"

    fun imageOutputOptions(context: Context, name: String, mimeType: String): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
    }

    fun videoOutputOptions(context: Context, name: String): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        return MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    /**
     * Digital zoom beyond the camera's own maximum: centre-crop the full-quality JPEG, scale it back up to the
     * original size (like the stock camera's digital zoom), keep the EXIF data and save it to the gallery.
     */
    fun saveCroppedJpeg(context: Context, source: File, zoom: Float, name: String): Uri? {
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false) ?: return null
        val width = decoder.width
        val height = decoder.height
        val cropWidth = (width / zoom).toInt().coerceAtLeast(1)
        val cropHeight = (height / zoom).toInt().coerceAtLeast(1)
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        val region = decoder.decodeRegion(Rect(left, top, left + cropWidth, top + cropHeight), BitmapFactory.Options())
        decoder.recycle()
        val scaled = Bitmap.createScaledBitmap(region, width, height, true)
        if (scaled !== region) region.recycle()

        val temp = File(context.cacheDir, "$name-zoomed.jpg")
        FileOutputStream(temp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        scaled.recycle()
        copyExif(source, temp)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        temp.delete()
        return uri
    }

    private fun copyExif(from: File, to: File) {
        try {
            val source = ExifInterface(from.absolutePath)
            val target = ExifInterface(to.absolutePath)
            val tags = listOf(
                ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
            )
            for (tag in tags) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            target.saveAttributes()
        } catch (e: Exception) {
            // EXIF is nice to have; the photo itself is already saved.
        }
    }

    fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
    } catch (e: Exception) {
        null
    }
}
