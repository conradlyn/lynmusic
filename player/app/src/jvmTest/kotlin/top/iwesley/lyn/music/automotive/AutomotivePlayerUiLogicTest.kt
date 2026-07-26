package top.iwesley.lyn.music.automotive

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.iwesley.lyn.music.core.model.PlaybackSnapshot
import top.iwesley.lyn.music.core.model.PlayerArtworkStyle
import top.iwesley.lyn.music.feature.player.PlayerIntent

class AutomotivePlayerUiLogicTest {
    @Test
    fun `playback controls hide secondary actions below 252 dp width`() {
        listOf(200.dp, 251.dp).forEach { maxWidth ->
            val layout = resolveAutomotivePlaybackControlsLayout(
                maxWidth = maxWidth,
                maxHeight = 800.dp,
            )

            assertEquals(false, layout.showSecondaryControls)
            assertEquals(48.dp, layout.skipButtonSize)
            assertEquals(60.dp, layout.playButtonSize)
            assertEquals(26.dp, layout.skipIconSize)
            assertEquals(46.dp, layout.playIconSize)
            assertEquals(4.dp, layout.controlGap)
            assertEquals(164.dp, layout.totalWidth)
            assertTrue(layout.totalWidth <= maxWidth)
        }
    }

    @Test
    fun `playback controls show safe compact five button layout from 252 through 287 dp`() {
        listOf(252.dp, 287.dp).forEach { maxWidth ->
            val layout = resolveAutomotivePlaybackControlsLayout(
                maxWidth = maxWidth,
                maxHeight = 800.dp,
            )

            assertEquals(true, layout.showSecondaryControls)
            assertEquals(48.dp, layout.actionButtonSize)
            assertEquals(48.dp, layout.skipButtonSize)
            assertEquals(60.dp, layout.playButtonSize)
            assertEquals(22.dp, layout.actionIconSize)
            assertEquals(26.dp, layout.skipIconSize)
            assertEquals(46.dp, layout.playIconSize)
            assertEquals(0.dp, layout.controlGap)
            assertEquals(252.dp, layout.totalWidth)
            assertTrue(layout.totalWidth <= maxWidth)
        }
    }

    @Test
    fun `playback controls switch to enlarged narrow layout at 288 dp`() {
        val layout = resolveAutomotivePlaybackControlsLayout(
            maxWidth = 288.dp,
            maxHeight = 800.dp,
        )

        assertEquals(true, layout.showSecondaryControls)
        assertEquals(48.dp, layout.actionButtonSize)
        assertEquals(56.dp, layout.skipButtonSize)
        assertEquals(72.dp, layout.playButtonSize)
        assertEquals(2.dp, layout.controlGap)
    }

    @Test
    fun `playback controls use enlarged narrow layout below 400 dp`() {
        val layout = resolveAutomotivePlaybackControlsLayout(
            maxWidth = 399.dp,
            maxHeight = 800.dp,
        )

        assertEquals(true, layout.showSecondaryControls)
        assertEquals(48.dp, layout.actionButtonSize)
        assertEquals(56.dp, layout.skipButtonSize)
        assertEquals(72.dp, layout.playButtonSize)
        assertEquals(26.dp, layout.actionIconSize)
        assertEquals(32.dp, layout.skipIconSize)
        assertEquals(56.dp, layout.playIconSize)
        assertEquals(2.dp, layout.controlGap)
    }

    @Test
    fun `playback controls use compact layout from 400 through 519 dp`() {
        listOf(400.dp, 519.dp).forEach { maxWidth ->
            val layout = resolveAutomotivePlaybackControlsLayout(
                maxWidth = maxWidth,
                maxHeight = 800.dp,
            )

            assertEquals(true, layout.showSecondaryControls)
            assertEquals(56.dp, layout.actionButtonSize)
            assertEquals(64.dp, layout.skipButtonSize)
            assertEquals(84.dp, layout.playButtonSize)
            assertEquals(30.dp, layout.actionIconSize)
            assertEquals(36.dp, layout.skipIconSize)
            assertEquals(64.dp, layout.playIconSize)
            assertEquals(6.dp, layout.controlGap)
        }
    }

    @Test
    fun `playback controls use extra compact layout for wide short car pane`() {
        val layout = resolveAutomotivePlaybackControlsLayout(
            maxWidth = 520.dp,
            maxHeight = 424.dp,
        )

        assertEquals(true, layout.showSecondaryControls)
        assertEquals(48.dp, layout.actionButtonSize)
        assertEquals(48.dp, layout.skipButtonSize)
        assertEquals(60.dp, layout.playButtonSize)
        assertEquals(22.dp, layout.actionIconSize)
        assertEquals(26.dp, layout.skipIconSize)
        assertEquals(46.dp, layout.playIconSize)
        assertEquals(0.dp, layout.controlGap)
    }

