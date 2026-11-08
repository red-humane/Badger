package com.humnspace.integral.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Badger Motion Design Specification:
 * Origin: Animations emerge from the physical Badge (Essential Key) position.
 * Continuity: Elements move instead of abruptly appearing.
 * Restraint: Only important elements animate.
 * Feedback: Amber indicates interaction and active state.
 * Depth: Layered opacity and subtle scale replace blur and heavy glass.
 */
object BadgerMotion {
    const val Fast = 150
    const val Normal = 250
    const val Entrance = 320
    const val ButtonIndicator = 120
    const val CapsuleEntrance = 280
    const val CapsuleDismissal = 150
    const val StaggerDelay = 40
    const val TextHighlight = 180
    const val SelectionPill = 220
    const val RingerMorph = 250
    const val ScanSweep = 1600

    val StandardSpring = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = 450f
    )

    val SoftSpring = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = 320f
    )
}

/**
 * Badger Signature Surface Palette:
 * 90% solid dark surfaces and 10% controlled translucency.
 */
object BadgerColors {
    val Background = Color(0xFF090909)
    val Surface = Color(0xFF191919)
    val SurfaceDark = Color(0xFF121214)
    val Elevated = Color(0xFF222222)
    val Amber = Color(0xFFFFB300)
    val PrimaryText = Color(0xFFFFFFFF)
    val SecondaryText = Color(0xFF999999)
}

/**
 * Animated contextual capsule: Reusable component for Get-Content, Ringer and Voice.
 * Notice that there is no blur() or RenderEffect. The rich appearance comes from
 * layered opacity, contrast and typography.
 */
@Composable
fun BadgerCapsule(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(
            tween(180)
        ) + scaleIn(
            initialScale = 0.94f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 380f
            )
        ) + slideInVertically(
            initialOffsetY = { it / 5 },
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = 400f
            )
        ),
        exit = fadeOut(tween(130)) +
            scaleOut(
                targetScale = 0.97f,
                animationSpec = tween(130)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xF0191919),
                    RoundedCornerShape(20.dp)
                )
                .border(
                    0.7.dp,
                    Color.White.copy(alpha = 0.09f),
                    RoundedCornerShape(20.dp)
                )
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * Amber scanning line: For Get-Content, continuously moving line with a subtle fade.
 * Runs only while scanning, with an ease-in-out sweep.
 */
@Composable
fun BadgerScanLine(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(
        label = "badger_scan"
    )

    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = BadgerMotion.ScanSweep,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_progress"
    )

    Canvas(modifier = modifier) {
        val y = size.height * progress

        drawLine(
            color = Color(0xFFFFB300).copy(alpha = 0.85f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

/**
 * Connected Spring physics state for rows or groups of physically connected buttons.
 * When button i is pressed, neighboring buttons respond with a subtle connected displacement.
 */
class ConnectedSpringState(val itemCount: Int) {
    var pressedIndex by mutableStateOf<Int?>(null)
}

@Composable
fun rememberConnectedSpringState(itemCount: Int): ConnectedSpringState {
    return remember(itemCount) { ConnectedSpringState(itemCount) }
}

/**
 * Modifier for a button that belongs to a connected spring group.
 */
fun Modifier.connectedSpringButton(
    index: Int,
    state: ConnectedSpringState,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }

    val isPressed = (state.pressedIndex == index)
    val isLeftNeighbor = (state.pressedIndex != null && state.pressedIndex == index + 1)
    val isRightNeighbor = (state.pressedIndex != null && state.pressedIndex == index - 1)

    LaunchedEffect(isPressed, isLeftNeighbor, isRightNeighbor) {
        val springSpec = spring<Float>(
            dampingRatio = 0.72f,
            stiffness = 480f
        )
        when {
            isPressed -> {
                launch { scaleAnim.animateTo(0.955f, springSpec) }
                launch { offsetXAnim.animateTo(0f, springSpec) }
            }
            isLeftNeighbor -> {
                launch { scaleAnim.animateTo(0.988f, springSpec) }
                launch { offsetXAnim.animateTo(-3f, springSpec) }
            }
            isRightNeighbor -> {
                launch { scaleAnim.animateTo(0.988f, springSpec) }
                launch { offsetXAnim.animateTo(3f, springSpec) }
            }
            else -> {
                launch { scaleAnim.animateTo(1f, springSpec) }
                launch { offsetXAnim.animateTo(0f, springSpec) }
            }
        }
    }

    this
        .offset { IntOffset(x = (offsetXAnim.value * density).roundToInt(), y = 0) }
        .graphicsLayer {
            scaleX = scaleAnim.value
            scaleY = scaleAnim.value
        }
        .pointerInput(enabled, index) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    state.pressedIndex = index
                    try {
                        tryAwaitRelease()
                    } finally {
                        state.pressedIndex = null
                    }
                },
                onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
        }
}

/**
 * Single spring-reactive button modifier for standalone cards and buttons in Badger.
 */
fun Modifier.badgerSpringClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.955f,
    onClick: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val scaleAnim = remember { Animatable(1f) }

    this
        .graphicsLayer {
            scaleX = scaleAnim.value
            scaleY = scaleAnim.value
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    coroutineScope {
                        launch {
                            scaleAnim.animateTo(
                                pressedScale,
                                spring(dampingRatio = 0.72f, stiffness = 500f)
                            )
                        }
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        scaleAnim.animateTo(
                            1f,
                            spring(dampingRatio = 0.72f, stiffness = 420f)
                        )
                    }
                },
                onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
        }
}
