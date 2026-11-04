package com.pleos.cooldown.data.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener

/**
 * Pleos TextToSpeech 3.3.1. ON_DEVICE 모드라 네트워크와 앱 등록이 필요 없다.
 * 권한: `pleos.car.permission.TTS_SERVICE`
 */
class PleosVoiceOutput(context: Context) : VoiceOutput {

    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val ready = CompletableDeferred<Boolean>()
    private var finished: CompletableDeferred<Unit>? = null

    private val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finish()
        override fun onStop() = finish()
        override fun onError(errorType: TtsErrorType) {
            Log.w(TAG, "TTS error $errorType")
            finish()
        }
    }

    init {
        tts.initialize(object : TtsInitializeListener {
            override fun onSuccess() {
                tts.setLocale(Locale.KOREA)
                tts.addEventListener(listener)
                ready.complete(true)
            }
            override fun onFailure(errorType: TtsErrorType) {
                Log.w(TAG, "TTS init failed $errorType")
                ready.complete(false)
            }
        })
    }

    /**
     * 다 말할 때까지(또는 오류·예상 길이 초과까지) 기다린다.
     * 에뮬레이터에서 onDone이 오지 않는 경우가 있어서, 문장 길이로 재생 시간을 어림해 그만큼만 기다린다.
     */
    override suspend fun speak(text: String) {
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } != true) return
        val done = CompletableDeferred<Unit>()
        finished = done
        try {
            tts.speak(text, true)
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed", e)
            return
        }
        withTimeoutOrNull(estimatedDurationMs(text)) { done.await() }
    }

    fun release() {
        tts.removeEventListener(listener)
        tts.release()
    }

    private fun finish() {
        finished?.complete(Unit)
    }

    /** 한국어 TTS는 초당 6~7음절 안팎이다. 여유 1초를 더하고, 최대 6초까지만 기다린다 */
    private fun estimatedDurationMs(text: String): Long =
        (text.count { !it.isWhitespace() } * MS_PER_CHAR + MARGIN_MS).coerceAtMost(MAX_SPEAK_WAIT_MS)

    private companion object {
        const val TAG = "PleosTts"
        const val READY_TIMEOUT_MS = 3_000L
        const val MS_PER_CHAR = 180L
        const val MARGIN_MS = 1_000L
        const val MAX_SPEAK_WAIT_MS = 6_000L
    }
}

/**
 * Pleos SpeechToText 3.3.1, ON_DEVICE 모드. 한 번 듣고 최종 결과를 돌려준다.
 * 권한: `pleos.car.permission.STT_SERVICE`
 */
class PleosVoiceInput(context: Context) : VoiceInput {

    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val ready = CompletableDeferred<Boolean>()
    private var result: CompletableDeferred<String?>? = null

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed) result?.complete(stt)
        }
        override fun onEndedRecognition() {
            result?.complete(null)
        }
        override fun onError(errorType: SttErrorType) {
            Log.w(TAG, "STT error $errorType")
            result?.complete(null)
        }
    }

    init {
        stt.initialize(object : SttInitializeListener {
            override fun onSuccess() {
                stt.setLocale(Locale.KOREA)
                stt.addListener(listener)
                ready.complete(true)
            }
            override fun onFailure(errorType: SttErrorType) {
                Log.w(TAG, "STT init failed $errorType")
                ready.complete(false)
            }
        })
    }

    override suspend fun listenOnce(timeoutMs: Long): String? {
        if (withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } != true) return null
        val pending = CompletableDeferred<String?>()
        result = pending
        try {
            stt.request()
        } catch (e: Exception) {
            Log.w(TAG, "STT request failed", e)
            return null
        }
        val text = withTimeoutOrNull(timeoutMs) { pending.await() }
        runCatching { stt.stop() }
        return text
    }

    fun release() {
        stt.removeListener(listener)
        stt.release()
    }

    private companion object {
        const val TAG = "PleosStt"
        const val READY_TIMEOUT_MS = 3_000L
    }
}
