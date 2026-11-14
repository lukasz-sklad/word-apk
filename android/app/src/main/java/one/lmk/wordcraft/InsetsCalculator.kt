package one.lmk.wordcraft

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.WindowInsetsCompat

/**
 * Pixel inset margins on each edge of a surface.
 */
data class EdgePx(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/**
 * Final overlap insets passed across JNI into Rust (`nativeOnInsets`).
 */
data class SurfaceInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val imeBottom: Int,
)

/**
 * Pure overlap math for system bars, desktop caption bars, display cutouts, and IME.
 *
 * Subtracting the offsets that parent/decor chrome already consumed outside the drawing
 * surface avoids empty double-padding in computer/desktop/freeform mode and on devices where
 * the OS reserves system bar or cutout space outside `SurfaceView`.
 */
internal fun computeSurfaceInsets(
    decorWidth: Int,
    decorHeight: Int,
    surfaceLeftInDecor: Int,
    surfaceTopInDecor: Int,
    surfaceWidth: Int,
    surfaceHeight: Int,
    statusBar: EdgePx,
    navBar: EdgePx,
    captionBar: EdgePx,
    cutoutSafe: EdgePx,
    cutoutHasIntersectingRect: Boolean,
    statusHasIntersectingRect: Boolean?,
    navHasIntersectingRect: Boolean?,
    captionHasIntersectingRect: Boolean?,
    imeBottomRaw: Int,
): SurfaceInsets {
    val hasValidBounds =
        decorWidth > 0 && decorHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0

    val consumedLeft = if (hasValidBounds) surfaceLeftInDecor.coerceAtLeast(0) else 0
    val consumedTop = if (hasValidBounds) surfaceTopInDecor.coerceAtLeast(0) else 0
    val consumedRight = if (hasValidBounds) {
        (decorWidth - (surfaceLeftInDecor + surfaceWidth)).coerceAtLeast(0)
    } else {
        0
    }
    val consumedBottom = if (hasValidBounds) {
        (decorHeight - (surfaceTopInDecor + surfaceHeight)).coerceAtLeast(0)
    } else {
        0
    }

    val effectiveStatus = if (statusHasIntersectingRect == false) EdgePx() else statusBar
    val effectiveNav = if (navHasIntersectingRect == false) EdgePx() else navBar
    val effectiveCaption = if (captionHasIntersectingRect == false) EdgePx() else captionBar
    val effectiveCutout = if (cutoutHasIntersectingRect) cutoutSafe else EdgePx()

    val left = maxOf(
        (effectiveStatus.left - consumedLeft).coerceAtLeast(0),
        (effectiveNav.left - consumedLeft).coerceAtLeast(0),
        (effectiveCaption.left - consumedLeft).coerceAtLeast(0),
        (effectiveCutout.left - consumedLeft).coerceAtLeast(0),
    )
    val top = maxOf(
        (effectiveStatus.top - consumedTop).coerceAtLeast(0),
        (effectiveNav.top - consumedTop).coerceAtLeast(0),
        (effectiveCaption.top - consumedTop).coerceAtLeast(0),
        (effectiveCutout.top - consumedTop).coerceAtLeast(0),
    )
    val right = maxOf(
        (effectiveStatus.right - consumedRight).coerceAtLeast(0),
        (effectiveNav.right - consumedRight).coerceAtLeast(0),
        (effectiveCaption.right - consumedRight).coerceAtLeast(0),
        (effectiveCutout.right - consumedRight).coerceAtLeast(0),
    )
    val bottom = maxOf(
        (effectiveStatus.bottom - consumedBottom).coerceAtLeast(0),
        (effectiveNav.bottom - consumedBottom).coerceAtLeast(0),
        (effectiveCaption.bottom - consumedBottom).coerceAtLeast(0),
        (effectiveCutout.bottom - consumedBottom).coerceAtLeast(0),
    )
    val imeBottom = (imeBottomRaw - consumedBottom).coerceAtLeast(0)

    return SurfaceInsets(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        imeBottom = imeBottom,
    )
}

