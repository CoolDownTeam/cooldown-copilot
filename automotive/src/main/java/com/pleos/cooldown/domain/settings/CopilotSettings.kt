package com.pleos.cooldown.domain.settings

/** S1 설정 (명세 8절, 화면 명세 7절). 주행 시작 때 읽어서 그 주행 동안 쓴다 */
data class CopilotSettings(
    /** F2 쿨다운 캐빈 */
    val cooldownEnabled: Boolean = true,
    /** F3 피로 케어 (환기 예방 포함) */
    val fatigueEnabled: Boolean = true,
    /** F1 코스 브리핑 */
    val briefingEnabled: Boolean = true,
    /** 음성 안내. 끄면 공조만 조절하고 말하지 않는다 */
    val voiceEnabled: Boolean = true,
    /** SC-01 온보딩을 마쳤는지 */
    val onboardingDone: Boolean = false,
) {
    /**
     * 주행에 실제로 적용할 설정. 첫 실행 동의(온보딩)를 마치기 전에는 운전 습관만 배우고,
     * 공조 개입·음성 안내는 하지 않는다 (S1-01, 투명성: 동의 없이 차를 바꾸지 않는다)
     */
    val active: CopilotSettings
        get() = if (onboardingDone) this else copy(
            cooldownEnabled = false,
            fatigueEnabled = false,
            briefingEnabled = false,
            voiceEnabled = false,
        )

    companion object {
        val DEFAULT = CopilotSettings()
    }
}
