package io.github.diegobr4nd.lectorbilingue.models

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogParserTest {

    private val prefijo = CatalogParser.URL_PREFIX
    private val hexA = "a".repeat(64)
    private val hexB = "0123456789abcdef".repeat(4)

    private fun archivo(name: String, size: Long, sha: String) = JSONObject()
        .put("name", name).put("size", size).put("sha256", sha)
        .put("url", "${prefijo}opus-en-es-v1/$name")

    private fun modelo(id: String = "opus-en-es-tcbig-2026.10", pair: String = "en-es") = JSONObject()
        .put("id", id)
        .put("pair", pair)
        .put("engine", "opus")
        .put("modelVersion", "tc-big-2026.10")
        .put("license", "CC-BY-4.0")
        .put("attribution", "Helsinki-NLP / OPUS-MT, Tiedemann et al.")
        .put(
            "files",
            JSONArray()
                .put(archivo("model.bin", 235_883_903L, hexA))
                .put(archivo("vocab.spm", 800_000L, hexB)),
        )

    /** Catálogo correcto: 1 modelo, 2 archivos. */
    private fun valido(): JSONObject = JSONObject()
        .put("version", 1)
        .put("generated", "2026-10-03T00:00:00Z")
        .put("models", JSONArray().put(modelo()))

    private fun JSONObject.m0(): JSONObject = getJSONArray("models").getJSONObject(0)
    private fun JSONObject.f(j: Int): JSONObject = m0().getJSONArray("files").getJSONObject(j)

    private fun parse(o: JSONObject): Catalog = CatalogParser.parse(o.toString().toByteArray(Charsets.UTF_8))

    private fun rechaza(o: JSONObject): CatalogException =
        assertFailsWith<CatalogException> { parse(o) }

    private fun rechazaBytes(b: ByteArray): CatalogException =
        assertFailsWith<CatalogException> { CatalogParser.parse(b) }

    // ---------- Casos válidos ----------

    @Test fun `catalogo valido se lee completo`() {
        val c = parse(valido())
        assertEquals(1, c.version)
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), c.generated)
        assertEquals(1, c.models.size)
        val m = c.models[0]
        assertEquals("opus-en-es-tcbig-2026.10", m.id)
        assertEquals("en-es", m.pair)
        assertEquals("opus", m.engine)
        assertEquals("tc-big-2026.10", m.modelVersion)
        assertEquals("CC-BY-4.0", m.license)
        assertEquals("Helsinki-NLP / OPUS-MT, Tiedemann et al.", m.attribution)
        assertEquals(
            ModelFile("model.bin", 235_883_903L, hexA, "${prefijo}opus-en-es-v1/model.bin"),
            m.files[0],
        )
        assertEquals("vocab.spm", m.files[1].name)
    }

    @Test fun `totalSize suma los archivos`() {
        assertEquals(235_883_903L + 800_000L, parse(valido()).models[0].totalSize)
    }

    @Test fun `findByPair filtra por par`() {
        val o = valido()
        o.getJSONArray("models").put(modelo(id = "opus-en-fr-1", pair = "en-fr"))
        val c = parse(o)
        assertEquals(listOf("opus-en-es-tcbig-2026.10"), c.findByPair("en-es").map { it.id })
        assertEquals(listOf("opus-en-fr-1"), c.findByPair("en-fr").map { it.id })
        assertTrue(c.findByPair("de-es").isEmpty())
    }

    @Test fun `engine firefox es valido`() {
        val o = valido(); o.m0().put("engine", "firefox")
        assertEquals("firefox", parse(o).models[0].engine)
    }

    @Test fun `size en el limite de 2 GiB es valido`() {
        val o = valido(); o.f(0).put("size", 1L shl 31)
        assertEquals(1L shl 31, parse(o).models[0].files[0].size)
    }

    @Test fun `size 1 es valido`() {
        val o = valido(); o.f(0).put("size", 1)
        assertEquals(1L, parse(o).models[0].files[0].size)
    }

    @Test fun `32 archivos es valido`() {
        val o = valido()
        val files = JSONArray()
        repeat(32) { files.put(archivo("f$it.bin", 10, hexA)) }
        o.m0().put("files", files)
        assertEquals(32, parse(o).models[0].files.size)
    }

    @Test fun `200 modelos es valido`() {
        val o = valido()
        val ms = JSONArray()
        repeat(200) { ms.put(modelo(id = "m$it")) }
        o.put("models", ms)
        assertEquals(200, parse(o).models.size)
    }

    @Test fun `0 modelos es valido`() {
        val o = valido(); o.put("models", JSONArray())
        assertTrue(parse(o).models.isEmpty())
    }

    @Test fun `name con punto simple es valido`() {
        val o = valido(); o.f(0).put("name", "a.b-c_D.bin").put("url", "${prefijo}t/a.b-c_D.bin")
        assertEquals("a.b-c_D.bin", parse(o).models[0].files[0].name)
    }

    // ---------- Raíz ----------

    @Test fun `version distinta de 1`() { val o = valido(); o.put("version", 2); rechaza(o) }
    @Test fun `version como string`() { val o = valido(); o.put("version", "1"); rechaza(o) }
    @Test fun `version decimal`() {
        // Texto crudo: JSONObject.toString() escribiría 1.0 como 1.
        val s = valido().toString().replace("\"version\":1", "\"version\":1.0")
        assertTrue(s.contains("1.0"))
        rechazaBytes(s.toByteArray())
    }
    @Test fun `generated invalido`() { val o = valido(); o.put("generated", "ayer"); rechaza(o) }
    @Test fun `generated sin Z`() {
        val o = valido(); o.put("generated", "2026-10-03T00:00:00+00:00"); rechaza(o)
    }
    @Test fun `generated sin hora`() { val o = valido(); o.put("generated", "2026-10-03"); rechaza(o) }
    @Test fun `generated con espacios`() {
        val o = valido(); o.put("generated", " 2026-10-03T00:00:00Z"); rechaza(o)
    }
    @Test fun `generated numerico`() { val o = valido(); o.put("generated", 1); rechaza(o) }
    @Test fun `models no es arreglo`() { val o = valido(); o.put("models", JSONObject()); rechaza(o) }
    @Test fun `201 modelos`() {
        val o = valido()
        val ms = JSONArray()
        repeat(201) { ms.put(modelo(id = "m$it")) }
        o.put("models", ms)
        rechaza(o)
    }
    @Test fun `raiz es arreglo`() { rechazaBytes("[]".toByteArray()) }
    @Test fun `JSON no valido`() { rechazaBytes("{\"version\": 1,".toByteArray()) }
    @Test fun `bytes vacios`() { rechazaBytes(ByteArray(0)) }
    @Test fun `basura despues del JSON`() { rechazaBytes((valido().toString() + " x").toByteArray()) }
    @Test fun `JSON mayor de 1 MiB se rechaza antes de leerlo`() {
        // Bytes que ni siquiera son JSON: si se parsearan daría otro error; lo importante es el tamaño.
        val e = rechazaBytes(ByteArray(CatalogParser.MAX_BYTES + 1) { ' '.code.toByte() })
        assertTrue(e.message!!.contains("tamaño"), e.message)
    }
    @Test fun `JSON de exactamente 1 MiB con relleno es valido`() {
        val base = valido().toString().toByteArray()
        val relleno = ByteArray(CatalogParser.MAX_BYTES - base.size) { ' '.code.toByte() }
        CatalogParser.parse(base + relleno)
    }

    // ---------- Campos faltantes ----------

    @Test fun `falta version`() { val o = valido(); o.remove("version"); rechaza(o) }
    @Test fun `falta generated`() { val o = valido(); o.remove("generated"); rechaza(o) }
    @Test fun `falta models`() { val o = valido(); o.remove("models"); rechaza(o) }
    @Test fun `faltan campos del modelo`() {
        for (k in listOf("id", "pair", "engine", "modelVersion", "license", "attribution", "files")) {
            val o = valido(); o.m0().remove(k)
            val e = rechaza(o)
            assertTrue(e.message!!.contains(k), "mensaje debe nombrar $k: ${e.message}")
        }
    }
    @Test fun `faltan campos del archivo`() {
        for (k in listOf("name", "size", "sha256", "url")) {
            val o = valido(); o.f(1).remove(k)
            val e = rechaza(o)
            assertTrue(e.message!!.contains(k), "mensaje debe nombrar $k: ${e.message}")
        }
    }
    @Test fun `campo null cuenta como faltante`() {
        val o = valido(); o.m0().put("license", JSONObject.NULL); rechaza(o)
    }

    // ---------- Modelo ----------

    @Test fun `modelo que no es objeto`() {
        val o = valido(); o.put("models", JSONArray().put("x")); rechaza(o)
    }
    @Test fun `id con mayusculas`() { val o = valido(); o.m0().put("id", "Opus-en-es"); rechaza(o) }
    @Test fun `id vacio`() { val o = valido(); o.m0().put("id", ""); rechaza(o) }
    @Test fun `id de 65 caracteres`() { val o = valido(); o.m0().put("id", "a".repeat(65)); rechaza(o) }
    @Test fun `id de 64 caracteres es valido`() {
        val o = valido(); o.m0().put("id", "a".repeat(64)); parse(o)
    }
    @Test fun `id con barra`() { val o = valido(); o.m0().put("id", "a/b"); rechaza(o) }
    @Test fun `id punto`() { val o = valido(); o.m0().put("id", "."); rechaza(o) }
    @Test fun `id dos puntos`() { val o = valido(); o.m0().put("id", ".."); rechaza(o) }
    @Test fun `id que empieza por guion`() { val o = valido(); o.m0().put("id", "-x"); rechaza(o) }
    @Test fun `id que empieza por punto`() { val o = valido(); o.m0().put("id", ".x"); rechaza(o) }
    @Test fun `id con dos puntos en medio`() { val o = valido(); o.m0().put("id", "a..b"); rechaza(o) }
    @Test fun `id repetido`() {
        val o = valido(); o.getJSONArray("models").put(modelo(pair = "en-fr")); rechaza(o)
    }
    @Test fun `pair con ruta`() { val o = valido(); o.m0().put("pair", "../x"); rechaza(o) }
    @Test fun `pair con mayusculas`() { val o = valido(); o.m0().put("pair", "EN-es"); rechaza(o) }
    @Test fun `pair de un solo idioma`() { val o = valido(); o.m0().put("pair", "en"); rechaza(o) }
    @Test fun `pair con codigo de 4 letras`() { val o = valido(); o.m0().put("pair", "engl-es"); rechaza(o) }
    @Test fun `engine desconocido`() { val o = valido(); o.m0().put("engine", "x"); rechaza(o) }
    @Test fun `engine con mayusculas`() { val o = valido(); o.m0().put("engine", "Opus"); rechaza(o) }
    @Test fun `modelVersion vacio`() { val o = valido(); o.m0().put("modelVersion", ""); rechaza(o) }
    @Test fun `license numerica`() { val o = valido(); o.m0().put("license", 4); rechaza(o) }
    @Test fun `attribution demasiado larga`() {
        val o = valido(); o.m0().put("attribution", "a".repeat(CatalogParser.MAX_TEXT + 1)); rechaza(o)
    }
    @Test fun `files no es arreglo`() { val o = valido(); o.m0().put("files", "model.bin"); rechaza(o) }
    @Test fun `0 archivos`() { val o = valido(); o.m0().put("files", JSONArray()); rechaza(o) }
    @Test fun `33 archivos`() {
        val o = valido()
        val files = JSONArray()
        repeat(33) { files.put(archivo("f$it.bin", 10, hexA)) }
        o.m0().put("files", files)
        rechaza(o)
    }

    // ---------- Archivo ----------

    @Test fun `archivo que no es objeto`() {
        val o = valido(); o.m0().put("files", JSONArray().put(1)); rechaza(o)
    }
    @Test fun `name con barra`() { val o = valido(); o.f(0).put("name", "a/model.bin"); rechaza(o) }
    @Test fun `name con barra invertida`() { val o = valido(); o.f(0).put("name", "a\\model.bin"); rechaza(o) }
    @Test fun `name igual a dos puntos`() { val o = valido(); o.f(0).put("name", ".."); rechaza(o) }
    @Test fun `name con dos puntos en medio`() {
        val o = valido(); o.f(0).put("name", "a..b").put("url", "${prefijo}t/a..b"); rechaza(o)
    }
    @Test fun `name vacio`() { val o = valido(); o.f(0).put("name", ""); rechaza(o) }
    @Test fun `name de 129 caracteres`() { val o = valido(); o.f(0).put("name", "a".repeat(129)); rechaza(o) }
    @Test fun `name repetido`() { val o = valido(); o.f(1).put("name", "model.bin"); rechaza(o) }
    @Test fun `mismo name en modelos distintos es valido`() {
        val o = valido(); o.getJSONArray("models").put(modelo(id = "otro", pair = "en-fr"))
        assertEquals(2, parse(o).models.size)
    }
    @Test fun `size 0`() { val o = valido(); o.f(0).put("size", 0); rechaza(o) }
    @Test fun `size negativo`() { val o = valido(); o.f(0).put("size", -5); rechaza(o) }
    @Test fun `size 2 a la 31 mas 1`() { val o = valido(); o.f(0).put("size", (1L shl 31) + 1); rechaza(o) }
    @Test fun `size como string`() { val o = valido(); o.f(0).put("size", "10"); rechaza(o) }
    @Test fun `size decimal`() { val o = valido(); o.f(0).put("size", 1.5); rechaza(o) }
    @Test fun `size fuera del rango de Long`() {
        val s = valido().toString().replace("235883903", "99999999999999999999999")
        rechazaBytes(s.toByteArray())
    }
    @Test fun `size con exponente`() {
        val s = valido().toString().replace("235883903", "1e3")
        rechazaBytes(s.toByteArray())
    }
    @Test fun `sha256 con mayusculas`() { val o = valido(); o.f(0).put("sha256", "A".repeat(64)); rechaza(o) }
    @Test fun `sha256 de 63 caracteres`() { val o = valido(); o.f(0).put("sha256", "a".repeat(63)); rechaza(o) }
    @Test fun `sha256 de 65 caracteres`() { val o = valido(); o.f(0).put("sha256", "a".repeat(65)); rechaza(o) }
    @Test fun `sha256 no hexadecimal`() { val o = valido(); o.f(0).put("sha256", "g".repeat(64)); rechaza(o) }

    // ---------- URL ----------

    private fun conUrl(url: String): JSONObject = valido().also { it.f(0).put("url", url) }

    @Test fun `url http`() {
        rechaza(conUrl("http://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/t/model.bin"))
    }
    @Test fun `url otro host`() {
        rechaza(conUrl("https://evil.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/t/model.bin"))
    }
    @Test fun `url host con sufijo`() {
        rechaza(conUrl("https://github.com.evil.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/t/model.bin"))
    }
    @Test fun `url otro repo`() {
        rechaza(conUrl("https://github.com/otro/lector-bilingue-modelos/releases/download/t/model.bin"))
    }
    @Test fun `url con mayusculas en el esquema`() {
        rechaza(conUrl("HTTPS://github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/t/model.bin"))
    }
    @Test fun `url con usuario`() {
        rechaza(conUrl("https://x@github.com/DiegoBr4nd/lector-bilingue-modelos/releases/download/t/model.bin"))
    }
    @Test fun `url con dos puntos para subir`() { rechaza(conUrl("${prefijo}../../x/model.bin")) }
    @Test fun `url con punto codificado`() { rechaza(conUrl("${prefijo}%2e%2e/model.bin")) }
    @Test fun `url con punto codificado en mayusculas`() { rechaza(conUrl("${prefijo}%2E%2E/model.bin")) }
    @Test fun `url con barra invertida`() { rechaza(conUrl("${prefijo}t\\model.bin")) }
    @Test fun `url con consulta`() { rechaza(conUrl("${prefijo}t/model.bin?x=1")) }
    @Test fun `url con fragmento`() { rechaza(conUrl("${prefijo}t/model.bin#x")) }
    @Test fun `url con espacio`() { rechaza(conUrl("${prefijo}t /model.bin")) }
    @Test fun `url con etiqueta punto`() { rechaza(conUrl("${prefijo}./model.bin")) }
    @Test fun `url sin etiqueta`() { rechaza(conUrl("${prefijo}model.bin")) }
    @Test fun `url con directorio extra`() { rechaza(conUrl("${prefijo}t/sub/model.bin")) }
    @Test fun `url con etiqueta vacia`() { rechaza(conUrl("$prefijo/model.bin")) }
    @Test fun `url cuyo archivo no coincide con name`() { rechaza(conUrl("${prefijo}t/otro.bin")) }
    @Test fun `url como numero`() { val o = valido(); o.f(0).put("url", 5); rechaza(o) }

    // ---------- Mensajes ----------

    @Test fun `los mensajes nombran campo y posicion pero no el valor`() {
        val centinela = "ZZsecretoZZ"
        val casos = listOf<(JSONObject) -> Unit>(
            { it.m0().put("id", centinela) },
            { it.m0().put("pair", centinela) },
            { it.m0().put("engine", centinela) },
            { it.f(1).put("name", centinela + "/x") },
            { it.f(1).put("sha256", centinela) },
            { it.f(1).put("url", "${prefijo}t/$centinela") },
            { it.f(1).put("size", centinela) },
            { it.put("generated", centinela) },
            { it.put("version", centinela) },
        )
        for (mutar in casos) {
            val o = valido(); mutar(o)
            val e = rechaza(o)
            assertFalse(e.message!!.contains(centinela), "el mensaje filtra el valor: ${e.message}")
        }
        val o = valido(); o.f(1).put("sha256", centinela)
        val msg = rechaza(o).message!!
        assertTrue(msg.contains("sha256") && msg.contains("0") && msg.contains("1"), msg)
    }

    @Test fun `JSON roto no filtra contenido`() {
        val e = rechazaBytes("{\"ZZsecretoZZ\": ".toByteArray())
        assertFalse(e.message!!.contains("ZZsecretoZZ"), e.message)
    }
}
