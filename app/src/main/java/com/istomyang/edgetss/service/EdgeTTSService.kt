package com.istomyang.edgetss.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.istomyang.edgetss.data.LogRepository
import com.istomyang.edgetss.data.SpeakerRepository
import com.istomyang.edgetss.data.repositoryLog
import com.istomyang.edgetss.data.repositorySpeaker
import com.istomyang.edgetss.utils.Codec
import com.istomyang.tts_engine.TTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EdgeTTSService : TextToSpeechService() {
    companion object {
        private const val LOG_NAME = "EdgeTTSService"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var engine: TTS
    private lateinit var logRepository: LogRepository
    private lateinit var speakerRepository: SpeakerRepository

    private var prepared = false
    private var locale: String? = null
    private var voiceName: String? = null
    private var outputFormat: String? = null
    private var sampleRate = 24000

    private val synthesisMutex = Mutex()
    @Volatile
    private var activeSynthesis: SynthesisState? = null
    private var pendingAudio: PendingAudio? = null
    private var sequenceNumber = 0L

    override fun onCreate() {
        super.onCreate()

        val context = this.applicationContext
        logRepository = context.repositoryLog
        speakerRepository = context.repositorySpeaker

        engine = TTS()

        scope.launch {
            launch { collectConfig() }

            try {
                engine.run()
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                error("engine run error: $e")
            }
        }
    }

    override fun onDestroy() {
        runBlocking {
            engine.close()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        pendingAudio = null
        activeSynthesis?.stop()
    }

    private suspend fun collectConfig() {
        speakerRepository.getActiveFlow().collect { voice ->
            if (voice != null) {
                locale = voice.locale
                voiceName = voice.name
                outputFormat = voice.suggestedCodec
                prepared = true
                info("use speaker: $voiceName - $locale")
            }
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("", "", "")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null || !prepared) {
            callback?.error()
            return
        }

        val text = request.charSequenceText.toString()
        val pitch = request.pitch - 100
        val rate = request.speechRate - 100

        info("start synthesizing text: $text")

        runBlocking {
            synthesisMutex.withLock {
                val synthesis = SynthesisState(++sequenceNumber)
                activeSynthesis = synthesis
                try {
                    synthesizeToCallback(text, pitch, rate, callback, synthesis)
                } finally {
                    if (activeSynthesis === synthesis) {
                        activeSynthesis = null
                    }
                }
            }
        }
    }

    private suspend fun synthesizeToCallback(
        text: String,
        pitch: Int,
        rate: Int,
        callback: SynthesisCallback,
        synthesis: SynthesisState
    ) {
        val metadata = TTS.AudioMetaData(
            locale = locale!!,
            voiceName = voiceName!!,
            volume = "+0%",
            outputFormat = outputFormat!!,
            pitch = "${pitch}Hz",
            rate = "${rate}%",
        )

        coroutineScope {
            val audioToPlay = pendingAudio
            pendingAudio = null
            val currentAudio = async(Dispatchers.IO) {
                synthesizeToPcm(text, metadata, synthesis)
            }

            try {
                callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

                if (audioToPlay == null) {
                    sendInitialSilence(callback, synthesis)
                } else {
                    info("play buffered audio seq=${audioToPlay.sequence}")
                    sendAudio(callback, audioToPlay, synthesis)
                }

                val preparedAudio = currentAudio.await()
                if (!synthesis.stopped && preparedAudio.hasAudio) {
                    pendingAudio = preparedAudio
                    info("buffered synthesized audio seq=${preparedAudio.sequence}")
                }

                if (synthesis.stopped) {
                    pendingAudio = null
                    callback.error()
                } else {
                    callback.done()
                }
            } catch (e: Throwable) {
                currentAudio.cancel()
                pendingAudio = null
                callback.error()
                error("synthesize text error: $e")
            }
        }
    }

    private suspend fun synthesizeToPcm(
        text: String,
        metadata: TTS.AudioMetaData,
        synthesis: SynthesisState
    ): PendingAudio = coroutineScope {
        val compressedAudio = Channel<Codec.Frame>(8)
        val decoded = mutableListOf<ByteArray>()
        val decoder = async(Dispatchers.IO) {
            Codec(compressedAudio.consumeAsFlow(), applicationContext).run(coroutineContext).collect { frame ->
                if (!frame.endOfFrame && !synthesis.stopped) {
                    decoded.add(frame.data!!)
                }
            }
        }

        try {
            engine.input(text, metadata)

            while (true) {
                val frame = engine.receiveOutput()
                when {
                    frame.textCompleted -> break
                    frame.audioCompleted -> compressedAudio.send(Codec.Frame(null, endOfFrame = true))
                    frame.data != null -> compressedAudio.send(Codec.Frame(frame.data))
                }
            }

            compressedAudio.close()
            decoder.await()
            PendingAudio(synthesis.sequence, decoded)
        } catch (e: Throwable) {
            compressedAudio.close(e)
            decoder.cancel()
            throw e
        }
    }

    private fun sendAudio(callback: SynthesisCallback, audio: PendingAudio, synthesis: SynthesisState) {
        for (chunk in audio.chunks) {
            sendAudio(callback, chunk, synthesis)
            if (synthesis.stopped) {
                return
            }
        }
    }

    private fun sendInitialSilence(callback: SynthesisCallback, synthesis: SynthesisState) {
        val silenceDurationMs = 20
        val bytesPerSample = 2
        val size = sampleRate * silenceDurationMs / 1000 * bytesPerSample
        sendAudio(callback, ByteArray(size), synthesis)
    }

    private fun sendAudio(callback: SynthesisCallback, data: ByteArray, synthesis: SynthesisState) {
        var offset = 0
        val maxBufferSize = maxOf(1, callback.maxBufferSize)
        while (offset < data.size && !synthesis.stopped) {
            val length = minOf(maxBufferSize, data.size - offset)
            val status = callback.audioAvailable(data, offset, length)
            if (status != TextToSpeech.SUCCESS) {
                synthesis.stop()
                return
            }
            offset += length
        }
    }

    private class PendingAudio(
        val sequence: Long,
        val chunks: List<ByteArray>
    ) {
        val hasAudio: Boolean
            get() = chunks.any { it.isNotEmpty() }
    }

    private class SynthesisState {
        constructor(sequence: Long) {
            this.sequence = sequence
        }

        val sequence: Long

        @Volatile
        var stopped: Boolean = false
            private set

        fun stop() {
            stopped = true
        }
    }

    private fun debug(message: String) {
        Log.d(LOG_NAME, message)
        scope.launch {
            logRepository.debug(LOG_NAME, message)
        }
    }

    private fun info(message: String) {
        logRepository.info(LOG_NAME, message)
        Log.i(LOG_NAME, message)
    }

    private fun error(message: String) {
        logRepository.error(LOG_NAME, message)
        Log.e(LOG_NAME, message)
    }

    private val String.description: String
        get() {
            val size = this.length
            return if (size > 10) {
                "${this.substring(0, 10)}..."
            } else {
                this
            }
        }
}

