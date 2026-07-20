package com.openwave.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import com.openwave.music.features.settings.DisplaySettingsStore
import com.openwave.music.ui.navigation.OpenWaveNavHost
import com.openwave.music.ui.theme.OpenWaveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject lateinit var displaySettings: DisplaySettingsStore

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* media notification works without; optional for Android 13+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            val densityScale by displaySettings.densityScale.collectAsState(
                initial = DisplaySettingsStore.DEFAULT_SCALE,
            )
            val systemDensity = LocalDensity.current
            val appDensity = remember(systemDensity, densityScale) {
                Density(
                    density = systemDensity.density * densityScale,
                    fontScale = systemDensity.fontScale,
                )
            }
            CompositionLocalProvider(LocalDensity provides appDensity) {
                OpenWaveTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        OpenWaveNavHost()
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
