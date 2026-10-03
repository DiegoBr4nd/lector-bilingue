package io.github.diegobr4nd.lectorbilingue.models

import java.util.Base64
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Llave pública minisign.
 *
 * Formato del archivo `.pub` (2 líneas):
 * - línea 1: `untrusted comment: …` (texto libre, no se confía en él);
 * - línea 2: base64 de `"Ed"(2) || keyId(8) || pk(32)` = 42 bytes.
 *
 * `keyId` se guarda en el orden del archivo (minisign lo imprime al revés, en little-endian).
 */
data class MinisignPublicKey(val keyId: ByteArray, val key: ByteArray) {
    companion object {
        /** Lee el contenido de un `.pub`. Lanza [IllegalArgumentException] si está mal formado. */
        fun parse(text: String): MinisignPublicKey {
            val line = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("untrusted comment:") }
                .singleOrNull() ?: throw IllegalArgumentException("llave pública mal formada")
            val raw = runCatching { Base64.getDecoder().decode(line) }.getOrNull()
            require(raw != null && raw.size == 42 && raw[0] == 'E'.code.toByte() && raw[1] == 'd'.code.toByte()) {
                "llave pública mal formada"
            }
            return MinisignPublicKey(raw.copyOfRange(2, 10), raw.copyOfRange(10, 42))
        }
    }
    override fun equals(other: Any?) = other is MinisignPublicKey && keyId.contentEquals(other.keyId) && key.contentEquals(other.key)
    override fun hashCode() = keyId.contentHashCode()
}

class SignatureException(message: String) : Exception(message)

/**
 * Verifica firmas minisign (algoritmo `ED`: Ed25519 sobre BLAKE2b-512) y su comentario confiable.
 *
 * Formato del archivo `.minisig` (4 líneas):
 * - línea 1: `untrusted comment: …`;
 * - línea 2: base64 de `alg(2) || keyId(8) || sig(64)` = 74 bytes;
 * - línea 3: `trusted comment: <texto>`;
 * - línea 4: base64 de `globalSig(64)`.
 *
 * Reglas: `alg` debe ser `"ED"` (el legado `"Ed"`, sin prehash, se rechaza); `keyId` debe ser de una
 * llave confiable; `sig` = Ed25519(BLAKE2b-512(mensaje)); `globalSig` = Ed25519(sig || utf8(texto)).
 */
class MinisignVerifier(private val trustedKeys: List<MinisignPublicKey>) {
    /** Devuelve el texto del comentario confiable si todo es válido; si no, lanza [SignatureException]. */
    fun verify(message: ByteArray, signatureFile: String): String {
        if (signatureFile.length > 4096) throw SignatureException("firma demasiado grande")
        val lines = signatureFile.lines().map { it.trimEnd('\r') }
        if (lines.size < 4 || !lines[0].startsWith("untrusted comment:") || !lines[2].startsWith("trusted comment: ")) {
            throw SignatureException("formato de firma inválido")
        }
        val sigBlock = decode(lines[1], 74)
        val alg = String(sigBlock, 0, 2, Charsets.US_ASCII)
        if (alg != "ED") throw SignatureException("algoritmo de firma no admitido")
        val keyId = sigBlock.copyOfRange(2, 10)
        val signature = sigBlock.copyOfRange(10, 74)
        val key = trustedKeys.firstOrNull { it.keyId.contentEquals(keyId) }
            ?: throw SignatureException("firma de una llave desconocida")
        val trustedComment = lines[2].removePrefix("trusted comment: ")
        val globalSignature = decode(lines[3], 64)

        if (!ed25519(key.key, blake2b512(message), signature)) throw SignatureException("firma inválida")
        if (!ed25519(key.key, signature + trustedComment.toByteArray(Charsets.UTF_8), globalSignature)) {
            throw SignatureException("comentario confiable alterado")
        }
        return trustedComment
    }

    private fun decode(b64: String, size: Int): ByteArray {
        val raw = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()
        if (raw == null || raw.size != size) throw SignatureException("formato de firma inválido")
        return raw
    }

    private fun blake2b512(data: ByteArray): ByteArray {
        val d = Blake2bDigest(512)
        d.update(data, 0, data.size)
        return ByteArray(64).also { d.doFinal(it, 0) }
    }

    private fun ed25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }
}
