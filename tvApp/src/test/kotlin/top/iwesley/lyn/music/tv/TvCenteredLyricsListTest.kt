package top.iwesley.lyn.music.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvCenteredLyricsListTest {
    @Test
    fun `first lyrics target uses immediate positioning`() {
        assertFalse(
            shouldAnimateTvCenteredLyricsScroll(
                previousTargetIndex = null,
                targetIndex = 0,
                isTargetVisible = true,
            ),
        )
    }

    @Test
    fun `adjacent visible lyrics target uses smooth scrolling`() {
        assertTrue(
            shouldAnimateTvCenteredLyricsScroll(
                previousTargetIndex = 4,
                targetIndex = 5,
                isTargetVisible = true,
            ),
        )
    }

    @Test
    fun `visible lyrics target two lines away uses smooth scrolling`() {
        assertTrue(
            shouldAnimateTvCenteredLyricsScroll(
                previousTargetIndex = 4,
                targetIndex = 6,
                isTargetVisible = true,
            ),
        )
    }

    @Test
    fun `distant lyrics target uses immediate positioning`() {
        assertFalse(
            shouldAnimateTvCenteredLyricsScroll(
                previousTargetIndex = 4,
                targetIndex = 7,
                isTargetVisible = true,
            ),
        )
    }

    @Test
    fun `offscreen lyrics target uses immediate positioning`() {
        assertFalse(
            shouldAnimateTvCenteredLyricsScroll(
                previousTargetIndex = 4,
                targetIndex = 5,
                isTargetVisible = false,
            ),
        )
    }
}
