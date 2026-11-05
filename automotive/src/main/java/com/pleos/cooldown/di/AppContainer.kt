package com.pleos.cooldown.di

import android.content.Context
import com.pleos.cooldown.data.hvac.FakeHvacController
import com.pleos.cooldown.data.hvac.HvacController
import com.pleos.cooldown.data.hvac.OutsideTemperatureSource
import com.pleos.cooldown.data.hvac.PleosHvacController
import com.pleos.cooldown.data.local.BaselineRepository
import com.pleos.cooldown.data.local.ComfortProfileRepository
import com.pleos.cooldown.data.local.CopilotDatabase
import com.pleos.cooldown.data.local.TripRepository
import com.pleos.cooldown.data.settings.SettingsStore
import com.pleos.cooldown.domain.report.TripWithInterventions
import com.pleos.cooldown.data.navi.PleosRouteSource
import com.pleos.cooldown.data.vehicle.FakeVehicleDataSource
import com.pleos.cooldown.data.vehicle.PleosVehicleDataSource
import com.pleos.cooldown.data.vehicle.ScenarioParser
import com.pleos.cooldown.data.vehicle.UxRestrictionSource
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
import kotlinx.coroutines.Dispatchers
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
    var vehicleDataSource: VehicleDataSource = PleosVehicleDataSource(appContext, clock)
        private set

    private var runnerCreated = false

    /**
     * 디버그 빌드 시연용: `assets/scenarios/<name>.csv`를 재생하는 가짜 차량 신호로 바꾼다 (명세 10절).
     * 공조·음성·내비는 실제 SDK를 그대로 쓴다. 러너가 만들어지기 전에만 바꿀 수 있다.
     */
    fun useScenario(name: String): Boolean {
        if (runnerCreated) return false
        val csv = runCatching {
            appContext.assets.open("scenarios/$name.csv").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false
        vehicleDataSource = FakeVehicleDataSource(
            ScenarioParser.parse(csv),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock,
        )
        return true
    }

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
    val tripRepository: TripRepository by lazy { TripRepository(database.tripDao()) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    private val outsideTemperature = OutsideTemperatureSource(appContext)

    /** 지금 계절 구간 (F2-19). 주행 시작과 홈·설정 화면이 같이 쓴다 */
    fun currentSeason(): SeasonBand = SeasonBand.of(outsideTemperature.readCelsius(), LocalDate.now().monthValue)

    val uxRestrictions: UxRestrictionSource by lazy { UxRestrictionSource(appContext) }

    /** F1·F3가 함께 쓰는 내비 경로 */
    val routeSource: PleosRouteSource by lazy { PleosRouteSource(appContext) }

    /** 모든 주행 상태를 바꾸는 단일 스레드 (CopilotRunner 규칙) */
    private val runnerScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }

    val copilotRunner: CopilotRunner by lazy {
        runnerCreated = true
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
                override suspend fun saveTrip(record: TripWithInterventions): Long = tripRepository.save(record)
                override suspend fun resetLearning() {
                    baselineRepository.reset()
                    comfortRepository.reset()
                }
            },
            scorer = StateScorer(hourOfDay = { ms -> Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour }),
            season = ::currentSeason,
            voiceOut = PleosVoiceOutput(appContext),
            voiceIn = PleosVoiceInput(appContext),
            routes = routeSource,
            settings = { settingsStore.settings.value },
        )
    }

    private companion object {
        const val USE_FAKE_HVAC = false
    }
}
