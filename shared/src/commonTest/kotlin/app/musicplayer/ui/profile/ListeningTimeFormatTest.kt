package app.musicplayer.ui.profile

import kotlin.test.*

class ListeningTimeFormatTest {
    @Test
    fun displayUsesWholeMinutesWithoutChangingStoredPrecision() {
        assertEquals("0 мин", formatListeningTime(59999))
        assertEquals("1 мин", formatListeningTime(60000))
        assertEquals("1 ч 1 мин", formatListeningTime(3660999))
        assertEquals("0 мин", formatListeningTime(0))
    }
}
