package com.pleos.cooldown.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pleos.cooldown.presentation.driving.DrivingScreen
import com.pleos.cooldown.presentation.history.HistoryScreen
import com.pleos.cooldown.presentation.home.HomeScreen
import com.pleos.cooldown.presentation.home.HomeUi
import com.pleos.cooldown.presentation.onboarding.OnboardingScreen
import com.pleos.cooldown.presentation.report.ReportScreen
import com.pleos.cooldown.presentation.settings.SettingsScreen
import com.pleos.cooldown.presentation.theme.CopilotTheme

/**
 * 화면 전환 (화면 명세 1절). 주행 중이면 어느 화면이든 SC-03으로 덮고, 정차하면 원래 화면으로 돌아간다 (UI-02, S1-04).
 */
@Composable
fun AppRoot(vm: CopilotViewModel, onRequestPermissions: () -> Unit) {
    val driving by vm.driving.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(CopilotTheme.colors.background)) {
        if (driving) {
            val status by vm.drivingStatus.collectAsStateWithLifecycle()
            DrivingScreen(status)
            return@Box
        }

        BackHandler(enabled = screen != Screen.Home && screen != Screen.Onboarding) { vm.back() }

        when (val s = screen) {
            Screen.Onboarding -> OnboardingScreen(
                onConsent = { allow ->
                    vm.answerClimateConsent(allow)
                    if (allow) onRequestPermissions()
                },
                onFinish = vm::finishOnboarding,
            )
            Screen.Home -> {
                val trips by vm.trips.collectAsStateWithLifecycle()
                val learning by vm.learning.collectAsStateWithLifecycle()
                val briefing by vm.briefing.collectAsStateWithLifecycle()
                val comfort by vm.comfort.collectAsStateWithLifecycle()
                val season by vm.season.collectAsStateWithLifecycle()
                val climate by vm.climateAvailable.collectAsStateWithLifecycle()
                HomeScreen(
                    ui = HomeUi(
                        settings = settings,
                        learning = learning,
                        lastTrip = trips.firstOrNull(),
                        learnedSinceMs = trips.lastOrNull { !it.trip.learning }?.trip?.startMs,
                        nowMs = System.currentTimeMillis(),
                        briefing = briefing,
                        comfort = comfort,
                        season = season,
                        climateAvailable = climate,
                    ),
                    onOpenTrip = { vm.open(Screen.Report(it, Screen.Home)) },
                    onHistory = { vm.open(Screen.History) },
                    onSettings = { vm.open(Screen.Settings) },
                    onListenBriefing = vm::replayBriefing,
                )
            }
            is Screen.Report -> {
                val report by vm.report.collectAsStateWithLifecycle()
                ReportScreen(report?.takeIf { it.tripId == s.tripId }, onClose = vm::back)
            }
            Screen.History -> {
                val trips by vm.trips.collectAsStateWithLifecycle()
                HistoryScreen(
                    trips = trips,
                    onOpen = { vm.open(Screen.Report(it, Screen.History)) },
                    onClearAll = vm::clearHistory,
                    onBack = vm::back,
                )
            }
            Screen.Settings -> {
                val comfort by vm.comfort.collectAsStateWithLifecycle()
                val season by vm.season.collectAsStateWithLifecycle()
                SettingsScreen(
                    settings = settings,
                    comfort = comfort,
                    season = season,
                    onChange = vm::updateSettings,
                    onRelearn = vm::resetLearning,
                    onBack = vm::back,
                )
            }
        }
    }
}
