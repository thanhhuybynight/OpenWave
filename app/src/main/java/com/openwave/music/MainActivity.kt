package com.openwave.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.openwave.music.ui.navigation.OpenWaveNavHost
import com.openwave.music.ui.theme.OpenWaveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenWaveTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OpenWaveNavHost()
                }
            }
        }
    }
}
