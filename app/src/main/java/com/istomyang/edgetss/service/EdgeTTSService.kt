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
                val synthesis = SynthesisState()
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

        val compressedAudio = Channel<Codec.Frame>(8)
        val decoder = scope.async {
            Codec(compressedAudio.consumeAsFlow(), applicationContext).run(coroutineContext).collect { frame ->
                if (!frame.endOfFrame && !synthesis.stopped) {
                    sendAudio(callback, frame.data!!, synthesis)
                }
            }
        }

        try {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
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

            if (synthesis.stopped) {
                callback.error()
            } else {
                callback.done()
            }
        } catch (e: Throwable) {
            compressedAudio.close(e)
            decoder.cancel()
            callback.error()
            error("synthesize text error: $e")
        }
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

    private class SynthesisState {
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

