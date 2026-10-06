package io.github.diegobr4nd.lectorbilingue.books.readium

import android.content.Context
import io.github.diegobr4nd.lectorbilingue.books.BookMetadata
import io.github.diegobr4nd.lectorbilingue.books.ImportError
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.Try
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
        return opener.open(asset, allowUserInteraction = false, onCreatePublication = { container = sanitizing(container) })
            .fold(
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
                    cover = pub.cover(),
                ),
            )
        } finally { pub.close() }
    }

    /** Todo HTML, XHTML y SVG pasa por HtmlSanitizer antes de llegar al navegador. */
    private fun sanitizing(container: Container<Resource>): Container<Resource> =
        TransformingContainer(container) { url, resource ->
            val path = url.path?.lowercase().orEmpty()
            when {
                path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm") -> resource.mapText { HtmlSanitizer.sanitize(it) }
                path.endsWith(".svg") -> resource.mapText { HtmlSanitizer.sanitize(it, isSvg = true) }
                else -> resource
            }
        }

    private fun Resource.mapText(transform: (String) -> String): Resource =
        TransformingResource(this) { bytes -> Try.success(transform(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)) }
}
