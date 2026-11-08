package com.humnspace.integral.accessibility

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager

enum class ScreenEdge { RIGHT, LEFT, TOP, BOTTOM }

data class ButtonScreenLocation(
    val edge: ScreenEdge,
    val x: Float,
    val y: Float
)

object DeviceOrientationHelper {

    /**
     * Accurately maps the physical hardware Essential Key coordinates across
     * portrait, landscape (ROTATION_90), reverse landscape (ROTATION_270), and reverse portrait.
     *
     * In Portrait:
     * - Physical right button is at (width, height * originY) -> ScreenEdge.RIGHT
     *
     * In Landscape (ROTATION_90, device turned 90° CCW):
     * - Physical right button is at the Screen TOP edge! (width * originY, 0) -> ScreenEdge.TOP
     *
     * In Reverse Landscape (ROTATION_270, device turned 90° CW):
     * - Physical right button is at the Screen BOTTOM edge! (width * (1f - originY), height) -> ScreenEdge.BOTTOM
     *
     * In Reverse Portrait (ROTATION_180):
     * - Physical right button is at Screen LEFT edge! (0, height * (1f - originY)) -> ScreenEdge.LEFT
     */
    fun getButtonScreenLocation(context: Context, width: Float, height: Float): ButtonScreenLocation {
        val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X)
        val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y)
        val isPhysicalRight = originX >= 0.5f

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { context.display?.rotation ?: Surface.ROTATION_0 } catch (_: Throwable) { Surface.ROTATION_0 }
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        // Clamp originY slightly away from display extremities for safety
        val safeOriginY = originY.coerceIn(0.12f, 0.88f)

        return when (rotation) {
            Surface.ROTATION_90 -> {
                // Device rotated 90 deg counter-clockwise.
                // Physical Right becomes Screen TOP.
                // Physical Left becomes Screen BOTTOM.
                if (isPhysicalRight) {
                    ButtonScreenLocation(ScreenEdge.TOP, width * safeOriginY, 0f)
                } else {
                    ButtonScreenLocation(ScreenEdge.BOTTOM, width * (1f - safeOriginY), height)
                }
            }
            Surface.ROTATION_270 -> {
                // Device rotated 90 deg clockwise.
                // Physical Right becomes Screen BOTTOM.
                // Physical Left becomes Screen TOP.
                if (isPhysicalRight) {
                    ButtonScreenLocation(ScreenEdge.BOTTOM, width * (1f - safeOriginY), height)
                } else {
                    ButtonScreenLocation(ScreenEdge.TOP, width * safeOriginY, 0f)
                }
            }
            Surface.ROTATION_180 -> {
                // Inverted portrait
                if (isPhysicalRight) {
                    ButtonScreenLocation(ScreenEdge.LEFT, 0f, height * (1f - safeOriginY))
                } else {
                    ButtonScreenLocation(ScreenEdge.RIGHT, width, height * (1f - safeOriginY))
                }
            }
            else -> {
                // Standard portrait (ROTATION_0)
                if (isPhysicalRight) {
                    ButtonScreenLocation(ScreenEdge.RIGHT, width, height * safeOriginY)
                } else {
                    ButtonScreenLocation(ScreenEdge.LEFT, 0f, height * safeOriginY)
                }
            }
        }
    }
}

