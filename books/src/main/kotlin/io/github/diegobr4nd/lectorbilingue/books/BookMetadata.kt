package io.github.diegobr4nd.lectorbilingue.books

import android.graphics.Bitmap

/** Lo que la Biblioteca necesita de un libro. Nunca se registra en el log. */
data class BookMetadata(val title: String?, val author: String?, val cover: Bitmap?)
