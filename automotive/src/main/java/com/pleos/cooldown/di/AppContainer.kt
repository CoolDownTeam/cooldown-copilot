package com.pleos.cooldown.di

import android.content.Context
import com.pleos.cooldown.data.hvac.FakeHvacController
import com.pleos.cooldown.data.hvac.HvacController
import com.pleos.cooldown.data.hvac.OutsideTemperatureSource
import com.pleos.cooldown.data.hvac.PleosHvacController
import com.pleos.cooldown.data.local.BaselineRepository
import com.pleos.cooldown.data.local.ComfortProfileRepository
import com.pleos.cooldown.data.local.CopilotDatabase
import com.pleos.cooldown.data.navi.PleosRestAreaSource
import com.pleos.cooldown.data.vehicle.PleosVehicleDataSource
import com.pleos.cooldown.data.vehicle.VehicleDataSource
import com.pleos.cooldown.data.voice.PleosVoiceInput
import com.pleos.cooldown.data.voice.PleosVoiceOutput
import com.pleos.cooldown.domain.cooldown.ComfortProfile
import com.pleos.cooldown.domain.cooldown.SeasonBand
import com.pleos.cooldown.domain.engine.StateScorer
import com.pleos.cooldown.domain.model.Baseline
import com.pleos.cooldown.service.CopilotRunner
import com.pleos.cooldown.service.CopilotStorage
import com.pleos.cooldown.util.Clock
import com.pleos.cooldown.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * 수동 DI 컨테이너. 실제/가짜 데이터 소스를 바꿔 끼우는 지점이다.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val clock: Clock = SystemClock
    val vehicleDataSource: VehicleDataSource = PleosVehicleDataSource(appContext, clock)

    /**
     * `CONTROL_CAR_CLIMATE` 승인 전 시연은 [FakeHvacController]로 바꾼다 (명세 4.8 시연 방법).
     */
    val hvacController: HvacController by lazy {
        if (USE_FAKE_HVAC) FakeHvacController() else PleosHvacController(appContext, clock)
    }

    // DB는 처음 쓸 때 연다 (앱 시작을 느리게 하지 않게)
    private val database: CopilotDatabase by lazy { CopilotDatabase.create(appContext) }
    val baselineRepository: BaselineRepository by lazy { BaselineRepository(database.baselineDao()) }
    val comfortRepository: ComfortProfileRepository by lazy { ComfortProfileRepository(database.comfortProfileDao()) }

    private val outsideTemperature = OutsideTemperatureSource(appContext)

    /** 모든 주행 상태를 바꾸는 단일 스레드 (CopilotRunner 규칙) */
    private val runnerScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }

    val copilotRunner: CopilotRunner by lazy {
        CopilotRunner(
            scope = runnerScope,
            clock = clock,
            vehicle = vehicleDataSource,
            hvac = hvacController,
            storage = object : CopilotStorage {
                override suspend fun loadBaseline(): Baseline = baselineRepository.load()
                override suspend fun saveBaseline(baseline: Baseline) = baselineRepository.save(baseline)
                override suspend fun loadComfort(): ComfortProfile = comfortRepository.load()
                override suspend fun saveComfort(profile: ComfortProfile) = comfortRepository.save(profile)
            },
            scorer = StateScorer(hourOfDay = { ms -> Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour }),
            season = { SeasonBand.of(outsideTemperature.readCelsius(), LocalDate.now().monthValue) },
            voiceOut = PleosVoiceOutput(appContext),
            voiceIn = PleosVoiceInput(appContext),
            restAreas = PleosRestAreaSource(appContext),
        )
    }

    private companion object {
        const val USE_FAKE_HVAC = false
    }
}
