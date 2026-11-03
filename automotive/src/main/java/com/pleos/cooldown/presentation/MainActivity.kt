package com.pleos.cooldown.presentation

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
import com.pleos.cooldown.data.vehicle.PleosVehicleDataSource
import com.pleos.cooldown.presentation.navigation.AppNavigation
import com.pleos.cooldown.presentation.theme.CooldownTheme

class MainActivity : ComponentActivity() {

    // 차속(CAR_SPEED)은 런타임 권한이라 허용된 뒤 데이터 소스를 다시 시작한다
    private val speedPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) (application as CooldownApplication).container.vehicleDataSource.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestSpeedPermissionIfNeeded()
        setContent {
            CooldownTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }

    private fun requestSpeedPermissionIfNeeded() {
        val permission = PleosVehicleDataSource.CAR_SPEED_PERMISSION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            speedPermissionRequest.launch(permission)
        }
    }
}
