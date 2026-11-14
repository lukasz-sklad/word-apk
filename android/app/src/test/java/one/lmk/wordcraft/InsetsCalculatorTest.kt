package one.lmk.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Test

class InsetsCalculatorTest {

    @Test
    fun pixel9ProPortraitOverlapsCutoutAndStatusBar() {
        val result = computeSurfaceInsets(
            decorWidth = 1280,
            decorHeight = 2856,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 1280,
            surfaceHeight = 2856,
            statusBar = EdgePx(top = 156),
            navBar = EdgePx(bottom = 72),
            captionBar = EdgePx(),
            cutoutSafe = EdgePx(top = 204),
            cutoutHasIntersectingRect = true,
            statusHasIntersectingRect = true,
            navHasIntersectingRect = true,
            captionHasIntersectingRect = false,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 0, top = 204, right = 0, bottom = 72, imeBottom = 0), result)
    }

    @Test
    fun pixel9ProLandscapeOverlapsLeftCutoutAndSlimStatusBar() {
        val result = computeSurfaceInsets(
            decorWidth = 2856,
            decorHeight = 1280,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 2856,
            surfaceHeight = 1280,
            statusBar = EdgePx(top = 72),
            navBar = EdgePx(bottom = 60),
            captionBar = EdgePx(),
            cutoutSafe = EdgePx(left = 204),
            cutoutHasIntersectingRect = true,
            statusHasIntersectingRect = true,
            navHasIntersectingRect = true,
            captionHasIntersectingRect = false,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 204, top = 72, right = 0, bottom = 60, imeBottom = 0), result)
    }

    @Test
    fun computerModeCaptionBarAboveSurfaceDropsTopPadding() {
        val result = computeSurfaceInsets(
            decorWidth = 1920,
            decorHeight = 1080,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 84,
            surfaceWidth = 1920,
            surfaceHeight = 996,
            statusBar = EdgePx(),
            navBar = EdgePx(),
            captionBar = EdgePx(top = 84),
            cutoutSafe = EdgePx(),
            cutoutHasIntersectingRect = false,
            statusHasIntersectingRect = false,
            navHasIntersectingRect = false,
            captionHasIntersectingRect = false,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 0, top = 0, right = 0, bottom = 0, imeBottom = 0), result)
    }

    @Test
    fun computerModeNonIntersectingCutoutAndExternalMonitorDropsTopPadding() {
        val result = computeSurfaceInsets(
            decorWidth = 1600,
            decorHeight = 900,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 1600,
            surfaceHeight = 900,
            statusBar = EdgePx(top = 72),
            navBar = EdgePx(bottom = 48),
            captionBar = EdgePx(top = 84),
            cutoutSafe = EdgePx(top = 204),
            cutoutHasIntersectingRect = false,
            statusHasIntersectingRect = false,
            navHasIntersectingRect = false,
            captionHasIntersectingRect = false,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 0, top = 0, right = 0, bottom = 0, imeBottom = 0), result)
    }

    @Test
    fun computerModeOverlayCaptionBarInsideSurfaceReservesTopSpace() {
        val result = computeSurfaceInsets(
            decorWidth = 1600,
            decorHeight = 900,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 1600,
            surfaceHeight = 900,
            statusBar = EdgePx(),
            navBar = EdgePx(),
            captionBar = EdgePx(top = 84),
            cutoutSafe = EdgePx(),
            cutoutHasIntersectingRect = false,
            statusHasIntersectingRect = false,
            navHasIntersectingRect = false,
            captionHasIntersectingRect = true,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 0, top = 84, right = 0, bottom = 0, imeBottom = 0), result)
    }

    @Test
    fun nonPixelTabletHiddenBarsAndNoCutoutHasZeroTopPadding() {
        val result = computeSurfaceInsets(
            decorWidth = 2560,
            decorHeight = 1600,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 2560,
            surfaceHeight = 1600,
            statusBar = EdgePx(),
            navBar = EdgePx(),
            captionBar = EdgePx(),
            cutoutSafe = EdgePx(),
            cutoutHasIntersectingRect = false,
            statusHasIntersectingRect = false,
            navHasIntersectingRect = false,
            captionHasIntersectingRect = false,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 0, top = 0, right = 0, bottom = 0, imeBottom = 0), result)
    }

    @Test
    fun nonPixelTabletVisibleTranslucentStatusBarStillReservesTopSpace() {
        val result = computeSurfaceInsets(
            decorWidth = 2560,
            decorHeight = 1600,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 2560,
            surfaceHeight = 1600,
            statusBar = EdgePx(top = 72),
            navBar = EdgePx(bottom = 72),
            captionBar = EdgePx(),
            cutoutSafe = EdgePx(),
            cutoutHasIntersectingRect = false,
            statusHasIntersectingRect = true,
            navHasIntersectingRect = true,
            captionHasIntersectingRect = false,
            imeBottomRaw = 0,
        )

        assertEquals(SurfaceInsets(left = 0, top = 72, right = 0, bottom = 72, imeBottom = 0), result)
    }

    @Test
    fun imeBottomSubtractsConsumedBottomChrome() {
        val result = computeSurfaceInsets(
            decorWidth = 1080,
            decorHeight = 2000,
            surfaceLeftInDecor = 0,
            surfaceTopInDecor = 0,
            surfaceWidth = 1080,
            surfaceHeight = 1936,
            statusBar = EdgePx(top = 60),
            navBar = EdgePx(bottom = 64),
            captionBar = EdgePx(),
            cutoutSafe = EdgePx(),
            cutoutHasIntersectingRect = false,
            statusHasIntersectingRect = null,
            navHasIntersectingRect = null,
            captionHasIntersectingRect = null,
            imeBottomRaw = 800,
        )

        assertEquals(SurfaceInsets(left = 0, top = 60, right = 0, bottom = 0, imeBottom = 736), result)
    }
}
