package ai.cjym.agentclaw.ui.avatar

import ai.guiji.duix.sdk.client.DUIX
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class SofiaSpeechController(
    private val context: Context,
    private val duixProvider: () -> DUIX?,
    private val onSentence: (IntRange?) -> Unit,
    private val onSpeaking: (Boolean) -> Unit
) {
    private var tts: TextToSpeech? = null
    private val playbackGeneration = AtomicLong(0)

    suspend fun speak(text: String, lipLeadMs: Int = 0) {
        val generation = playbackGeneration.incrementAndGet()
        // Recreate the engine for every response so changes made in Android's
        // system TTS settings (engine/voice/rate) are picked up immediately.
        tts?.stop()
        tts?.shutdown()
        tts = null
        val engine = ensureTts()
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return
        onSpeaking(true)
        try {
            for ((index, sentence) in sentences.withIndex()) {
                if (playbackGeneration.get() != generation) break
                onSentence(sentence.second)
                val wav = File(context.cacheDir, "sofia_tts_${UUID.randomUUID()}.wav")
                try {
                    synthesize(engine, sentence.first, wav, "sofia-$index-${System.nanoTime()}")
                    val pcm = withContext(Dispatchers.IO) { readAndResampleWav(wav) }
                    playPcm(pcm, generation, lipLeadMs)
                } finally {
                    wav.delete()
                }
            }
        } catch (_: CancellationException) {
            stop()
            throw CancellationException()
        } finally {
            if (playbackGeneration.get() == generation) {
                onSentence(null)
                onSpeaking(false)
            }
        }
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
        tts?.stop()
        duixProvider()?.stopAudio()
        onSentence(null)
        onSpeaking(false)
    }

    fun release() { stop(); tts?.shutdown(); tts = null }

    private suspend fun ensureTts(): TextToSpeech = tts ?: suspendCancellableCoroutine { continuation ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val maleMarkers = listOf(
                    "male", "man", "boy", "男", "yunxi", "yunyang", "yunjian",
                    "xiaoming", "xiaofeng", "xiaogang", "kangkang", "cmn-cn-x-ccd",
                    "zh-cn-x-ccd", "zh_cn_male", "chinese_male", "温文尔雅", "晴朗",
                    "遒劲", "紧劲", "wenwenerya", "qinglang", "huawei_male"
                )
                val femaleMarkers = listOf(
                    "female", "woman", "girl", "女", "xiaoxiao", "xiaoyi", "xiaomeng",
                    "xiaoyan", "xiaobei"
                )
                val chineseVoices = created?.voices.orEmpty().filter { it.locale.language == "zh" }
                val maleVoice = chineseVoices
                    .filter { voice ->
                        val descriptor = (
                            voice.name + " " + voice.features.joinToString(" ") + " " +
                                voice.locale.displayName + " " + voice.toString()
                            ).lowercase()
                        maleMarkers.any(descriptor::contains) && femaleMarkers.none(descriptor::contains)
                    }
                    .maxByOrNull { voice ->
                        (if (!voice.isNetworkConnectionRequired) 100 else 0) + voice.quality
                    }
                if (maleVoice != null) {
                    created?.voice = maleVoice
                }
                Log.i(
                    "SofiaSpeech",
                    "systemEngine=${created?.defaultEngine}, selectedMale=${maleVoice?.name}, " +
                        "voice=${created?.voice?.name}, locale=${created?.voice?.locale}, " +
                        "availableVoices=${chineseVoices.joinToString { it.name }}"
                )
                tts = created
                continuation.resume(requireNotNull(created))
            } else continuation.cancel(IllegalStateException("系统语音初始化失败"))
        }
        continuation.invokeOnCancellation { created?.shutdown() }
    }

    private suspend fun synthesize(engine: TextToSpeech, text: String, file: File, id: String) = suspendCancellableCoroutine<Unit> { continuation ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { onError(utteranceId, TextToSpeech.ERROR) }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id && continuation.isActive) continuation.cancel(IllegalStateException("语音合成失败：$errorCode"))
            }
        })
        val result = engine.synthesizeToFile(text, Bundle(), file, id)
        if (result != TextToSpeech.SUCCESS && continuation.isActive) continuation.cancel(IllegalStateException("语音合成启动失败"))
        continuation.invokeOnCancellation { engine.stop() }
    }

    private suspend fun playPcm(pcm: ByteArray, generation: Long, lipLeadMs: Int) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty() || playbackGeneration.get() != generation) return@withContext
        val minBuffer = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3_200)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val duix = duixProvider()
        try {
            duix?.setVolume(0f)
            duix?.startPush()
            val lipLeadBytes = ((16_000 * 2L * lipLeadMs.coerceAtLeast(0)) / 1_000L)
                .toInt()
                .coerceAtMost(pcm.size)
                .let { it - (it and 1) }
            var duixOffset = 0
            while (duixOffset < lipLeadBytes && playbackGeneration.get() == generation) {
                val count = minOf(3_200, lipLeadBytes - duixOffset)
                duix?.pushPcm(pcm.copyOfRange(duixOffset, duixOffset + count))
                duixOffset += count
            }
            if (lipLeadMs > 0 && playbackGeneration.get() == generation) {
                delay(lipLeadMs.toLong())
            }
            track.play()
            var audioOffset = 0
            while (audioOffset < pcm.size && playbackGeneration.get() == generation) {
                val count = minOf(3_200, pcm.size - audioOffset)
                val slice = pcm.copyOfRange(audioOffset, audioOffset + count)
                track.write(slice, 0, slice.size, AudioTrack.WRITE_BLOCKING)
                if (duixOffset < pcm.size) {
                    val duixCount = minOf(count, pcm.size - duixOffset)
                    duix?.pushPcm(pcm.copyOfRange(duixOffset, duixOffset + duixCount))
                    duixOffset += duixCount
                }
                audioOffset += count
            }
            duix?.stopPush()
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun readAndResampleWav(file: File): ByteArray {
        val bytes = file.readBytes()
        if (bytes.size < 44) return byteArrayOf()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 1
        var sampleRate = 16_000
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = String(bytes, cursor, 4, Charsets.US_ASCII)
            val size = buffer.getInt(cursor + 4).coerceAtLeast(0)
            if (id == "fmt " && cursor + 16 < bytes.size) {
                channels = buffer.getShort(cursor + 10).toInt().coerceAtLeast(1)
                sampleRate = buffer.getInt(cursor + 12).coerceAtLeast(1)
                bits = buffer.getShort(cursor + 22).toInt()
            } else if (id == "data") {
                dataOffset = cursor + 8
                dataSize = minOf(size, bytes.size - dataOffset)
                break
            }
            cursor += 8 + size + (size and 1)
        }
        require(dataOffset >= 0 && bits == 16) { "系统语音未输出可用的 PCM WAV" }
        val frameCount = dataSize / 2 / channels
        val mono = ShortArray(frameCount) { frame ->
            var sum = 0
            repeat(channels) { channel -> sum += buffer.getShort(dataOffset + (frame * channels + channel) * 2).toInt() }
            (sum / channels).toShort()
        }
        val outputCount = (mono.size * 16_000.0 / sampleRate).roundToInt().coerceAtLeast(1)
        val output = ByteBuffer.allocate(outputCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(outputCount) { index ->
            val source = index * sampleRate.toDouble() / 16_000.0
            val left = source.toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = source - left
            output.putShort((mono[left] + (mono[right] - mono[left]) * fraction).roundToInt().toShort())
        }
        return output.array()
    }

    private fun splitSentences(source: String): List<Pair<String, IntRange>> {
        val text = source.trim()
        val matches = Regex("[^。！？!?；;\\n]+[。！？!?；;]?|\\n+").findAll(text)
        val result = mutableListOf<Pair<String, IntRange>>()
        matches.forEach { match ->
            val value = match.value.trim()
            if (value.isNotBlank()) {
                var start = match.range.first + match.value.indexOf(value)
                value.chunked(1_500).forEach { chunk -> result += chunk to (start until start + chunk.length); start += chunk.length }
            }
        }
        return result
    }
}
