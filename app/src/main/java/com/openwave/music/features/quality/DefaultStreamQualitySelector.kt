package com.openwave.music.features.quality

import com.openwave.music.core.domain.QualityPreference
import com.openwave.music.core.domain.StreamInfo
import com.openwave.music.core.domain.StreamQuality
import com.openwave.music.features.StreamQualitySelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prefer higher bitrate labels when Premium session allows MAX.
 * Candidates should carry [StreamInfo.qualityLabel] like "128kbps", "251", "256kbps".
 */
@Singleton
class DefaultStreamQualitySelector @Inject constructor() : StreamQualitySelector {

    override var preference: QualityPreference = QualityPreference()

    override fun select(
        candidates: List<StreamInfo>,
        preference: QualityPreference,
    ): StreamInfo? {
        if (candidates.isEmpty()) return null
        val ranked = candidates.sortedByDescending { estimateKbps(it) }
        return when (preference.preferred) {
            StreamQuality.AUTO -> ranked.firstOrNull { estimateKbps(it) in 48..160 } ?: ranked.first()
            StreamQuality.HIGH -> ranked.firstOrNull { estimateKbps(it) >= 128 } ?: ranked.first()
            StreamQuality.MAX -> {
                if (preference.hasYtmPremiumSession) {
                    ranked.firstOrNull { estimateKbps(it) >= 192 } ?: ranked.first()
                } else {
                    // Silently fall back — no fake "256kbps"
                    ranked.firstOrNull { estimateKbps(it) >= 128 } ?: ranked.first()
                }
            }
        }
    }

    private fun estimateKbps(info: StreamInfo): Int {
        val label = info.qualityLabel.orEmpty().lowercase()
        Regex("(\\d{2,3})\\s*kbps").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }
        // Common YT itag hints
        return when {
            "256" in label || label == "141" -> 256
            "160" in label || label == "251" || label == "140" -> 160
            "128" in label -> 128
            "64" in label || label == "250" -> 64
            "48" in label || label == "249" -> 48
            else -> 96
        }
    }
}
