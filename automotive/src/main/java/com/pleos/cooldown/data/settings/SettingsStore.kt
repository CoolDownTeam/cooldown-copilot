package com.pleos.cooldown.data.settings

import android.content.Context
import com.pleos.cooldown.domain.settings.CopilotSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 설정 저장소. 값이 몇 개뿐이라 SharedPreferences에 두고 StateFlow로 내보낸다 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("copilot_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<CopilotSettings> = _settings.asStateFlow()

    fun update(transform: (CopilotSettings) -> CopilotSettings) {
        _settings.update(transform)
        val s = _settings.value
        prefs.edit()
            .putBoolean(KEY_COOLDOWN, s.cooldownEnabled)
            .putBoolean(KEY_FATIGUE, s.fatigueEnabled)
            .putBoolean(KEY_BRIEFING, s.briefingEnabled)
            .putBoolean(KEY_VOICE, s.voiceEnabled)
            .putBoolean(KEY_ONBOARDING, s.onboardingDone)
            .apply()
    }

    private fun read(): CopilotSettings {
        val d = CopilotSettings.DEFAULT
        return CopilotSettings(
            cooldownEnabled = prefs.getBoolean(KEY_COOLDOWN, d.cooldownEnabled),
            fatigueEnabled = prefs.getBoolean(KEY_FATIGUE, d.fatigueEnabled),
            briefingEnabled = prefs.getBoolean(KEY_BRIEFING, d.briefingEnabled),
            voiceEnabled = prefs.getBoolean(KEY_VOICE, d.voiceEnabled),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING, d.onboardingDone),
        )
    }

    private companion object {
        const val KEY_COOLDOWN = "cooldown"
        const val KEY_FATIGUE = "fatigue"
        const val KEY_BRIEFING = "briefing"
        const val KEY_VOICE = "voice"
        const val KEY_ONBOARDING = "onboarding_done"
    }
}