    @Test
    fun `playback controls use compact layout below 520 dp height`() {
        val layout = resolveAutomotivePlaybackControlsLayout(
            maxWidth = 520.dp,
            maxHeight = 480.dp,
        )

        assertEquals(true, layout.showSecondaryControls)
        assertEquals(56.dp, layout.actionButtonSize)
        assertEquals(64.dp, layout.skipButtonSize)
        assertEquals(84.dp, layout.playButtonSize)
        assertEquals(30.dp, layout.actionIconSize)
        assertEquals(36.dp, layout.skipIconSize)
        assertEquals(64.dp, layout.playIconSize)
        assertEquals(6.dp, layout.controlGap)
    }

    @Test
    fun `playback controls switch to large layout at 520 dp width and height`() {
        val layout = resolveAutomotivePlaybackControlsLayout(
            maxWidth = 520.dp,
            maxHeight = 520.dp,
        )

        assertEquals(true, layout.showSecondaryControls)
        assertEquals(80.dp, layout.actionButtonSize)
        assertEquals(88.dp, layout.skipButtonSize)
        assertEquals(108.dp, layout.playButtonSize)
        assertEquals(36.dp, layout.actionIconSize)
        assertEquals(44.dp, layout.skipIconSize)
        assertEquals(76.dp, layout.playIconSize)
        assertEquals(10.dp, layout.controlGap)
    }

    @Test
    fun `playback controls never expose a button below minimum touch size`() {
        val layouts = listOf(
            resolveAutomotivePlaybackControlsLayout(maxWidth = 200.dp, maxHeight = 800.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 252.dp, maxHeight = 800.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 288.dp, maxHeight = 800.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 399.dp, maxHeight = 800.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 400.dp, maxHeight = 800.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 519.dp, maxHeight = 800.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 520.dp, maxHeight = 424.dp),
            resolveAutomotivePlaybackControlsLayout(maxWidth = 520.dp, maxHeight = 520.dp),
        )

        layouts.forEach { layout ->
            assertTrue(layout.skipButtonSize >= 48.dp)
            assertTrue(layout.playButtonSize >= 48.dp)
            if (layout.showSecondaryControls) {
                assertTrue(layout.actionButtonSize >= 48.dp)
            }
        }
    }

    @Test
    fun `track and progress layout caps normal artwork at larger car size`() {
        val layout = resolveAutomotiveTrackAndProgressLayout(
            maxWidth = 900.dp,
            maxHeight = 800.dp,
        )

        assertEquals(false, layout.compactVertical)
        assertEquals(360.dp, layout.artworkSize)
        assertEquals(360.dp, layout.artworkMaximumSize)
        assertEquals(20.dp, layout.artworkTitleGap)
        assertEquals(44.dp, layout.progressTopGap)
        assertEquals(6.dp, layout.bottomPadding)
        assertEquals(0.86f, layout.progressWidthFraction)
    }

    @Test
    fun `track and progress layout keeps compact car height conservative`() {
        val layout = resolveAutomotiveTrackAndProgressLayout(
            maxWidth = 500.dp,
            maxHeight = 400.dp,
        )

        assertEquals(true, layout.compactVertical)
        assertEquals(192.dp, layout.artworkSize)
        assertEquals(250.dp, layout.artworkMaximumSize)
        assertEquals(12.dp, layout.artworkTitleGap)
        assertEquals(26.dp, layout.progressTopGap)
        assertEquals(8.dp, layout.bottomPadding)
        assertEquals(0.9f, layout.progressWidthFraction)
    }

    @Test
    fun `track and progress layout reduces vertical chrome for ultra compact height`() {
        val layout = resolveAutomotiveTrackAndProgressLayout(
            maxWidth = 520.dp,
            maxHeight = 300.dp,
        )

        assertEquals(true, layout.compactVertical)
        assertEquals(140.dp, layout.artworkSize)
        assertEquals(220.dp, layout.artworkMaximumSize)
        assertEquals(8.dp, layout.artworkTitleGap)
        assertEquals(2.dp, layout.metadataGap)
        assertEquals(16.dp, layout.progressTopGap)
        assertEquals(4.dp, layout.bottomPadding)
        assertEquals(0.92f, layout.progressWidthFraction)
    }

