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

    /** 디버그 빌드에서만: `adb shell am start ... --es scenario f2_aggressive` */
    private fun useScenarioIfRequested() {
        val name = intent.getStringExtra(EXTRA_SCENARIO) ?: return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val used = (application as CooldownApplication).container.useScenario(name)
        Log.i(TAG, "scenario $name used=$used")
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
        val RUNTIME_PERMISSIONS = listOf(
            PleosVehicleDataSource.CAR_SPEED_PERMISSION,
            OutsideTemperatureSource.PERMISSION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
