package com.pleos.cooldown.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

    private fun requestPermissionsAndStart() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            CopilotService.start(this)
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf(
            PleosVehicleDataSource.CAR_SPEED_PERMISSION,
            OutsideTemperatureSource.PERMISSION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
