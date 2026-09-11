package dev.woms.mumdroid.ui.screen

import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Same pull tracking as the default [PullToRefreshState], but hide is a snap
 * so the cards jump back with the indicator the moment refresh ends.
 */
@Stable
internal class InstantHidePullToRefreshState : PullToRefreshState {
    private var distance by mutableFloatStateOf(0f)

    override val distanceFraction: Float
        get() = distance

    override val isAnimating: Boolean
        get() = false

    override suspend fun animateToHidden() {
        distance = 0f
    }

    override suspend fun animateToThreshold() {
        distance = 1f
    }

    override suspend fun snapTo(targetValue: Float) {
        distance = targetValue.coerceAtLeast(0f)
    }
}
