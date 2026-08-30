package com.valerochka1337.valerochkagym.data.ai

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.graphics.scale

sealed interface InBodyPhotoEncodingResult {
    data class Success(val jpegDataUrl: String) : InBodyPhotoEncodingResult
    data class Failure(val message: String) : InBodyPhotoEncodingResult
}

/** Converts one locally selected image into a bounded JPEG data URL for the vision request. */
interface InBodyPhotoEncoder {
    suspend fun encode(uri: Uri): InBodyPhotoEncodingResult
}

/**
 * The source URI is read once into a scaled bitmap and never copied to app storage. JPEG avoids
 * unsupported HEIC input at providers and bounds both memory use and request size.
 */
@Singleton
class AndroidInBodyPhotoEncoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : InBodyPhotoEncoder {

    override suspend fun encode(uri: Uri): InBodyPhotoEncodingResult = withContext(computeDispatcher) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext InBodyPhotoEncodingResult.Failure(UNREADABLE_IMAGE_MESSAGE)
            }

            val bitmap = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } ?: return@withContext InBodyPhotoEncodingResult.Failure(UNREADABLE_IMAGE_MESSAGE)

            var prepared = bitmap.withExifOrientation(resolver, uri)
            if (prepared !== bitmap) bitmap.recycle()
            prepared = prepared.scaleToMaxSide()
            try {
                var jpeg = prepared.toBoundedJpeg()
                while (jpeg.size > MAX_JPEG_BYTES && maxOf(prepared.width, prepared.height) > MIN_IMAGE_SIDE) {
                    val reduced = prepared.scaleDown()
                    prepared.recycle()
                    prepared = reduced
                    jpeg = prepared.toBoundedJpeg()
                }
                if (jpeg.size > MAX_JPEG_BYTES) {
                    InBodyPhotoEncodingResult.Failure(TOO_LARGE_IMAGE_MESSAGE)
                } else {
                    InBodyPhotoEncodingResult.Success(
                        "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}",
                    )
                }
            } finally {
                prepared.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            InBodyPhotoEncodingResult.Failure(UNREADABLE_IMAGE_MESSAGE)
        }
    }

    private fun Bitmap.withExifOrientation(resolver: ContentResolver, uri: Uri): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                postRotate(90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                postRotate(270f)
                postScale(-1f, 1f)
            }

            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    /** The power-of-two decoder sampling is only approximate; enforce the wire-size bound exactly. */
    private fun Bitmap.scaleToMaxSide(): Bitmap {
        val maxSide = maxOf(width, height)
        if (maxSide <= MAX_IMAGE_SIDE) return this
        val scale = MAX_IMAGE_SIDE.toFloat() / maxSide
        val resized = this.scale(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
        if (resized !== this) recycle()
        return resized
    }

    private fun Bitmap.toBoundedJpeg(): ByteArray {
        var quality = INITIAL_QUALITY
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().use { output ->
                check(compress(Bitmap.CompressFormat.JPEG, quality, output))
                output.toByteArray()
            }
            quality -= QUALITY_STEP
        } while (bytes.size > MAX_JPEG_BYTES && quality >= MIN_QUALITY)
        return bytes
    }

    private fun Bitmap.scaleDown(): Bitmap = this.scale(
        (width * SCALE_DOWN_FACTOR).toInt().coerceAtLeast(1),
        (height * SCALE_DOWN_FACTOR).toInt().coerceAtLeast(1),
    )

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var maxSide = maxOf(width, height)
        while (maxSide / 2 >= MAX_IMAGE_SIDE) {
            sample *= 2
            maxSide /= 2
        }
        return sample
    }

    private companion object {
        // На листе InBody много мелких чисел в пяти сегментах. 3072 px сохраняют их читаемыми,
        // но не декодируют полный снимок камеры в десятки мегапикселей.
        const val MAX_IMAGE_SIDE = 3_072
        const val MAX_JPEG_BYTES = 6 * 1024 * 1024
        const val MIN_IMAGE_SIDE = 1_024
        const val SCALE_DOWN_FACTOR = 0.8f
        const val INITIAL_QUALITY = 92
        const val MIN_QUALITY = 68
        const val QUALITY_STEP = 6
        const val UNREADABLE_IMAGE_MESSAGE = "Не удалось подготовить фото — выберите другой снимок"
        const val TOO_LARGE_IMAGE_MESSAGE = "Фото слишком большое для безопасной отправки — снимите лист ближе"
    }
}
