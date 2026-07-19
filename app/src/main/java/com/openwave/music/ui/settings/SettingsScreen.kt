package com.openwave.music.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.openwave.music.core.domain.AudioFxState
import com.openwave.music.core.domain.EqPreset
import com.openwave.music.core.domain.SoundType
import com.openwave.music.features.audiofx.AudioFxSettingsStore
import com.openwave.music.presentation.SettingsViewModel
import kotlin.math.roundToInt

private const val PROJECT_URL = "https://github.com/thanhhuybynight/OpenWave"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val autoContinue by vm.autoContinue.collectAsStateWithLifecycle()
    val crossplay by vm.crossplay.collectAsStateWithLifecycle()
    val audioFx by vm.audioFxState.collectAsStateWithLifecycle()
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
                title = "Auto-queue (Radio)",
                description = "Luôn bật mặc định: khi hết hàng đợi sẽ phát tiếp bài liên quan ngẫu nhiên. Tắt bằng nút radio trên Now Playing.",
                checked = autoContinue,
                onCheckedChange = vm::setAutoContinue,
            )
            SettingsSwitchRow(
                title = "Crossplay",
                description = if (crossplay) {
                    "Bật — bài tiếp theo có thể lấy từ mọi nguồn (YouTube Music, SoundCloud…)"
                } else {
                    "Tắt — chỉ phát tiếp từ cùng nguồn với bài hiện tại"
                },
                checked = crossplay,
                onCheckedChange = vm::setCrossplay,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(20.dp))

            SectionLabel("Equalizer")
            Text(
                text = "Chỉ áp dụng cho âm thanh trong OpenWave (không chỉnh EQ hệ thống).",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsSwitchRow(
                title = "Bật Equalizer",
                description = "Các dải tần và preset bên dưới",
                checked = audioFx.enabled,
                onCheckedChange = vm::setEqEnabled,
            )

            Text(
                text = "Preset",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EqPreset.entries.filter { it != EqPreset.CUSTOM }.forEach { preset ->
                    FilterChip(
                        selected = audioFx.preset == preset ||
                            (audioFx.preset == EqPreset.CUSTOM && preset == EqPreset.FLAT &&
                                audioFx.bandGainsDb == AudioFxState.FlatGains),
                        onClick = { vm.setEqPreset(preset) },
                        enabled = audioFx.enabled,
                        label = { Text(presetLabel(preset)) },
                    )
                }
                if (audioFx.preset == EqPreset.CUSTOM) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        enabled = audioFx.enabled,
                        label = { Text("Custom") },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            audioFx.bandGainsDb.forEachIndexed { index, gain ->
                EqBandRow(
                    label = AudioFxState.BandLabels.getOrElse(index) { "Band ${index + 1}" },
                    freqHint = AudioFxState.BandCentersHz.getOrNull(index)?.let { hz ->
                        if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                    }.orEmpty(),
                    gainDb = gain,
                    enabled = audioFx.enabled,
                    onChange = { vm.setEqBand(index, it) },
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(20.dp))

            SectionLabel("Âm thanh")
            Text(
                text = "Sound Type",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = audioFx.soundType == SoundType.STEREO,
                    onClick = { vm.setSoundType(SoundType.STEREO) },
                    label = { Text("Stereo") },
                )
                FilterChip(
                    selected = audioFx.soundType == SoundType.MONO,
                    onClick = { vm.setSoundType(SoundType.MONO) },
                    label = { Text("Mono") },
                )
            }
            Text(
                text = if (audioFx.soundType == SoundType.MONO) {
                    "Mono — trộn L/R thành một kênh (tai nghe / loa vẫn phát cả hai bên)."
                } else {
                    "Stereo — giữ tách kênh trái / phải."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            SettingsSwitchRow(
                title = "Normalize Volume",
                description = "Cân bằng độ lớn giữa các bài, giảm spike / earrape (loudness + limiter trong app).",
                checked = audioFx.normalizeVolume,
                onCheckedChange = vm::setNormalizeVolume,
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
                    Icons.Outlined.Link,
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
private fun EqBandRow(
    label: String,
    freqHint: String,
    gainDb: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.5f),
                )
                if (freqHint.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = freqHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatDb(gainDb),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
        }
        Slider(
            value = gainDb,
            onValueChange = onChange,
            valueRange = AudioFxSettingsStore.MIN_DB..AudioFxSettingsStore.MAX_DB,
            steps = 60,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatDb(db: Float): String {
    val v = (db * 10f).roundToInt() / 10f
    return if (v > 0f) "+$v dB" else "$v dB"
}

private fun presetLabel(preset: EqPreset): String = when (preset) {
    EqPreset.FLAT -> "Flat"
    EqPreset.POP -> "Pop"
    EqPreset.JAZZ -> "Jazz"
    EqPreset.EDM -> "EDM"
    EqPreset.BASSBOOST -> "Bassboost"
    EqPreset.CUSTOM -> "Custom"
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
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
