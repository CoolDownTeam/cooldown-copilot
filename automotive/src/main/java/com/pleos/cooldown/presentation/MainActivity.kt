package com.pleos.cooldown.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.pleos.cooldown.BuildConfig
import com.pleos.cooldown.CooldownApplication
import com.pleos.cooldown.data.hvac.OutsideTemperatureSource
import com.pleos.cooldown.data.vehicle.PleosVehicleDataSource
import com.pleos.cooldown.presentation.theme.CooldownTheme
import com.pleos.cooldown.service.CopilotService

class MainActivity : ComponentActivity() {

    // 런타임 권한을 받은 뒤 서비스를 시작한다. 거부돼도 시작하고, 없는 신호는 데이터 소스가 사용 불가로 표시한다
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            container.vehicleDataSource.start()
            CopilotService.start(this)
        }

    private val container get() = (application as CooldownApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useScenarioIfRequested()
        enableEdgeToEdge()
        container.uxRestrictions.start()
        // S1-01: 첫 실행이면 권한은 온보딩 2단계 "허용"에서 묻는다. 서비스는 C0 학습을 위해 바로 시작한다
        if (container.settingsStore.settings.value.onboardingDone) requestPermissionsAndStart() else CopilotService.start(this)
        val vm = ViewModelProvider(this, CopilotViewModel.Factory(container))[CopilotViewModel::class.java]
        setContent {
            CooldownTheme {
                AppRoot(vm, onRequestPermissions = ::requestPermissionsAndStart)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this, CopilotViewModel.Factory(container))[CopilotViewModel::class.java].refreshSeason()
    }

    override fun onDestroy() {
        if (isFinishing) container.uxRestrictions.stop()
        super.onDestroy()
    }

    /**
     * 시연 재생을 연 빌드에서만 (`BuildConfig.DEMO_SCENARIOS`, 공모전 제출 빌드는 release 포함):
     * - `--es scenario f2_aggressive`: 녹화한 차량 신호 재생
     * - `--es demo_route "위도,경도,이름"`: 내비 경로 요청 (F1 확인용). `--ez demo_simulate true`면 모의 주행 시작
     */
    private fun useScenarioIfRequested() {
        if (!BuildConfig.DEMO_SCENARIOS) return
        intent.getStringExtra(EXTRA_SCENARIO)?.let { name ->
            Log.i(TAG, "scenario $name used=${container.useScenario(name)}")
        }
        intent.getStringExtra(EXTRA_DEMO_ROUTE)?.split(",")?.takeIf { it.size >= 3 }?.let { parts ->
            val lat = parts[0].toDoubleOrNull() ?: return@let
            val lon = parts[1].toDoubleOrNull() ?: return@let
            val routes = container.pleosRouteSource
            routes.start()
            routes.requestDemoRoute(lat, lon, parts[2])
            if (intent.getBooleanExtra(EXTRA_DEMO_SIMULATE, false)) {
                window.decorView.postDelayed({ routes.startDemoSimulation() }, DEMO_SIMULATE_DELAY_MS)
            }
            Log.i(TAG, "demo route requested ${parts[2]}")
        }
    }

    private fun requestPermissionsAndStart() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            CopilotService.start(this)
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_DEMO_ROUTE = "demo_route"
        const val EXTRA_DEMO_SIMULATE = "demo_simulate"
        const val DEMO_SIMULATE_DELAY_MS = 8_000L
        val RUNTIME_PERMISSIONS = listOf(
            PleosVehicleDataSource.CAR_SPEED_PERMISSION,
            OutsideTemperatureSource.PERMISSION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
