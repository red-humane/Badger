package com.humnspace.integral.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humnspace.integral.BadgerAction
import com.humnspace.integral.SatoshiFontFamily
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Badger Design System Tokens:
 * Tactile Minimalism × Nothing-inspired Lucent UI × Bold Geometry
 */
object BadgerTheme {
    // Color Palette
    val AmoledBlack = Color(0xFF000000)
    val DeepCharcoal = Color(0xFF171717)
    val SurfaceElevated = Color(0xFF202023)
    val BorderSubtle = Color(0xFF26262A)
    val Yellow = Color(0xFFFFB300) // Project token Accent
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF929292)
    val TextMuted = Color(0xFF6E6E6E)

    // Shapes
    val ShapeCircle = CircleShape
    val ShapeCapsule = RoundedCornerShape(percent = 50)
    val ShapeTile = RoundedCornerShape(22.dp)
    val ShapeSquircle = RoundedCornerShape(18.dp)
    val ShapeCompact = RoundedCornerShape(14.dp)

    // Standard Heights
    val HeightCapsule: Dp = 50.dp
    val HeightSegmentedPill: Dp = 40.dp
    val HeightActionTile: Dp = 126.dp
}

/**
 * Tactile press interaction modifier:
 * Compresses slightly (scale 0.95f) on press with haptic feedback,
 * and rebounds smoothly upon release with controlled 120ms spring.
 */
fun Modifier.badgerTactileClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
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
                                spring(dampingRatio = 0.72f, stiffness = 550f)
                            )
                        }
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        scaleAnim.animateTo(
                            1f,
                            spring(dampingRatio = 0.72f, stiffness = 450f)
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

/**
 * Circular Action Button (Reference 1):
 * - Default: Deep Charcoal (#171717), White icon (#F5F5F5)
 * - Selected: Badger Yellow (#FFB300), Black icon (#000000)
 */
@Composable
fun BadgerCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    contentDescription: String? = null
) {
    val bgColor = if (selected) BadgerTheme.Yellow else BadgerTheme.DeepCharcoal
    val iconColor = if (selected) BadgerTheme.AmoledBlack else BadgerTheme.TextPrimary
    val borderColor = if (selected) Color.Transparent else BadgerTheme.BorderSubtle

    Box(
        modifier = modifier
            .size(size)
            .background(bgColor, BadgerTheme.ShapeCircle)
            .border(1.dp, borderColor, BadgerTheme.ShapeCircle)
            .badgerTactileClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Extended Capsule Button for Primary Actions (Reference 1):
 * - Primary: Badger Yellow (#FFB300) with Black text/icon
 * - Secondary: Deep Charcoal (#171717) with White text/icon
 */
@Composable
fun BadgerCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    val bgColor = when {
        !enabled -> Color(0xFF222226)
        primary -> BadgerTheme.Yellow
        else -> BadgerTheme.DeepCharcoal
    }
    val contentColor = when {
        !enabled -> BadgerTheme.TextMuted
        primary -> BadgerTheme.AmoledBlack
        else -> BadgerTheme.TextPrimary
    }
    val borderColor = when {
        !enabled || primary -> Color.Transparent
        else -> BadgerTheme.BorderSubtle
    }

    Box(
        modifier = modifier
            .height(BadgerTheme.HeightCapsule)
            .background(bgColor, BadgerTheme.ShapeCapsule)
            .border(1.dp, borderColor, BadgerTheme.ShapeCapsule)
            .badgerTactileClick(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = contentColor,
                fontSize = 14.5.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
            if (icon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * Segmented Pill Button for Gestures ("Single", "Double", "Triple", "Hold"):
 * - Active: Badger Yellow with Black text
 * - Default: Deep Charcoal with Muted Grey text
 */
@Composable
fun BadgerSegmentedPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) BadgerTheme.Yellow else BadgerTheme.DeepCharcoal
    val textColor = if (isSelected) BadgerTheme.AmoledBlack else BadgerTheme.TextSecondary
    val borderColor = if (isSelected) Color.Transparent else BadgerTheme.BorderSubtle

    Box(
        modifier = modifier
            .height(BadgerTheme.HeightSegmentedPill)
            .background(bgColor, BadgerTheme.ShapeCapsule)
            .border(1.dp, borderColor, BadgerTheme.ShapeCapsule)
            .badgerTactileClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.5.sp,
            fontFamily = SatoshiFontFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.2.sp
        )
    }
}

/**
 * Compact Icon-Only Control (e.g., Top Bar buttons):
 */
@Composable
fun BadgerCompactIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .background(BadgerTheme.DeepCharcoal, BadgerTheme.ShapeSquircle)
            .border(1.dp, BadgerTheme.BorderSubtle, BadgerTheme.ShapeSquircle)
            .badgerTactileClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = BadgerTheme.TextPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 2-Column Action Tile (Reference 3):
 * - Default: Deep Charcoal (#171717), minimal icon, short label
 * - Active: Badger Yellow (#FFB300), Black iconography, Black text
 */
@Composable
fun BadgerActionTile(
    action: BadgerAction,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) BadgerTheme.Yellow else BadgerTheme.DeepCharcoal
    val iconColor = if (isSelected) BadgerTheme.AmoledBlack else BadgerTheme.TextPrimary
    val titleColor = if (isSelected) BadgerTheme.AmoledBlack else BadgerTheme.TextPrimary
    val descColor = if (isSelected) Color(0xFF2A2A2E) else BadgerTheme.TextSecondary
    val borderColor = if (isSelected) Color.Transparent else BadgerTheme.BorderSubtle
    val iconBgColor = if (isSelected) Color(0x22000000) else Color(0xFF222226)

    Box(
        modifier = modifier
            .height(BadgerTheme.HeightActionTile)
            .background(bgColor, BadgerTheme.ShapeTile)
            .border(1.dp, borderColor, BadgerTheme.ShapeTile)
            .badgerTactileClick(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Squircle Icon Badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(iconBgColor, BadgerTheme.ShapeCompact),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Active Badge
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .background(BadgerTheme.AmoledBlack, BadgerTheme.ShapeCapsule)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = BadgerTheme.Yellow,
                            fontSize = 9.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }

            Column {
                Text(
                    text = action.title,
                    color = titleColor,
                    fontSize = 14.5.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = action.description,
                    color = descColor,
                    fontSize = 10.5.sp,
                    fontFamily = SatoshiFontFamily,
                    lineHeight = 14.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

