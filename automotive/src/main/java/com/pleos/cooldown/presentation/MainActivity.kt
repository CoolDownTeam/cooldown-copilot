package com.pleos.cooldown.presentation

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pleos.cooldown.CooldownApplication
import com.pleos.cooldown.data.hvac.OutsideTemperatureSource
import com.pleos.cooldown.data.vehicle.PleosVehicleDataSource
import com.pleos.cooldown.presentation.navigation.AppNavigation
import com.pleos.cooldown.presentation.theme.CooldownTheme
import com.pleos.cooldown.service.CopilotService

class MainActivity : ComponentActivity() {

    // 런타임 권한을 받은 뒤 서비스를 시작한다. 거부돼도 시작하고, 없는 신호는 데이터 소스가 사용 불가로 표시한다
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            (application as CooldownApplication).container.vehicleDataSource.start()
            CopilotService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useScenarioIfRequested()
        enableEdgeToEdge()
        requestPermissionsAndStart()
        setContent {
            CooldownTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }

    /**
     * 디버그 빌드에서만:
     * - `--es scenario f2_aggressive`: 녹화한 차량 신호 재생
     * - `--es demo_route "위도,경도,이름"`: 내비 경로 요청 (F1 확인용). `--ez demo_simulate true`면 모의 주행 시작
     */
    private fun useScenarioIfRequested() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val container = (application as CooldownApplication).container
        intent.getStringExtra(EXTRA_SCENARIO)?.let { name ->
            Log.i(TAG, "scenario $name used=${container.useScenario(name)}")
        }
        intent.getStringExtra(EXTRA_DEMO_ROUTE)?.split(",")?.takeIf { it.size >= 3 }?.let { parts ->
            val lat = parts[0].toDoubleOrNull() ?: return@let
            val lon = parts[1].toDoubleOrNull() ?: return@let
            val routes = container.routeSource
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
