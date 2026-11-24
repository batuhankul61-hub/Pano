package app.pano.launcher.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSourcesTest {
    @Test
    fun erisimKapaliysaBunuSoyler() {
        assertEquals(
            "Bildirim erişimi kapalı olduğu için mesajlar okunamıyor",
            MessageSources.statusText(hasAccess = false, apps = listOf("WhatsApp")),
        )
    }

    @Test
    fun uygulamaYoksaBluetoothunMesajTasimadiginiSoyler() {
        val text = MessageSources.statusText(hasAccess = true, apps = emptyList())
        assertTrue(text.startsWith("Araçta mesaj uygulaması yok"))
        assertTrue(text.contains("Bluetooth"))
    }

    @Test
    fun uygulamalariSiralar() {
        assertEquals(
            "WhatsApp, Telegram bildirimleri okunuyor",
            MessageSources.statusText(hasAccess = true, apps = listOf("WhatsApp", "Telegram")),
        )
    }
}
