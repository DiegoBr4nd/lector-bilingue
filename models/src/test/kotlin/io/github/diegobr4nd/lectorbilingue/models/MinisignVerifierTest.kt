package io.github.diegobr4nd.lectorbilingue.models

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinisignVerifierTest {
    private fun res(name: String) = javaClass.getResource("/minisign/$name")!!.readBytes()
    private val pub1 = MinisignPublicKey.parse(String(res("test.pub")))
    private val pub2 = MinisignPublicKey.parse(String(res("test2.pub")))
    private val msg = res("catalog-ok.json")
    private val sig = String(res("catalog-ok.json.minisig"))

    @Test fun `firma valida devuelve el comentario confiable`() =
        assertTrue(MinisignVerifier(listOf(pub1)).verify(msg, sig).isNotEmpty())
    @Test fun `acepta si la llave esta entre varias`() =
        assertTrue(MinisignVerifier(listOf(pub2, pub1)).verify(msg, sig).isNotEmpty())
    @Test fun `un byte alterado se rechaza`() {
        val bad = msg.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(bad, sig) }
    }
    @Test fun `llave desconocida se rechaza`() {
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub2)).verify(msg, sig) }
    }
    @Test fun `firma de otra llave con key id falsificado se rechaza`() {
        val otra = String(res("catalog-otra-llave.json.minisig"))
        val lines = otra.lines().toMutableList()
        val raw = Base64.getDecoder().decode(lines[1]).also { System.arraycopy(pub1.keyId, 0, it, 2, 8) }
        lines[1] = Base64.getEncoder().encodeToString(raw)
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, lines.joinToString("\n")) }
    }
    @Test fun `comentario confiable alterado se rechaza`() {
        val alt = sig.replace(Regex("trusted comment: (.*)")) { "trusted comment: ${it.groupValues[1]}X" }
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, alt) }
    }
    @Test fun `algoritmo legado Ed se rechaza`() {
        assertFailsWith<SignatureException> {
            MinisignVerifier(listOf(pub1)).verify(msg, String(res("catalog-legacy-Ed.json.minisig")))
        }
    }
    /** Firma ED válida con la etiqueta cambiada a "Ed": solo la regla del algoritmo la detiene. */
    @Test fun `etiqueta de algoritmo cambiada a Ed se rechaza aunque la firma sea valida`() {
        val lines = sig.lines().toMutableList()
        val raw = Base64.getDecoder().decode(lines[1]).also { it[1] = 'd'.code.toByte() }
        lines[1] = Base64.getEncoder().encodeToString(raw)
        assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, lines.joinToString("\n")) }
    }
    @Test fun `formato roto se rechaza`() {
        listOf("", "una linea", sig.lines().take(3).joinToString("\n"), sig.replace("trusted comment:", "comment:"))
            .forEach { assertFailsWith<SignatureException> { MinisignVerifier(listOf(pub1)).verify(msg, it) } }
    }
    @Test fun `llave publica mal formada falla al leerla`() {
        assertFailsWith<IllegalArgumentException> { MinisignPublicKey.parse("untrusted comment: x\nAAAA") }
    }

    /** Firma hecha por el programa minisign real (0.12): prueba compatibilidad con la herramienta oficial. */
    @Test fun `firma creada con minisign real se acepta`() {
        val realPub = MinisignPublicKey.parse(String(res("real-minisign.pub")))
        val comment = MinisignVerifier(listOf(realPub))
            .verify(res("real-message.txt"), String(res("real-message.txt.minisig")))
        assertTrue(comment.startsWith("timestamp:"))
    }
    @Test fun `firma real de minisign no vale con la llave de prueba`() {
        assertFailsWith<SignatureException> {
            MinisignVerifier(listOf(pub1)).verify(res("real-message.txt"), String(res("real-message.txt.minisig")))
        }
    }
    /** minisign en Windows escribe la firma con finales de línea CRLF. */
    @Test fun `firma con finales de linea CRLF se acepta`() =
        assertTrue(MinisignVerifier(listOf(pub1)).verify(msg, sig.replace("\n", "\r\n")).isNotEmpty())
    @Test fun `devuelve exactamente el comentario confiable`() =
        assertEquals("timestamp:1759449600\tfile:catalog-ok.json\thashed", MinisignVerifier(listOf(pub1)).verify(msg, sig))
}