internal fun extractSurfaceInsets(
    decorView: View,
    targetSurface: View,
    insets: WindowInsetsCompat,
): SurfaceInsets {
    val decorLoc = IntArray(2)
    val surfaceLoc = IntArray(2)
    decorView.getLocationInWindow(decorLoc)
    targetSurface.getLocationInWindow(surfaceLoc)

    val surfaceWidth = targetSurface.width
    val surfaceHeight = targetSurface.height
    val surfaceLeftInDecor = surfaceLoc[0] - decorLoc[0]
    val surfaceTopInDecor = surfaceLoc[1] - decorLoc[1]
    val hasLayout = surfaceWidth > 0 && surfaceHeight > 0

    val surfaceWindowRect = if (hasLayout) {
        Rect(
            surfaceLoc[0],
            surfaceLoc[1],
            surfaceLoc[0] + surfaceWidth,
            surfaceLoc[1] + surfaceHeight,
        )
    } else {
        null
    }

    val statusVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
    val statusInsets = if (statusVisible) {
        insets.getInsets(WindowInsetsCompat.Type.statusBars())
    } else {
        null
    }

    val navVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    val navInsets = if (navVisible) {
        insets.getInsets(WindowInsetsCompat.Type.navigationBars())
    } else {
        null
    }

    val captionVisible = insets.isVisible(WindowInsetsCompat.Type.captionBar())
    val captionInsets = if (captionVisible) {
        insets.getInsets(WindowInsetsCompat.Type.captionBar())
    } else {
        null
    }

    val cutout = insets.displayCutout
    val cutoutSafe = if (cutout != null) {
        EdgePx(
            left = cutout.safeInsetLeft,
            top = cutout.safeInsetTop,
            right = cutout.safeInsetRight,
            bottom = cutout.safeInsetBottom,
        )
    } else {
        EdgePx()
    }

    val cutoutHasIntersectingRect = if (cutout == null) {
        false
    } else if (!hasLayout) {
        true
    } else {
        val rects = cutout.boundingRects
        rects.any { Rect.intersects(surfaceWindowRect!!, it) }
    }

    // WindowInsets.getBoundingRects(typeMask) exists on Android 15 (API 35) and newer.
    val canQueryTypeBoundingRects =
        hasLayout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    val statusIntersects = if (canQueryTypeBoundingRects && statusVisible) {
        insets.getBoundingRects(WindowInsetsCompat.Type.statusBars())
            .any { Rect.intersects(surfaceWindowRect!!, it) }
    } else {
        null
    }
    val navIntersects = if (canQueryTypeBoundingRects && navVisible) {
        insets.getBoundingRects(WindowInsetsCompat.Type.navigationBars())
            .any { Rect.intersects(surfaceWindowRect!!, it) }
    } else {
        null
    }
    val captionIntersects = if (canQueryTypeBoundingRects && captionVisible) {
        insets.getBoundingRects(WindowInsetsCompat.Type.captionBar())
            .any { Rect.intersects(surfaceWindowRect!!, it) }
    } else {
        null
    }

    val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
    val imeRaw = if (imeVisible) {
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom.coerceAtLeast(1)
    } else {
        0
    }

    return computeSurfaceInsets(
        decorWidth = decorView.width,
        decorHeight = decorView.height,
        surfaceLeftInDecor = surfaceLeftInDecor,
        surfaceTopInDecor = surfaceTopInDecor,
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        statusBar = statusInsets.toEdgePx(),
        navBar = navInsets.toEdgePx(),
        captionBar = captionInsets.toEdgePx(),
        cutoutSafe = cutoutSafe,
        cutoutHasIntersectingRect = cutoutHasIntersectingRect,
        statusHasIntersectingRect = statusIntersects,
        navHasIntersectingRect = navIntersects,
        captionHasIntersectingRect = captionIntersects,
        imeBottomRaw = imeRaw,
    )
}

private fun androidx.core.graphics.Insets?.toEdgePx(): EdgePx =
    if (this == null) EdgePx() else EdgePx(left, top, right, bottom)
