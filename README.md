# Waifu IA para Android

Compañera virtual (VTuber 2D) que funciona **en el teléfono, sin internet** una vez descargados los modelos.

| Función | Modelo | Tamaño aprox. |
|---|---|---|
| Cerebro | Qwen3 1.7B (Q4_K_M, GGUF) con llama.cpp | ~1.1 GB |
| Voz | Kokoro multilingüe (sherpa-onnx) | ~330 MB |
| Oído | Whisper base multilingüe (sherpa-onnx) | ~150 MB |

Pensado para teléfonos de 4 GB de RAM. Idioma: español.

## Cómo obtener el APK

1. Sube cambios a `main` (o ve a **Actions → Compilar APK → Run workflow**).
2. Cuando termine (unos 10-15 minutos), descarga `app-debug.apk` desde **Releases** o desde el artefacto de la ejecución.
3. Instálalo en el teléfono (permite instalar apps de origen desconocido).

## Primer uso

1. Conecta el teléfono a Wi-Fi y abre la app.
2. Pulsa **Descargar modelos** (~1.7 GB) y no cierres la app hasta terminar.
3. Espera a que cargue (puede tardar un minuto) y escribe o mantén pulsado 🎤 para hablar.
4. En ⚙ puedes cambiar el ID de voz de Kokoro y la personalidad del personaje.

## Cómo está armado

- `app/src/main/java/com/waifu/android/`
  - `MainActivity.kt`: pantalla y flujo de conversación.
  - `Engines.kt`: cerebro (llama.cpp), oído (Whisper), voz (Kokoro) y micrófono.
  - `ModelStore.kt`: descarga de modelos al primer uso.
  - `AvatarView.kt`: avatar 2D con parpadeo, expresiones y boca animada por la voz.
- El workflow descarga las librerías nativas y la API Kotlin de sherpa-onnx en cada compilación (versión fijada en `SHERPA_VERSION`).

## Limitaciones de esta primera versión

- Avatar 2D dibujado con código; más adelante se puede cambiar por Live2D.
- El historial de conversación es corto (últimos 3 turnos) por la memoria.
- Primera compilación sin probar en un teléfono real: es normal que haya que corregir errores.
