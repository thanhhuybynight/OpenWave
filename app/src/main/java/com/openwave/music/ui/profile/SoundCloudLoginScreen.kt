package com.openwave.music.ui.profile

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.openwave.music.features.settings.SoundCloudSessionStore

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SoundCloudLoginScreen(onBack: () -> Unit, onLoginReady: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (CookieManager.getInstance().getCookie(SoundCloudSessionStore.SOUNDCLOUD_URL)
                                    .orEmpty().split(';').any {
                                        val cookie = it.trim()
                                        cookie.substringBefore('=') == "oauth_token" &&
                                            cookie.substringAfter('=', "").isNotBlank()
                                    }
                            ) onLoginReady()
                        }
                    }
                    loadUrl(SoundCloudSessionStore.LOGIN_URL)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Quay lại")
            }
        }
    }
}
