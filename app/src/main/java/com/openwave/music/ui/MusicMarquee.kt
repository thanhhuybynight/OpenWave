package com.openwave.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.continuousMarquee(): Modifier = basicMarquee(
    iterations = Int.MAX_VALUE,
    repeatDelayMillis = 1_500,
)
