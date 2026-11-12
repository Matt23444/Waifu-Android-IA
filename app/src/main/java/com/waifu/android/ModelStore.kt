package com.waifu.android

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga y guarda los modelos en la carpeta privada de la app.
 * No se incluyen en el APK porque pesan más de 1 GB.
 */
class ModelStore(context: Context) {

    val root = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    val llmFile = File(root, "qwen3-1.7b-q4_k_m.gguf")
    val whisperDir = File(root, "whisper-base")
    val kokoroDir = File(root, "kokoro")

    private val okMarker = ".ok"

    fun isReady(): Boolean =
        llmFile.exists() &&
            File(whisperDir, okMarker).exists() &&
            File(kokoroDir, okMarker).exists()

    /** Descarga lo que falte. onProgress recibe (nombre, porcentaje 0-100). */
    fun downloadMissing(onProgress: (String, Int) -> Unit) {
        if (!llmFile.exists()) {
            download(LLM_URL, llmFile, "cerebro (Qwen3 1.7B)", onProgress)
        }
        if (!File(whisperDir, okMarker).exists()) {
            downloadAndExtract(WHISPER_URL, whisperDir, "oído (Whisper base)", onProgress)
        }
        if (!File(kokoroDir, okMarker).exists()) {
            downloadAndExtract(KOKORO_URL, kokoroDir, "voz (Kokoro)", onProgress)
        }
    }

    private fun downloadAndExtract(
        url: String,
        destDir: File,
        label: String,
        onProgress: (String, Int) -> Unit,
    ) {
        val archive = File(root, destDir.name + ".tar.bz2")
        download(url, archive, label, onProgress)
        onProgress("$label: descomprimiendo", 100)
        destDir.deleteRecursively()
        destDir.mkdirs()
        extractTarBz2(archive, destDir)
        File(destDir, okMarker).writeText("ok")
        archive.delete()
    }

    private fun download(url: String, out: File, label: String, onProgress: (String, Int) -> Unit) {
        val part = File(out.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IOException("HTTP ${conn.responseCode} al descargar $label")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(label, pct)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(out)) throw IOException("No se pudo guardar $label")
    }

    /** Descomprime quitando la carpeta raíz del archivo y evitando rutas peligrosas. */
    private fun extractTarBz2(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16)),
        ).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val target = File(destDir, relative)
                if (!target.canonicalPath.startsWith(destCanonical)) {
                    throw IOException("Ruta no válida en el archivo: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    companion object {
        const val LLM_URL =
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        const val WHISPER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
        const val KOKORO_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
    }
}
