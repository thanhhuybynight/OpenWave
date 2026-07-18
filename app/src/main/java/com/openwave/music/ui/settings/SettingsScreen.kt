package com.openwave.music.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openwave.music.BuildConfig
import com.openwave.music.presentation.SettingsViewModel

private const val PROJECT_URL = "https://github.com/thanhhuybynight/OpenWave"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val autoContinue by vm.autoContinue.collectAsStateWithLifecycle()
    val crossfade by vm.crossfadeSettings.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Quay lại",
                )
            }
            Text(
                text = "Cài đặt",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            SectionLabel("Phát nhạc")
            SettingsSwitchRow(
                title = "Auto radio",
                checked = autoContinue,
                onCheckedChange = vm::setAutoContinue,
            )
            SettingsSwitchRow(
                title = "Crossfade",
                checked = crossfade.enabled,
                onCheckedChange = vm::setCrossfade,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(20.dp))

            SectionLabel("Thông tin")
            SettingsInfoRow(title = "OpenWave")
            SettingsInfoRow(title = "Phiên bản ${BuildConfig.VERSION_NAME}")
            SettingsInfoRow(title = "FOSS · không GMS · không bắt buộc đăng nhập")
            SettingsInfoRow(title = "Nguồn: YouTube Music · SoundCloud · Local")

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)),
                        )
                    }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Mã nguồn trên GitHub",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = scheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))
            SettingsInfoRow(title = "License: AGPL-3.0 / project FOSS terms")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsInfoRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    )
}