    @Test
    fun `artwork display spec defaults to vinyl with existing sizing`() {
        val spec = resolveAutomotiveArtworkDisplaySpec(artworkSize = 320.dp)

        assertEquals(PlayerArtworkStyle.VINYL, spec.style)
        assertEquals(320.dp, spec.artworkSize)
        assertEquals(0.76f, spec.vinylArtworkDiameterFraction)
        assertEquals(0.72f, spec.vinylInnerGlowDiameterFraction)
    }

    @Test
    fun `artwork display spec passes selected style without changing layout size`() {
        PlayerArtworkStyle.entries.forEach { style ->
            val spec = resolveAutomotiveArtworkDisplaySpec(
                artworkSize = 320.dp,
                playerArtworkStyle = style,
            )

            assertEquals(style, spec.style)
            assertEquals(320.dp, spec.artworkSize)
        }
    }

    @Test
    fun `progress fraction clamps to playback bounds`() {
        assertEquals(
            0.25f,
            resolveAutomotivePlayerProgressFraction(
                PlaybackSnapshot(positionMs = 25_000L, durationMs = 100_000L),
            ),
        )
        assertEquals(
            1f,
            resolveAutomotivePlayerProgressFraction(
                PlaybackSnapshot(positionMs = 120_000L, durationMs = 100_000L),
            ),
        )
        assertEquals(
            0f,
            resolveAutomotivePlayerProgressFraction(
                PlaybackSnapshot(positionMs = 10_000L, durationMs = 0L),
            ),
        )
    }

    @Test
    fun `seek position resolves only when playback is seekable`() {
        val seekableSnapshot = PlaybackSnapshot(
            durationMs = 100_000L,
            canSeek = true,
        )

        assertEquals(50_000L, resolveAutomotivePlayerSeekPositionMs(0.5f, seekableSnapshot))
        assertEquals(0L, resolveAutomotivePlayerSeekPositionMs(-1f, seekableSnapshot))
        assertEquals(100_000L, resolveAutomotivePlayerSeekPositionMs(2f, seekableSnapshot))
        assertNull(resolveAutomotivePlayerSeekPositionMs(null, seekableSnapshot))
        assertNull(
            resolveAutomotivePlayerSeekPositionMs(
                0.5f,
                seekableSnapshot.copy(canSeek = false),
            ),
        )
        assertNull(
            resolveAutomotivePlayerSeekPositionMs(
                0.5f,
                seekableSnapshot.copy(durationMs = 0L),
            ),
        )
    }

    @Test
    fun `artwork swipe resolves skip intent by threshold and direction`() {
        assertEquals(
            PlayerIntent.SkipNext,
            resolveAutomotiveArtworkSwipeIntent(
                finalOffsetPx = -72f,
                swipeThresholdPx = 72f,
            ),
        )
        assertEquals(
            PlayerIntent.SkipPrevious,
            resolveAutomotiveArtworkSwipeIntent(
                finalOffsetPx = 72f,
                swipeThresholdPx = 72f,
            ),
        )
        assertNull(
            resolveAutomotiveArtworkSwipeIntent(
                finalOffsetPx = -71.9f,
                swipeThresholdPx = 72f,
            ),
        )
        assertNull(
            resolveAutomotiveArtworkSwipeIntent(
                finalOffsetPx = 71.9f,
                swipeThresholdPx = 72f,
            ),
        )
        assertNull(
            resolveAutomotiveArtworkSwipeIntent(
                finalOffsetPx = 100f,
                swipeThresholdPx = 0f,
            ),
        )
    }

    @Test
    fun `artwork drag offset is clamped to visual bounds`() {
        assertEquals(
            40f,
            resolveAutomotiveArtworkDragOffsetPx(
                currentOffsetPx = 25f,
                dragAmountPx = 15f,
                maxVisualOffsetPx = 80f,
            ),
        )
        assertEquals(
            80f,
            resolveAutomotiveArtworkDragOffsetPx(
                currentOffsetPx = 70f,
                dragAmountPx = 20f,
                maxVisualOffsetPx = 80f,
            ),
        )
        assertEquals(
            -80f,
            resolveAutomotiveArtworkDragOffsetPx(
                currentOffsetPx = -70f,
                dragAmountPx = -20f,
                maxVisualOffsetPx = 80f,
            ),
        )
        assertEquals(
            0f,
            resolveAutomotiveArtworkDragOffsetPx(
                currentOffsetPx = 20f,
                dragAmountPx = 10f,
                maxVisualOffsetPx = 0f,
            ),
        )
    }
}
