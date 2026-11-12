package com.waifu.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var store: ModelStore
    private lateinit var avatar: AvatarView
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var talkBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var progress: ProgressBar

    private var llm: LlmEngine? = null
    private var stt: SttEngine? = null
    private var tts: TtsEngine? = null
    private val recorder = Recorder()

    private val history = ArrayList<Pair<String, String>>() // (rol, texto)
    private var chatText = ""
    private var busy = false
    private var listening = false

    private val prefs by lazy { getSharedPreferences("waifu", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = ModelStore(this)
        buildUi()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        if (store.isReady()) {
            loadEngines()
        } else {
            status.text = "Faltan los modelos (~1.7 GB). Conéctate a Wi-Fi y pulsa Descargar."
            downloadBtn.visibility = android.view.View.VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1B2E.toInt())
        }

        avatar = AvatarView(this)
        root.addView(avatar, LinearLayout.LayoutParams(-1, 0, 5f))

        status = TextView(this).apply {
            setTextColor(0xFFBBB5D6.toInt())
            textSize = 13f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            text = "Iniciando…"
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))

        downloadBtn = Button(this).apply {
            text = "Descargar modelos"
            visibility = android.view.View.GONE
            setOnClickListener { startDownload() }
        }
        root.addView(downloadBtn)

        chat = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll = ScrollView(this).apply { addView(chat) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 3f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        input = EditText(this).apply {
            hint = "Escribe un mensaje…"
            setHintTextColor(0xFF8A84A8.toInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            isEnabled = false
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        sendBtn = Button(this).apply {
            text = "Enviar"
            isEnabled = false
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    input.setText("")
                    ask(t)
                }
            }
        }
        row.addView(sendBtn)

        talkBtn = Button(this).apply {
            text = "🎤"
            isEnabled = false
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> startListening()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopListening()
                }
                true
            }
        }
        row.addView(talkBtn)

        val settingsBtn = Button(this).apply {
            text = "⚙"
            setOnClickListener { showSettings() }
        }
        row.addView(settingsBtn)

        root.addView(row)
        setContentView(root)
    }

    private fun setControls(enabled: Boolean) {
        input.isEnabled = enabled
        sendBtn.isEnabled = enabled
        talkBtn.isEnabled = enabled
    }

    // ---------- Modelos ----------

    private fun startDownload() {
        downloadBtn.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.downloadMissing { label, pct ->
                        runOnUiThread {
                            progress.progress = pct
                            status.text = "Descargando $label: $pct %  (no cierres la app)"
                        }
                    }
                }
                progress.visibility = android.view.View.GONE
                downloadBtn.visibility = android.view.View.GONE
                loadEngines()
            } catch (e: Exception) {
                status.text = "Error al descargar: ${e.message}. Pulsa Descargar para reintentar."
                downloadBtn.isEnabled = true
            }
        }
    }

    private fun loadEngines() {
        status.text = "Cargando modelos… (puede tardar un minuto)"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val l = LlmEngine(this@MainActivity, engineScope)
                    l.load(store.llmFile)
                    llm = l
                    stt = SttEngine(store.whisperDir)
                    tts = TtsEngine(store.kokoroDir)
                }
                status.text = "Lista. Escribe o mantén pulsado 🎤 para hablar."
                setControls(true)
            } catch (e: Throwable) {
                status.text = "Error al cargar: ${e.message}"
            }
        }
    }

    // ---------- Voz de entrada ----------

    private fun startListening() {
        if (busy || listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Falta el permiso de micrófono."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        listening = true
        status.text = "Escuchando…"
        recorder.start()
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        val samples = recorder.stop()
        status.text = "Transcribiendo…"
        scope.launch {
            val text = try {
                withContext(Dispatchers.Default) { stt?.transcribe(samples) ?: "" }
            } catch (e: Throwable) {
                status.text = "Error al transcribir: ${e.message}"
                return@launch
            }
            if (text.isBlank()) {
                status.text = "No te escuché bien, inténtalo otra vez."
            } else {
                ask(text)
            }
        }
    }

    // ---------- Conversación ----------

    private fun buildPrompt(userText: String): String {
        val persona = prefs.getString("persona", DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(persona).append("\n").append(RULES).append("<|im_end|>\n")
        for ((role, text) in history.takeLast(6)) {
            sb.append("<|im_start|>").append(role).append("\n").append(text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(userText).append("<|im_end|>\n")
        // Bloque <think> vacío: desactiva el modo de razonamiento de Qwen3
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return sb.toString()
    }

    private fun emotionFrom(tag: String) = when (tag.lowercase()) {
        "feliz" -> AvatarView.Emotion.HAPPY
        "triste" -> AvatarView.Emotion.SAD
        "sorpresa" -> AvatarView.Emotion.SURPRISED
        else -> AvatarView.Emotion.NEUTRAL
    }

    private fun cleanForSpeech(s: String) =
        s.replace(Regex("[*_#`~]|\\p{So}|\\p{Cs}"), "").trim()

    private fun ask(userText: String) {
        if (busy) return
        val engine = llm ?: return
        val voice = tts ?: return
        busy = true
        setControls(false)
        status.text = "Pensando…"

        val prompt = buildPrompt(userText)
        history.add("user" to userText)
        chatText += "Tú: $userText\n"
        val chatBase = chatText + "Mika: "
        chat.text = chatBase
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }

        val speakQueue = Channel<String>(Channel.UNLIMITED)
        val speakerId = prefs.getInt("speaker", 0)
        val speaker = scope.launch(Dispatchers.Default) {
            for (sentence in speakQueue) {
                val clean = cleanForSpeech(sentence)
                if (clean.isNotEmpty()) {
                    voice.speak(clean, speakerId) { level -> avatar.post { avatar.setMouth(level) } }
                }
            }
        }

        scope.launch {
            val reply = StringBuilder()
            var tagHandled = false
            try {
                withContext(Dispatchers.IO) {
                    engine.generate(prompt) { token ->
                        reply.append(token)
                        var visible = reply.toString()
                        if (!tagHandled) {
                            val m = Regex("^\\s*\\[(\\w+)]\\s*").find(visible)
                            if (m != null) {
                                val emo = emotionFrom(m.groupValues[1])
                                runOnUiThread { avatar.emotion = emo }
                                reply.delete(0, m.range.last + 1)
                                tagHandled = true
                                visible = reply.toString()
                            } else if (visible.trimStart().isNotEmpty() && !visible.trimStart().startsWith("[")) {
                                tagHandled = true
                            } else if (visible.length > 14) {
                                tagHandled = true
                            } else {
                                return@generate // esperando a ver si llega la etiqueta
                            }
                        }
                        runOnUiThread {
                            chat.text = chatBase + visible
                            scroll.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                        // Enviar a la voz frase por frase
                        spokenUpTo(visible, speakQueue)
                    }
                }
                // Lo que quedó sin decir
                val rest = reply.toString().let { if (spokenChars <= it.length) it.substring(spokenChars) else "" }
                if (rest.isNotBlank()) speakQueue.trySend(rest)
            } catch (e: Throwable) {
                status.text = "Error: ${e.message}"
            } finally {
                speakQueue.close()
                speaker.join()
                spokenChars = 0
                avatar.setMouth(0f)
                avatar.emotion = AvatarView.Emotion.NEUTRAL
                val finalText = reply.toString().trim()
                if (finalText.isNotEmpty()) history.add("assistant" to finalText)
                chatText = chatBase + finalText + "\n"
                chat.text = chatText
                busy = false
                setControls(true)
                if (status.text.startsWith("Pensando")) {
                    status.text = "Lista. Escribe o mantén pulsado 🎤 para hablar."
                }
            }
        }
    }

    /** Cuántos caracteres de la respuesta ya se enviaron a la voz. */
    private var spokenChars = 0

    /**
     * Envía a la cola de voz las frases completas que haya en [visible] y devuelve
     * hasta dónde se envió. Una frase termina en . ! ? … o salto de línea.
     */
    private fun spokenUpTo(visible: String, queue: Channel<String>): Int {
        while (true) {
            if (spokenChars >= visible.length) break
            val tail = visible.substring(spokenChars)
            val idx = tail.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '…' || it == '\n' }
            if (idx < 0) break
            if (idx < 10 && tail[idx] != '\n') {
                // Frase demasiado corta (por ejemplo "Hola."): espera a tener más texto
                val next = tail.substring(idx + 1).indexOfFirst {
                    it == '.' || it == '!' || it == '?' || it == '…' || it == '\n'
                }
                if (next < 0) break
                queue.trySend(tail.substring(0, idx + 1 + next + 1))
                spokenChars += idx + 1 + next + 1
            } else {
                queue.trySend(tail.substring(0, idx + 1))
                spokenChars += idx + 1
            }
        }
        return spokenChars
    }

    // ---------- Ajustes ----------

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val speakerInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "ID de voz (0-52)"
            setText(prefs.getInt("speaker", 0).toString())
        }
        val personaInput = EditText(this).apply {
            hint = "Personalidad del personaje"
            setText(prefs.getString("persona", DEFAULT_PERSONA))
            minLines = 3
        }
        box.addView(TextView(this).apply { text = "Voz de Kokoro (prueba distintos números):" })
        box.addView(speakerInput)
        box.addView(TextView(this).apply { text = "Personalidad:" })
        box.addView(personaInput)

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit()
                    .putInt("speaker", speakerInput.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 0)
                    .putString("persona", personaInput.text.toString().ifBlank { DEFAULT_PERSONA })
                    .apply()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        engineScope.cancel()
    }

    companion object {
        const val DEFAULT_PERSONA =
            "Eres Mika, una compañera virtual y VTuber amable, curiosa y juguetona. " +
                "Hablas con el usuario como una amiga cercana."
        const val RULES =
            "Responde siempre en español neutro, sin modismos regionales. " +
                "Usa 1 o 2 frases cortas. " +
                "Empieza cada respuesta con una etiqueta de emoción entre corchetes: " +
                "[feliz], [triste], [sorpresa] o [neutral]."
    }
}
