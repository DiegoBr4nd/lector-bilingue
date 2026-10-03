package io.github.diegobr4nd.lectorbilingue.models

/** Llaves minisign en las que confía la app para el catálogo de modelos. */
object TrustedKeys {
    /** Llave pública actual (id 2FD11AA2479CDAA4): firma el catálogo en uso. */
    private const val ACTUAL = """untrusted comment: minisign public key 2FD11AA2479CDAA4
RWSk2pxHohrRL3YTCpPnO7YsfYCDwkgjPE9yZe1NukQ7t0j11b0mDrNU
"""

    /** Llave pública de reserva (id 162048C19C9F87EC): para rotar la llave actual si se compromete. */
    private const val RESERVA = """untrusted comment: minisign public key 162048C19C9F87EC
RWTsh5+cwUggFpKRkmlzqGKFjemUgfy5Ufp5UZ6eWtEFIZ8s2BFjCkph
"""

    /** Llaves públicas de producción: primero la actual, luego la de reserva. */
    val production: List<MinisignPublicKey> = listOf(
        MinisignPublicKey.parse(ACTUAL),
        MinisignPublicKey.parse(RESERVA),
    )
}
