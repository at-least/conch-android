package at.least.conch

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Health dot for the status banner:
 * - CONNECTED + keep-alive: flashes once every 15s — the visible
 *   heartbeat, synced to the SSH keep-alive cadence (sshj offers no
 *   per-reply callback, so this is the cadence, not the reply)
 * - CONNECTED without keep-alive: solid
 * - CONNECTING/RECONNECTING: blinking
 * - STOPPED: dim grey
 */
@Composable
internal fun StatusDot(state: ConnState, keepAlive: Boolean) {
    val alpha = when (state) {
        ConnState.CONNECTED -> if (keepAlive) heartbeatAlpha() else 1f
        ConnState.CONNECTING, ConnState.RECONNECTING -> blinkAlpha()
        ConnState.STOPPED -> 0.35f
    }
    val color = if (state == ConnState.STOPPED) Color(0xFF37474F) else Color.White
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/** Full brightness for 0.8s, decays to 0.35 by 2s, holds until the next 15s beat. */
@Composable
private fun heartbeatAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(tween(15_000, easing = LinearEasing)),
        label = "phase",
    )
    // derivedStateOf: while the computed alpha holds at 0.35f (13 of every
    // 15 seconds) the structurally-equal write suppresses recomposition —
    // the animation clock ticks cheaply instead of redrawing every frame
    val alpha = remember {
        androidx.compose.runtime.derivedStateOf {
            val p = phase.value
            if (p < 0.8f) {
                1f
            } else {
                0.35f + 0.65f * (1f - ((p - 0.8f) / 1.2f).coerceIn(0f, 1f))
            }
        }
    }
    return alpha.value
}

@Composable
private fun blinkAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "blink")
    val a by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return a
}
