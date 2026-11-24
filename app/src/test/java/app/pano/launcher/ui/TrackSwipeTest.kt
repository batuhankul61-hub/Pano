package app.pano.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Müzik kartı kaydırma yönü: sola sonraki, sağa önceki, kısa kaydırma hiçbir şey. */
class TrackSwipeTest {
    @Test fun yonVeEsik() {
        assertEquals(TrackSwipe.Next, trackSwipeFor(-120f, 100f))
        assertEquals(TrackSwipe.Next, trackSwipeFor(-100f, 100f))
        assertEquals(TrackSwipe.Previous, trackSwipeFor(150f, 100f))
        assertEquals(TrackSwipe.None, trackSwipeFor(-99f, 100f))
        assertEquals(TrackSwipe.None, trackSwipeFor(60f, 100f))
        assertEquals(TrackSwipe.None, trackSwipeFor(0f, 100f))
    }
}
