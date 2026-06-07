package top.iwesley.lyn.music.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerArtworkStyleTest {

    @Test
    fun `player artwork style parser accepts known names`() {
        assertEquals(PlayerArtworkStyle.VINYL, playerArtworkStyleOrDefault("VINYL"))
        assertEquals(PlayerArtworkStyle.HALF_RECORD, playerArtworkStyleOrDefault("HALF_RECORD"))
        assertEquals(PlayerArtworkStyle.MINIMAL_COVER, playerArtworkStyleOrDefault("MINIMAL_COVER"))
    }

    @Test
    fun `player artwork style parser falls back to vinyl for missing or invalid names`() {
        assertEquals(PlayerArtworkStyle.VINYL, playerArtworkStyleOrDefault(null))
        assertEquals(PlayerArtworkStyle.VINYL, playerArtworkStyleOrDefault(""))
        assertEquals(PlayerArtworkStyle.VINYL, playerArtworkStyleOrDefault("unknown"))
    }
}
