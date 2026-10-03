package io.github.diegobr4nd.lectorbilingue.models

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Instala un modelo ya descargado (`modelsDir/.tmp/<id>/`) en `modelsDir/<pair>/`.
 *
 * 1. Re-verifica: la carpeta contiene exactamente los archivos del catálogo (sin extras, sin
 *    subcarpetas, sin enlaces), cada uno con su tamaño y SHA-256. Si no → [IntegrityException].
 * 2. Escribe `.installed.json` dentro de la carpeta temporal.
 * 3. Reemplazo: si ya hay un modelo en `<pair>/`, se renombra a `.old-<pair>-<nanoTime>`; luego la
 *    carpeta temporal se renombra (atómico) a `<pair>/`; al final se borra la vieja. Si el segundo
 *    renombrado falla, la vieja vuelve a `<pair>/` y se relanza el error: el modelo anterior sigue
 *    disponible y la descarga verificada queda en `.tmp/<id>/` para reintentar.
 *
 * Ventana conocida: entre los dos renombrados `<pair>/` no existe un instante; quien lea el modelo
 * debe tolerar "no instalado" momentáneamente. Si el proceso muere justo ahí, queda `.old-<pair>-*`
 * (lo puede recuperar o borrar quien gestione los modelos al arrancar).
 */
class ModelInstaller internal constructor(
    private val modelsDir: File,
    private val move: (from: Path, to: Path) -> Unit,
) {
    constructor(modelsDir: File) : this(modelsDir, ::atomicMove)

    /** Re-verifica tamaños y SHA-256 en [staging], escribe .installed.json y lo instala en modelsDir/<pair>/ de forma atómica. */
    fun install(model: CatalogModel, staging: File): InstalledModel {
        ModelFiles.checkNames(model)
        val tmpRoot = File(modelsDir, ModelFiles.TMP_DIR)
        val expectedStaging = ModelFiles.child(tmpRoot, model.id)
        require(staging.canonicalFile == expectedStaging.canonicalFile) { "carpeta temporal inesperada" }
        val stagingPath = expectedStaging.toPath()
        if (!Files.isDirectory(stagingPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IntegrityException("la descarga no está completa")
        }
        val pairDir = ModelFiles.child(modelsDir, model.pair)

        // Un .installed.json en staging solo puede venir de un intento anterior fallido: se reescribe.
        val installedJson = File(expectedStaging, ModelFiles.INSTALLED_JSON).toPath()
        ModelFiles.deleteTree(installedJson)
        verify(model, expectedStaging)

        val installed = InstalledModel(model.id, model.pair, model.engine, model.modelVersion, model.files.map { it.name })
        Files.write(installedJson, installed.toJson().toByteArray(Charsets.UTF_8))

        val pairPath = pairDir.toPath()
        var old: Path? = null
        if (ModelFiles.existsNoFollow(pairPath)) {
            val oldPath = ModelFiles.child(modelsDir, ".old-${model.pair}-${System.nanoTime()}").toPath()
            move(pairPath, oldPath)
            old = oldPath
        }
        try {
            move(stagingPath, pairPath)
        } catch (e: Throwable) {
            if (old != null) {
                try {
                    move(old, pairPath)
                } catch (rollback: Throwable) {
                    e.addSuppressed(rollback)
                }
            }
            throw e
        }
        if (old != null) {
            try {
                ModelFiles.deleteTree(old)
            } catch (e: IOException) {
                // El nuevo ya está instalado; la carpeta vieja se puede limpiar más tarde.
            }
        }
        // La carpeta .tmp se borra solo si quedó vacía (puede haber otras descargas en curso).
        tmpRoot.delete()
        return installed
    }

    private fun verify(model: CatalogModel, staging: File) {
        val expected = model.files.associateBy { it.name }
        val present = HashSet<String>()
        Files.newDirectoryStream(staging.toPath()).use { children ->
            for (c in children) {
                val name = c.fileName.toString()
                if (name !in expected || !ModelFiles.isRegularFileNoFollow(c)) {
                    throw IntegrityException("la descarga contiene elementos que no son del modelo")
                }
                present += name
            }
        }
        for (f in model.files) {
            if (f.name !in present) throw IntegrityException("falta el archivo ${f.name}")
            if (!ModelFiles.matches(ModelFiles.child(staging, f.name), f)) {
                throw IntegrityException("el archivo ${f.name} no coincide con el catálogo")
            }
        }
    }

    private companion object {
        fun atomicMove(from: Path, to: Path) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
