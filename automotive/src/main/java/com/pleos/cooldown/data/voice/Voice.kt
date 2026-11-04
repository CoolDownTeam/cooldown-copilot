package com.pleos.cooldown.data.voice

import android.util.Log

/** 음성 안내. 주행 중에는 한 번에 한 문장만 말한다 (SAFE-02) */
interface VoiceOutput {
    suspend fun speak(text: String)

    /** 말하던 것을 멈춘다 (F1-01: 브리핑 중 출발) */
    fun stop() = Unit
}

/** 짧은 음성 응답 듣기 (F2-11, F3-03). 못 알아들었거나 시간이 지나면 null */
interface VoiceInput {
    suspend fun listenOnce(timeoutMs: Long): String?
}

/** TTS·STT 연결 전, 또는 음성 안내를 끈 경우에 쓴다. 로그만 남긴다 */
object SilentVoice : VoiceOutput, VoiceInput {
    private const val TAG = "SilentVoice"

    override suspend fun speak(text: String) {
        Log.i(TAG, "speak: $text")
    }

    override suspend fun listenOnce(timeoutMs: Long): String? = null
}
