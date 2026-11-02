package ai.backfm.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** Offline Korean voices only: passenger text is never sent to network TTS. */
fun interface AudioRenderer { suspend fun render(text: String, destination: File): File }
class SpeechRenderer(private val context: Context) : AudioRenderer {
    private var engine: TextToSpeech? = null
    override suspend fun render(text: String, destination: File): File = withContext(Dispatchers.Main) {
        try {
            withTimeout(20_000) { suspendCancellableCoroutine<Unit> { cont ->
                engine = TextToSpeech(context) { status ->
                    if (cont.isActive) {
                        if (status == TextToSpeech.SUCCESS) cont.resume(Unit)
                        else cont.resumeWithException(IllegalStateException("음성 엔진을 시작할 수 없습니다."))
                    }
                }
                cont.invokeOnCancellation { engine?.shutdown() }
            } }
            val tts = engine ?: error("음성 엔진 없음")
            val voice = tts.voices?.firstOrNull { it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired }
                ?: error("오프라인 한국어 음성이 없습니다. 기기 설정에서 한국어 음성 데이터를 설치해 주세요.")
            check(tts.setVoice(voice) == TextToSpeech.SUCCESS)
            tts.setSpeechRate(1.05f)
            tts.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            destination.parentFile?.mkdirs()
            val parts = mutableListOf<File>()
            try {
                text.chunked(minOf(3000, TextToSpeech.getMaxSpeechInputLength() - 1)).forEachIndexed { index, chunk ->
                    val part = File(destination.parentFile, "${destination.name}.$index.part.wav")
                    parts += part
                    withTimeout(120_000) { suspendCancellableCoroutine<Unit> { cont ->
                        val token = UUID.randomUUID().toString()
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) { if (utteranceId == token && cont.isActive) cont.resume(Unit) }
                            @Deprecated("Android legacy callback")
                            override fun onError(utteranceId: String?) { if (utteranceId == token && cont.isActive) cont.resumeWithException(IllegalStateException("음성 합성에 실패했습니다.")) }
                        })
                        cont.invokeOnCancellation { tts.stop() }
                        if (tts.synthesizeToFile(chunk, Bundle(), part, token) != TextToSpeech.SUCCESS && cont.isActive)
                            cont.resumeWithException(IllegalStateException("음성 합성을 요청하지 못했습니다."))
                    } }
                }
                withContext(Dispatchers.IO) { WaveJoiner.join(parts, destination) }
            } finally { parts.forEach { it.delete() } }
            destination
        } finally { engine?.shutdown(); engine = null }
    }
}

internal object WaveJoiner {
    fun join(parts: List<File>, destination: File) {
        var format: ByteArray? = null
        val data = java.io.ByteArrayOutputStream()
        parts.forEach { file ->
            val bytes = file.readBytes()
            require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "지원하지 않는 음성 파일입니다." }
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
                val content = bytes.copyOfRange(offset + 8, offset + 8 + length)
                if (id == "fmt ") { if (format == null) format = content else require(format!!.contentEquals(content)) }
                if (id == "data") data.write(content)
                offset += 8 + length + (length % 2)
            }
        }
        val fmt = format ?: error("음성 형식을 확인할 수 없습니다.")
        require(data.size() > 0)
        destination.outputStream().use { out ->
            fun ascii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
            fun int(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
            ascii("RIFF"); int(4 + 8 + fmt.size + (fmt.size % 2) + 8 + data.size() + (data.size() % 2)); ascii("WAVE")
            ascii("fmt "); int(fmt.size); out.write(fmt); if (fmt.size % 2 != 0) out.write(0)
            ascii("data"); int(data.size()); data.writeTo(out); if (data.size() % 2 != 0) out.write(0)
        }
    }
}
