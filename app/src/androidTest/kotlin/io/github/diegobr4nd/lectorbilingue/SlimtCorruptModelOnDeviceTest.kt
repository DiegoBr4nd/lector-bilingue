package io.github.diegobr4nd.lectorbilingue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.diegobr4nd.lectorbilingue.engine.firefox.SlimtNativeBridge
import io.github.diegobr4nd.lectorbilingue.models.Models
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Archivos de modelo cortados o dañados (parche 0004 de slimt): la carga debe lanzar una
 * excepción con mensaje fijo, nunca cerrar el proceso (SIGSEGV/SIGBUS).
 *
 * Si la app se cierra durante estas pruebas, el parche no está funcionando.
 */
@RunWith(AndroidJUnit4::class)
class SlimtCorruptModelOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before fun crearRaiz() {
        root = File(context.cacheDir, "slimt-corrupt-test").also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    @After fun limpiar() {
        root.deleteRecursively()
    }

    private val goodJson =
        """{"model": "m.bin", "vocabulary": "v.spm", "shortlist": "s.bin", "encoder_layers": 6, "decoder_layers": 4, "heads": 8}"""

    /** slimt.json de la línea base: 1 capa de codificador, 1 de decodificador, 1 cabeza. */
    private fun baseJson(encoder: Int = 1) =
        """{"model": "m.bin", "vocabulary": "v.spm", "shortlist": "s.bin", "encoder_layers": $encoder, "decoder_layers": 1, "heads": 1}"""

    /** Carpeta con slimt.json válido, vocabulario mínimo válido y lista corta (de relleno por defecto). */
    private fun modelDir(
        name: String,
        model: ByteArray,
        vocab: ByteArray = TINY_SPM,
        json: String = goodJson,
        shortlist: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): File {
        val dir = File(root, name).also { it.mkdirs() }
        File(dir, "slimt.json").writeText(json)
        File(dir, "m.bin").writeBytes(model)
        File(dir, "v.spm").writeBytes(vocab)
        File(dir, "s.bin").writeBytes(shortlist)
        return dir
    }

    /** Carpeta con la línea base: modelo, vocabulario y lista corta pequeños y válidos. */
    private fun baseDir(
        name: String,
        model: ByteArray = buildModel(baseItems()),
        shortlist: ByteArray = BASE_LEX,
        json: String = baseJson(),
    ) = modelDir(name, model, json = json, shortlist = shortlist)

    private fun assertRejected(dir: File, case: String) {
        val handle = try {
            SlimtNativeBridge.load(dir.path, 1)
        } catch (e: IllegalArgumentException) {
            checkMessage(e, case)
            return
        } catch (e: IllegalStateException) {
            checkMessage(e, case)
            return
        }
        SlimtNativeBridge.release(handle)
        fail("debía rechazar el modelo dañado: $case")
    }

    private fun checkMessage(e: Exception, case: String) {
        val msg = e.message.orEmpty()
        assertTrue(msg.isNotEmpty(), "mensaje vacío: $case")
        assertFalse(msg.contains('/') || msg.contains('\\'), "el mensaje filtra una ruta: $case")
        for (leak in listOf("m.bin", "v.spm", "s.bin", "Wemb", "truncated", "corrupt")) {
            assertFalse(msg.contains(leak), "el mensaje filtra contenido o el texto de slimt: $case")
        }
    }

    // ----- Línea base: modelo pequeño y válido construido aquí -----
    // Prueba de control: si carga, el vocabulario mínimo (TINY_SPM) funciona en el fork de
    // SentencePiece, y los casos dañados de abajo fallan en el lector del modelo o de la lista
    // corta, no en el vocabulario (que se carga antes y daría el mismo mensaje fijo).

    @Test fun lineaBaseCarga() {
        val handle = SlimtNativeBridge.load(baseDir("base").path, 1)
        assertTrue(handle != 0L, "la línea base debía cargar")
        SlimtNativeBridge.release(handle)
    }

    @Test fun lineaBaseSinCadaParametro() {
        val items = baseItems()
        for (i in items.indices) {
            val missing = items.filterIndexed { j, _ -> j != i }
            assertRejected(baseDir("sin$i", buildModel(missing)), "falta ${items[i].name}")
        }
    }

    @Test fun lineaBaseConMasCapasQueElModelo() =
        assertRejected(baseDir("capas", json = baseJson(encoder = 2)), "2 capas en slimt.json, 1 en el modelo")

    @Test fun lineaBaseCortada() {
        val full = buildModel(baseItems())
        val dir = baseDir("cortada")
        val model = File(dir, "m.bin")
        val lengths = (0 until full.size step 61) + listOf(full.size - 1)
        for (len in lengths) {
            model.writeBytes(full.copyOf(len))
            assertRejected(dir, "línea base cortada a $len de ${full.size} bytes")
        }
    }

