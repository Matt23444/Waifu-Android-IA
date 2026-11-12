package com.waifu.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt

private fun findFile(dir: File, vararg suffixes: String): File {
    for (s in suffixes) {
        val f = dir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(s) }
        if (f != null) return f
    }
    throw IllegalStateException("No encontré ${suffixes.joinToString(" / ")} en ${dir.name}")
}

/** Cerebro: llama.cpp con un modelo GGUF. */
class LlmEngine(context: Context, scope: CoroutineScope) {

    private val events = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val helper = LlamaHelper(context.contentResolver, scope, events)

    suspend fun load(model: File) = suspendCancellableCoroutine<Unit> { cont ->
        helper.load(path = Uri.fromFile(model).toString(), contextLength = 2048) { _ ->
            if (cont.isActive) cont.resume(Unit)
        }
    }

    /** Genera texto token a token; vuelve cuando el modelo termina. */
    suspend fun generate(prompt: String, onToken: (String) -> Unit) = coroutineScope {
        val finished = CompletableDeferred<Unit>()
        // UNDISPATCHED: nos suscribimos antes de pedir la respuesta para no perder tokens
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { e ->
                if (e is LlamaHelper.LLMEvent.Ongoing) {
                    onToken(e.word)
                } else {
                    val name = e::class.simpleName ?: ""
                    if (name == "Done" || name == "Error") finished.complete(Unit)
                }
            }
        }
        helper.predict(prompt)
        finished.await()
        collector.cancel()
    }
}

/** Oído: Whisper (multilingüe) con sherpa-onnx. */
class SttEngine(dir: File) {

    private val recognizer: OfflineRecognizer

    init {
        val encoder = findFile(dir, "encoder.int8.onnx", "encoder.onnx")
        val decoder = findFile(dir, "decoder.int8.onnx", "decoder.onnx")
        val tokens = findFile(dir, "tokens.txt")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = "es",
                    task = "transcribe",
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, 16000)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
        stream.release()
        return text.trim()
    }
}

/** Voz: Kokoro con sherpa-onnx, reproducida por frases mientras se genera. */
class TtsEngine(dir: File) {

    private val tts: OfflineTts

    init {
        val lexicons = dir.listFiles { f -> f.name.startsWith("lexicon") && f.name.endsWith(".txt") }
            ?.joinToString(",") { it.absolutePath } ?: ""
        val dict = File(dir, "dict").takeIf { it.exists() }?.absolutePath ?: ""
        val kokoro = OfflineTtsKokoroModelConfig(
            model = findFile(dir, "model.onnx").absolutePath,
            voices = findFile(dir, "voices.bin").absolutePath,
            tokens = findFile(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath,
            dictDir = dict,
            lexicon = lexicons,
            lang = "es",
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = kokoro,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(assetManager = null, config = config)
    }

    /** Habla el texto. onLevel recibe el volumen (0..~0.3) para mover la boca del avatar. */
    fun speak(text: String, speakerId: Int, onLevel: (Float) -> Unit) {
        if (text.isBlank()) return
        val sampleRate = tts.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        var totalFrames = 0
        tts.generateWithCallback(text = text, sid = speakerId, speed = 1.0f) { samples ->
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            totalFrames += samples.size
            var sum = 0f
            for (s in samples) sum += s * s
            onLevel(sqrt(sum / samples.size.coerceAtLeast(1)))
            1 // continuar
        }

        // Esperar a que termine de sonar lo que quedó en el búfer
        var waited = 0
        while (track.playbackHeadPosition < totalFrames && waited < 8000) {
            Thread.sleep(20)
            waited += 20
        }
        track.stop()
        track.release()
        onLevel(0f)
    }
}

/** Grabación del micrófono a 16 kHz, mono. */
class Recorder {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<FloatArray>()

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 16000),
        )
        chunks.clear()
        record = r
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(1600)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    chunks.add(FloatArray(n) { buf[it] / 32768f })
                }
            }
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join()
        record?.apply {
            stop()
            release()
        }
        record = null
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        chunks.clear()
        return out
    }
}
