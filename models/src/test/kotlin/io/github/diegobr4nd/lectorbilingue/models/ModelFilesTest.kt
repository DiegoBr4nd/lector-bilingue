package io.github.diegobr4nd.lectorbilingue.models

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hashInto_calculaElSha256() {
        val f = File(tmp.root, "a.part").apply { writeBytes("hola".toByteArray()) }
        val d = ModelFiles.newDigest()
        ModelFiles.hashInto(f, d)
        assertEquals("b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79", ModelFiles.hex(d.digest()))
    }

    @Test
    fun hashInto_errorDeLecturaDelDiscoEsModelFileExceptionSinRutaNiCausa() {
        val f = File(tmp.root, "secreto-ruta.part").apply { writeBytes(ByteArray(10)) }
        val failing = { _: File ->
            object : InputStream() {
                override fun read(): Int = throw IOException("fallo en ${f.absolutePath}")
            }
        }
        val e = assertFailsWith<ModelFileException> { ModelFiles.hashInto(f, ModelFiles.newDigest(), failing) }
        assertNull(e.cause)
        assertTrue(!e.message!!.contains("secreto-ruta"))
        assertIs<IOException>(e)
    }
}
