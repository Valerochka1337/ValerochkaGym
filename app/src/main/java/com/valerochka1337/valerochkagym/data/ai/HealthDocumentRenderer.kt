package com.valerochka1337.valerochkagym.data.ai

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface HealthDocumentRenderResult {
    data class Success(val pages: List<String>) : HealthDocumentRenderResult
    data class Failure(val message: String) : HealthDocumentRenderResult
}

interface HealthDocumentRenderer { suspend fun render(uri: Uri): HealthDocumentRenderResult }

/** Platform-only renderer; never OCRs or silently discards a page. */
@Singleton
class AndroidHealthDocumentRenderer @Inject constructor(
    private val resolver: ContentResolver,
    @param:ComputeDispatcher private val dispatcher: CoroutineDispatcher,
) : HealthDocumentRenderer {
    override suspend fun render(uri: Uri): HealthDocumentRenderResult = withContext(dispatcher) {
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        if (size != null && size > MAX_SOURCE_BYTES) return@withContext HealthDocumentRenderResult.Failure("Документ больше 20 МиБ — введите результаты вручную")
        val type = resolver.getType(uri).orEmpty()
        try {
            if (type == "application/pdf" || uri.toString().endsWith(".pdf", true)) renderPdf(uri) else renderImage(uri)
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { HealthDocumentRenderResult.Failure("Нет доступа к документу — введите результаты вручную") }
        catch (_: Exception) { HealthDocumentRenderResult.Failure("Документ повреждён или защищён паролем — введите результаты вручную") }
    }

    private suspend fun renderImage(uri: Uri): HealthDocumentRenderResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return HealthDocumentRenderResult.Failure("Не удалось открыть изображение — введите результаты вручную")
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION || bounds.outHeight / sample > MAX_IMAGE_DIMENSION ||
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_IMAGE_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return HealthDocumentRenderResult.Failure("Не удалось открыть изображение — введите результаты вручную")
        return try { bitmap.jpegDataUrl()?.let { HealthDocumentRenderResult.Success(listOf(it)) }
            ?: HealthDocumentRenderResult.Failure("Изображение слишком большое — введите результаты вручную")
        } finally { bitmap.recycle() }
    }

    private suspend fun renderPdf(uri: Uri): HealthDocumentRenderResult {
        val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
            ?: return HealthDocumentRenderResult.Failure("Не удалось открыть PDF — введите результаты вручную")
        descriptor.use { fd -> PdfRenderer(fd).use { pdf ->
            if (pdf.pageCount !in 1..MAX_PAGES) return HealthDocumentRenderResult.Failure("PDF должен содержать от 1 до 10 страниц")
            val pages = buildList {
                repeat(pdf.pageCount) { index ->
                    coroutineContext.ensureActive()
                    pdf.openPage(index).use { page ->
                        val scale = minOf(1f, 1600f / maxOf(page.width, page.height).toFloat())
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        add(bitmap.jpegDataUrl() ?: return HealthDocumentRenderResult.Failure("Страница PDF слишком большая"))
                    }
                    if (sumOf { it.length } > MAX_REQUEST_CHARS) return HealthDocumentRenderResult.Failure("Документ слишком большой для отправки")
                }
            }
            return HealthDocumentRenderResult.Success(pages)
        } }
    }

    private fun Bitmap.jpegDataUrl(): String? {
        val bytes = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        if (bytes.size > MAX_PAGE_BYTES) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private companion object { const val MAX_SOURCE_BYTES = 20 * 1024 * 1024; const val MAX_PAGES = 10; const val MAX_PAGE_BYTES = 6 * 1024 * 1024; const val MAX_REQUEST_CHARS = 20 * 1024 * 1024 * 4 / 3; const val MAX_IMAGE_DIMENSION = 2_048; const val MAX_IMAGE_PIXELS = 4_194_304L }
}
