package com.valerochka1337.valerochkagym.data.ai

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HealthDocumentRendererTest {
    @Test
    fun `denied metadata returns an access failure`() = runTest {
        val fixture = renderer(openAssetFileFailure = SecurityException())
        val result = fixture.renderer.render(DOCUMENT_URI)

        assertFailure(result, "Нет доступа к документу — введите результаты вручную")
        assertEquals(1, fixture.provider.openAssetFileCalls)
        assertEquals(0, fixture.provider.getTypeCalls)
    }

    @Test
    fun `missing metadata returns a safe failure`() = runTest {
        val fixture = renderer(openAssetFileFailure = FileNotFoundException())
        val result = fixture.renderer.render(DOCUMENT_URI)

        assertFailure(result, "Документ повреждён или защищён паролем — введите результаты вручную")
        assertEquals(1, fixture.provider.openAssetFileCalls)
        assertEquals(0, fixture.provider.getTypeCalls)
    }

    @Test
    fun `type lookup failure returns an access failure`() = runTest {
        val fixture = renderer(typeFailure = SecurityException())
        val result = fixture.renderer.render(DOCUMENT_URI)

        assertFailure(result, "Нет доступа к документу — введите результаты вручную")
        assertEquals(1, fixture.provider.openAssetFileCalls)
        assertEquals(1, fixture.provider.getTypeCalls)
    }

    @Test
    fun `metadata cancellation propagates`() = runTest {
        try {
            renderer(typeFailure = CancellationException()).renderer.render(DOCUMENT_URI)
            throw AssertionError("CancellationException expected")
        } catch (_: CancellationException) {
            // Cancellation belongs to the caller, rather than a manual-entry failure.
        }
    }

    private fun renderer(
        openAssetFileFailure: Throwable? = null,
        typeFailure: Throwable? = null,
    ): RendererFixture {
        val provider = Robolectric.buildContentProvider(MetadataProvider::class.java)
            .create(AUTHORITY)
            .get()
            .apply {
                this.openAssetFileFailure = openAssetFileFailure
                this.typeFailure = typeFailure
            }
        return RendererFixture(
            AndroidHealthDocumentRenderer(
                ContentResolver.wrap(provider),
                Dispatchers.Unconfined,
            ),
            provider,
        )
    }

    private fun assertFailure(result: HealthDocumentRenderResult, expectedMessage: String) {
        val failure = result as? HealthDocumentRenderResult.Failure
            ?: throw AssertionError("Expected renderer failure, was $result")
        assertEquals(expectedMessage, failure.message)
    }

    private data class RendererFixture(
        val renderer: AndroidHealthDocumentRenderer,
        val provider: MetadataProvider,
    )

    private class MetadataProvider : ContentProvider() {
        var openAssetFileFailure: Throwable? = null
        var typeFailure: Throwable? = null
        var openAssetFileCalls = 0
        var getTypeCalls = 0

        override fun onCreate() = true

        override fun getType(uri: Uri): String? {
            getTypeCalls++
            typeFailure?.let { throw it }
            return "image/jpeg"
        }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            openAssetFileCalls++
            openAssetFileFailure?.let { throw it }
            return null
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val AUTHORITY = "com.valerochka1337.valerochkagym.health-document-renderer-test"
        val DOCUMENT_URI: Uri = Uri.parse("content://$AUTHORITY/document")
    }
}
