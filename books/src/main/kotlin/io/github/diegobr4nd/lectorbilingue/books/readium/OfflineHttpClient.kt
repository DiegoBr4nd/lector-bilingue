package io.github.diegobr4nd.lectorbilingue.books.readium

import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import java.io.IOException

/** Readium pide un cliente HTTP; este nunca conecta. Leer un libro no usa la red (CLAUDE.md, regla 5). */
class OfflineHttpClient : HttpClient {
    override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
        Try.failure(HttpError.IO(ThrowableError(IOException("sin red en el lector"))))
}
