package com.beyondzero.onspot

import com.beyondzero.onspot.presentation.intro.IntroOverlay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.beyondzero.onspot.data.settings.SettingsManager
import com.beyondzero.onspot.presentation.home.HomeViewModel
import com.beyondzero.onspot.presentation.home.MainScreen
import com.beyondzero.onspot.presentation.navigation.Screen
import com.beyondzero.onspot.presentation.onboarding.OnboardingScreen
import com.beyondzero.onspot.service.FlowshareForegroundService
import com.beyondzero.onspot.ui.theme.OnSpotTheme

class MainActivity : ComponentActivity() {
  private val homeViewModel: HomeViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    val splash = installSplashScreen()
    // Fade the system splash out quickly; the Compose intro takes over from there.
    splash.setOnExitAnimationListener { provider ->
      provider.view.animate().alpha(0f).setDuration(180L).withEndAction { provider.remove() }.start()
    }
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val settingsManager = SettingsManager(this)
    val startDestination = if (settingsManager.hasCompletedOnboarding) Screen.Home.route else Screen.Onboarding.route

    // Keeps discovery/transfers (and a progress notification) running after this Activity is
    // closed or swiped away.
    try {
      ContextCompat.startForegroundService(this, Intent(this, FlowshareForegroundService::class.java))
    } catch (_: Exception) {
      // Android 12+ can refuse to start a foreground service in some background states;
      // the app still works while it is open.
    }

    // "Share → OnSpot" from another app (only once, not again after a rotation).
    if (savedInstanceState == null) homeViewModel.handleShareIntent(intent)

    setContent {
      OnSpotTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
         // The opening animation plays on a fresh start, not after a rotation.
         var intro by rememberSaveable { mutableStateOf(savedInstanceState == null) }
         var revealed by rememberSaveable { mutableStateOf(savedInstanceState != null) }
         Box(Modifier.fillMaxSize()) {
          val navController = rememberNavController()
          NavHost(navController = navController, startDestination = startDestination) {
            composable(Screen.Onboarding.route) {
              OnboardingScreen(
                settingsManager = settingsManager,
                onComplete = {
                  // Push the name/avatar picked during onboarding out to the network.
                  homeViewModel.setIdentity(settingsManager.deviceName, settingsManager.avatarIndex)
                  navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                  }
                }
              )
            }
            composable(Screen.Home.route) {
              MainScreen(viewModel = homeViewModel, entered = revealed)
            }
          }
          if (intro) IntroOverlay(onReveal = { revealed = true }, onFinished = { intro = false })
         }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    homeViewModel.handleShareIntent(intent)
  }
}
