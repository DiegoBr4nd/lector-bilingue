package io.github.diegobr4nd.lectorbilingue.books.readium

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** Abre EPUBs guardados con Readium, sin red y con el HTML limpio (HtmlSanitizer). */
class ReadiumBooks(context: Context) {
    private val http = OfflineHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)

    // Sin onCreatePublication aquí: en Readium 3.4.0 el del constructor nunca se llama (spike, P3).
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, http, assets, pdfFactory = null),
        contentProtections = emptyList(),
    )

    suspend fun open(file: File): Try<Publication, ImportError> {
        val asset = assets.retrieve(file).getOrElse { return Try.failure(ImportError.NOT_EPUB) }
        // El saneado va en open(): Readium lo aplica dos veces, por eso HtmlSanitizer es idempotente.
        return opener.open(
            asset,
            allowUserInteraction = false,
            onCreatePublication = { container = sanitizing(container, manifest) },
        ).fold(
            { Try.success(it) },
            {
                asset.close() // Sin publicación nadie más cierra el archivo.
                Try.failure(ImportError.DAMAGED)
            },
        )
    }

    /** Abre, lee título, autor y portada, y cierra. */
    suspend fun metadata(file: File): Try<BookMetadata, ImportError> {
        val pub = open(file).getOrElse { return Try.failure(it) }
        return try {
            val m = pub.metadata
            Try.success(
                BookMetadata(
                    title = m.title?.takeIf { it.isNotBlank() },
                    author = m.authors.mapNotNull { it.name.takeIf(String::isNotBlank) }.joinToString(", ").ifBlank { null },
                    cover = boundedCover(pub),
                ),
            )
        } finally { pub.close() }
    }

    /**
     * Portada decodificada con tope de tamaño. No se usa `Publication.cover()` de Readium porque decodifica
     * la imagen a tamaño completo: un PNG pequeño que declara 30000 × 30000 pediría 3,6 GB y tumbaría la app
     * al importar. Aquí se leen primero solo las medidas y se decodifica muestreado, con el lado mayor en
     * [ResourceSanitizing.MAX_COVER_SIDE] o menos. Cualquier problema = sin portada (no es un error del libro).
     */
    private suspend fun boundedCover(pub: Publication): Bitmap? {
        val link = pub.linkWithRel("cover") ?: return null
        val resource = pub.get(link) ?: return null
        return try {
            val length = resource.length().getOrNull() ?: return null
            if (length > ResourceSanitizing.MAX_COVER_BYTES) return null
            val bytes = resource.read().getOrNull() ?: return null
            decodeBounded(bytes)
        } finally { resource.close() }
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = ResourceSanitizing.coverSampleSize(bounds.outWidth, bounds.outHeight, ResourceSanitizing.MAX_COVER_SIDE)
        sample?.let { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = it }) }
    } catch (e: OutOfMemoryError) {
        null // Último recurso: nunca debería pasar con el muestreo.
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Todo recurso pasa por HtmlSanitizer salvo los de tipo inerte ([ResourceSanitizing.kindFor]).
     * El tipo se decide igual que lo hará Readium al servirlo al WebView (`WebViewServer`): el del
     * manifiesto y, si el recurso no está en él, el que Android deduce de la extensión. Nunca por la
     * extensión sola: un `c1.xml` declarado `application/xhtml+xml` se muestra como XHTML.
     */
    private fun sanitizing(container: Container<Resource>, manifest: Manifest): Container<Resource> =
        TransformingContainer(container) { url, resource ->
            val mediaType = manifest.linkWithHref(url)?.mediaType?.toString() ?: mediaTypeFromExtension(url)
            when (val kind = ResourceSanitizing.kindFor(mediaType)) {
                null -> resource
                // Un charset que no es UTF-8 en el Content-Type cambiaría cómo el WebView lee nuestra salida: no se sirve.
                else -> if (ResourceSanitizing.hasForeignCharset(mediaType)) {
                    TransformingResource(resource) { ResourceSanitizing.refused() }
                } else {
                    TransformingResource(resource) { bytes -> ResourceSanitizing.sanitizeSafely(bytes, kind) }
                }
            }
        }

    /** Igual que `WebViewServer.mediaTypeFromUrl` de Readium 3.4.0. */
    private fun mediaTypeFromExtension(url: Url): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(url.normalize().toString())?.takeIf { it.isNotEmpty() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