    @Test fun lineaBaseConUnTensorDanado() {
        fun mutated(case: String, change: (T) -> T) =
            assertRejected(baseDir(case, buildModel(baseItems().map(change))), case)
        // Un sesgo float32 [1, 8] necesita 32 bytes y su bloque solo trae 16.
        mutated("datoscortos") { if (it.name == "decoder_ff_logit_out_b") it.copy(dataLength = 16) else it }
        // Matriz int8 de una dimensión.
        mutated("ig8unadim") { if (it.name == "encoder_l1_self_Wq") it.copy(dims = intArrayOf(64)) else it }
        // Embedding que no es múltiplo de 8.
        mutated("wemb9") { if (it.name == "Wemb") it.copy(dims = intArrayOf(3, 3)) else it }
        // Dimensión negativa.
        mutated("negativa") { if (it.name == "encoder_l1_self_bq") it.copy(dims = intArrayOf(1, -8)) else it }
        // Forma enorme ((2^31-1) x (2^31-1) int8) con un bloque de 256 bytes.
        mutated("enorme") {
            if (it.name == "decoder_l1_rnn_W") it.copy(dims = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE)) else it
        }
    }

    @Test fun lineaBaseConListaCortaDanada() {
        val cases = listOf(
            "0 bytes" to ByteArray(0),
            "10 bytes" to ByteArray(10),
            "magia incorrecta" to ByteArray(48),
            // 2^61 * 8 = 2^64 desborda a 0: sin control, 48 + 0 + 0 == 48 pasaba.
            "tabla que desborda" to lex(1L shl 61, 0),
            "tabla vacía" to lex(0, 0),
            "desplazamiento fuera" to lex(6, 1, longArrayOf(0, 1, 1, 1, 1, 5), intArrayOf(3)),
            "palabra fuera del vocabulario" to lex(6, 1, longArrayOf(0, 1, 1, 1, 1, 1), intArrayOf(5)),
            "cortada" to BASE_LEX.copyOf(BASE_LEX.size - 1),
        )
        val dir = baseDir("listabase")
        for ((case, bytes) in cases) {
            File(dir, "s.bin").writeBytes(bytes)
            assertRejected(dir, "lista corta: $case")
        }
    }

    // ----- (a) archivo de 0 bytes -----

    @Test fun modeloDeCeroBytes() = assertRejected(modelDir("cero", ByteArray(0)), "0 bytes")

    // ----- (b) cabeceras que prometen más de lo que hay -----

    @Test fun muchasCabecerasEnArchivoCorto() =
        assertRejected(modelDir("cabeceras", le(1L, 1000L) + ByteArray(64)), "1000 cabeceras")

    @Test fun numeroDeCabecerasQueDesborda() =
        // 2^59 * 32 bytes = 2^64: desborda; sin control daría 0 y pasaría.
        assertRejected(modelDir("desborda", le(1L, 1L shl 59) + ByteArray(64)), "2^59 cabeceras")

    @Test fun nombreMasLargoQueElArchivo() = assertRejected(
        modelDir("nombre", le(1L, 1L) + header(nameLength = Long.MAX_VALUE) + ByteArray(32)),
        "nombre enorme",
    )

    @Test fun nombreDeLargoCero() = assertRejected(
        modelDir("nombre0", le(1L, 1L) + header(nameLength = 0) + ByteArray(32)),
        "nombre de largo 0",
    )

    @Test fun formaQueDesborda() = assertRejected(
        // 2^62 enteros * 4 bytes desborda.
        modelDir("forma", le(1L, 1L) + header(shapeLength = 1L shl 62) + "x\u0000".toByteArray() + ByteArray(64)),
        "forma enorme",
    )

    @Test fun rellenoMasGrandeQueElArchivo() =
        assertRejected(modelDir("relleno", tinyModel(offset = -1L)), "relleno 2^64-1")

    @Test fun datosMasGrandesQueElArchivo() =
        assertRejected(modelDir("datos", tinyModel(dataLength = 1L shl 40)), "data_length enorme")

    @Test fun formaPideMasQueLosDatos() =
        // Forma 1000x1000 float (4 MB) con solo 256 bytes de datos.
        assertRejected(modelDir("formagrande", tinyModel(rows = 1000, cols = 1000)), "forma > datos")

    @Test fun dimensionNegativa() =
        assertRejected(modelDir("negativa", tinyModel(rows = -1, cols = 4)), "dimensión negativa")

    @Test fun datosSinAlinear() =
        assertRejected(modelDir("alineacion", tinyModel(misalign = true)), "datos sin alinear a 256")

    @Test fun modeloBienFormadoSinParametros() =
        // Estructura correcta pero sin los tensores que espera el modelo: error al cargar,
        // no un tensor vacío que se cae al traducir.
        assertRejected(modelDir("sinparametros", tinyModel()), "faltan parámetros")

    // ----- (c) archivos cortados y basura -----

    @Test fun cadaPrefijoDeUnModeloPequeno() {
        val full = tinyModel()
        val dir = modelDir("prefijos", ByteArray(0))
        val model = File(dir, "m.bin")
        for (len in 0 until full.size) {
            model.writeBytes(full.copyOf(len))
            assertRejected(dir, "prefijo de $len bytes")
        }
    }

    @Test fun basuraConVersionCorrecta() {
        val dir = modelDir("basura", ByteArray(0))
        val model = File(dir, "m.bin")
        for (seed in 1..20) {
            val random = Random(seed)
            val size = 1024 + random.nextInt(4096)
            // La versión correcta (1) al principio, para pasar del primer control.
            model.writeBytes(le(1L) + random.nextBytes(size))
            assertRejected(dir, "basura semilla $seed")
        }
    }

    @Test fun basuraSinVersion() {
        val random = Random(99)
        assertRejected(modelDir("basura2", random.nextBytes(3000)), "basura sin versión")
    }

    @Test fun vocabularioBasura() =
        assertRejected(modelDir("vocab", tinyModel(), vocab = Random(7).nextBytes(2000)), "vocabulario basura")

    @Test fun vocabularioDeCeroBytes() =
        assertRejected(modelDir("vocab0", tinyModel(), vocab = ByteArray(0)), "vocabulario de 0 bytes")

    // ----- lista corta dañada (necesita el modelo real; sin él, se salta) -----

    @Test fun listaCortaDanada() = runBlocking<Unit> {
        Models.recover(context)
        val installed = Models.installedDir(context, "firefox", "en-es")
        assumeTrue("modelo Firefox en-es no instalado", installed != null)
        val cfg = JSONObject(File(installed, "slimt.json").readText())
        val dir = File(root, "lista").also { it.mkdirs() }
        File(installed, cfg.getString("model")).copyTo(File(dir, "m.bin"))
        File(installed, cfg.getString("vocabulary")).copyTo(File(dir, "v.spm"))
        cfg.put("model", "m.bin").put("vocabulary", "v.spm").put("shortlist", "s.bin")
        File(dir, "slimt.json").writeText(cfg.toString())
        val shortlist = File(dir, "s.bin")
        val cases = listOf(
            "10 bytes" to ByteArray(10),
            "magia incorrecta" to ByteArray(48),
            // 2^61 * 8 = 2^64 desborda a 0: sin control, 48 + 0 + 0 == 48 pasaba.
            "tabla que desborda" to lex(1L shl 61, 0),
            "tabla vacía" to lex(0, 0),
            "desplazamiento fuera" to lex(2, 1, longArrayOf(0, 5), intArrayOf(3)),
            "palabra fuera del vocabulario" to lex(2, 1, longArrayOf(0, 1), intArrayOf(1_000_000)),
        )
        for ((case, bytes) in cases) {
            shortlist.writeBytes(bytes)
            assertRejected(dir, "lista corta: $case")
        }
        // Lista corta válida pero con una sola palabra en la tabla: carga y traduce sin
        // leer fuera de la tabla (generate ignora las palabras sin entrada).
        shortlist.writeBytes(lex(2, 1, longArrayOf(0, 1), intArrayOf(3)))
        val handle = SlimtNativeBridge.load(dir.path, 1)
        try {
            val out = SlimtNativeBridge.translate(handle, arrayOf("The old library was quiet."))
            assertTrue(out.size == 1, "debía devolver una traducción")
        } finally {
            SlimtNativeBridge.release(handle)
        }
    }

    private companion object {
        /** Vocabulario SentencePiece mínimo y válido (5 piezas: <unk>, <s>, </s>, ▁, a). */
        val TINY_SPM: ByteArray = hex(
            "0a0e0a053c756e6b3e150000000018020a0c0a033c733e150000000018030a0d0a043c2f733e15" +
                "0000000018030a0c0a03e2968115000080bf18010a0a0a016115000000c01801",
        )

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        fun le(vararg values: Long): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buf.putLong(it) }
            return buf.array()
        }

        fun ints(vararg values: Int): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buf.putInt(it) }
            return buf.array()
        }

        const val FLOAT32 = 0x0404L
        const val INTGEMM8 = 0x4101L

        /** Lista corta binaria de Marian: cabecera de 6 enteros, desplazamientos y palabras. */
        fun lex(w2o: Long, size: Long, offsets: LongArray = LongArray(0), words: IntArray = IntArray(0)): ByteArray {
            val magic = -0x0EE5B72AFECBE80BL // 0xF11A48D5013417F5
            val buf = ByteBuffer.allocate(48 + offsets.size * 8 + words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(magic).putLong(0).putLong(50).putLong(50).putLong(w2o).putLong(size)
            offsets.forEach { buf.putLong(it) }
            words.forEach { buf.putInt(it) }
            return buf.array()
        }

        /** Lista corta válida para TINY_SPM (5 piezas): tabla de 6 entradas, una palabra. */
        val BASE_LEX: ByteArray = lex(6, 1, longArrayOf(0, 1, 1, 1, 1, 1), intArrayOf(3))

        /** Un tensor del modelo: nombre, tipo, forma y tamaño de su bloque de datos. */
        class T(val name: String, val type: Long, val dims: IntArray, val dataLength: Long = 256) {
            fun copy(dims: IntArray = this.dims, dataLength: Long = this.dataLength) = T(name, type, dims, dataLength)
        }

        /**
         * Todos los parámetros que slimt espera con 1 capa de codificador, 1 de decodificador
         * y FFN de profundidad 2 (mismos nombres que el modelo de Mozilla), en tamaño 8.
         */
        fun baseItems(): List<T> {
            val items = mutableListOf<T>()
            fun matrix(name: String) {
                items += T(name, INTGEMM8, intArrayOf(8, 8))
                items += T(name + "_QuantMultA", FLOAT32, intArrayOf(1, 1))
            }
            fun vector(name: String) {
                items += T(name, FLOAT32, intArrayOf(1, 8))
            }
            fun attention(prefix: String) {
                for (s in listOf("q", "k", "v", "o")) {
                    matrix("${prefix}W$s")
                    vector("${prefix}b$s")
                }
                vector("${prefix}Wo_ln_bias")
                vector("${prefix}Wo_ln_scale")
            }
            fun ffn(prefix: String) {
                for (i in 1..2) {
                    matrix("${prefix}_ffn_W$i")
                    vector("${prefix}_ffn_b$i")
                }
                vector("${prefix}_ffn_ffn_ln_bias")
                vector("${prefix}_ffn_ffn_ln_scale")
            }
            items += T("Wemb", INTGEMM8, intArrayOf(8, 8))
            items += T("none_QuantMultA", FLOAT32, intArrayOf(1, 1))
            vector("decoder_ff_logit_out_b")
            attention("encoder_l1_self_")
            ffn("encoder_l1")
            attention("decoder_l1_context_")
            ffn("decoder_l1")
            matrix("decoder_l1_rnn_W")
            matrix("decoder_l1_rnn_Wf")
            vector("decoder_l1_rnn_bf")
            vector("decoder_l1_rnn_ffn_ln_bias")
            vector("decoder_l1_rnn_ffn_ln_scale")
            return items
        }

        /**
         * Arma un modelo en el formato binario de Marian: versión, número de cabeceras,
         * cabeceras, nombres, formas, relleno hasta 256 bytes y bloques de datos.
         * Las matrices int8 llevan el multiplicador 1.0 justo después de la matriz.
         */
        fun buildModel(items: List<T>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(le(1L, items.size.toLong()))
            for (t in items) {
                out.write(le(t.name.toByteArray().size + 1L, t.type, t.dims.size.toLong(), t.dataLength))
            }
            for (t in items) out.write(t.name.toByteArray() + 0.toByte())
            for (t in items) out.write(ints(*t.dims))
            val pad = (256 - (out.size() + 8) % 256) % 256
            out.write(le(pad.toLong()))
            out.write(ByteArray(pad))
            for (t in items) {
                val block = ByteArray(t.dataLength.toInt())
                val elements = t.dims.fold(1L) { acc, d -> acc * d }
                if (t.type == INTGEMM8 && elements >= 0 && elements + 4 <= t.dataLength) {
                    ByteBuffer.wrap(block, elements.toInt(), 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.0f)
                }
                out.write(block)
            }
            return out.toByteArray()
        }

        /** Cabecera de un tensor: name_length, type, shape_length, data_length. */
        fun header(nameLength: Long = 2, type: Long = FLOAT32, shapeLength: Long = 2, dataLength: Long = 256) =
            le(nameLength, type, shapeLength, dataLength)

        /**
         * Modelo slimt de un tensor float32 "x" con la estructura del formato: versión,
         * cabeceras, nombre, forma, relleno hasta 256 bytes y datos. Los parámetros permiten
         * romper una sola cosa a la vez.
         */
        fun tinyModel(
            rows: Int = 8,
            cols: Int = 8,
            dataLength: Long = 256,
            offset: Long? = null,
            misalign: Boolean = false,
        ): ByteArray {
            val head = le(1L, 1L) + header(dataLength = dataLength) + "x\u0000".toByteArray() + ints(rows, cols)
            val beforePad = head.size + 8
            var pad = ((256 - beforePad % 256) % 256).toLong()
            if (misalign) pad += 4
            val padding = offset ?: pad
            return head + le(padding) + ByteArray(pad.toInt()) + ByteArray(256)
        }
    }
}
