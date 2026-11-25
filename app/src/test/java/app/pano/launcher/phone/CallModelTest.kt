package app.pano.launcher.phone

import app.pano.launcher.phone.CallScreenText.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallModelTest {
    @Test fun sureBicimi() {
        assertEquals("0:00", formatCallDuration(0))
        assertEquals("1:05", formatCallDuration(65))
        assertEquals("1:02:05", formatCallDuration(3725))
        assertEquals("0:00", formatCallDuration(-4))
    }

    @Test fun baslikAdYoksaNumara() {
        assertEquals("Ayşe", CallInfo("+905551112233", "Ayşe", CallPhase.Active).title)
        assertEquals("+905551112233", CallInfo("+905551112233", null, CallPhase.Active).title)
        assertEquals("Bilinmeyen numara", CallInfo(null, null, CallPhase.Incoming).title)
        assertEquals("+905551112233", CallInfo("+905551112233", "Ayşe", CallPhase.Active).subtitleNumber)
        assertNull(CallInfo("+905551112233", null, CallPhase.Active).subtitleNumber)
    }

    @Test fun kimlikVarsaKimliktenOkunur() {
        val p = CallScreenText.parse(
            listOf(
                Node("com.byd.bluetoothcall:id/tv_name", "Ayşe Yılmaz"),
                Node("com.byd.bluetoothcall:id/tv_number", "0555 111 22 33"),
                Node("com.byd.bluetoothcall:id/tv_time", "02:41"),
                Node("com.byd.bluetoothcall:id/btn_hangup", "Kapat"),
            ),
        )
        assertEquals("Ayşe Yılmaz", p.name)
        assertEquals("0555 111 22 33", p.number)
        assertEquals(161L, p.durationSec)
        assertEquals(CallPhase.Active, p.phase)
    }

    @Test fun kimlikYoksaBicimdenTahmin() {
        val p = CallScreenText.parse(
            listOf(Node(null, "Gelen arama"), Node(null, "Mehmet"), Node(null, "+90 532 000 00 00"), Node(null, "Cevapla"), Node(null, "Reddet")),
        )
        assertEquals("Mehmet", p.name)
        assertEquals("+90 532 000 00 00", p.number)
        assertEquals(CallPhase.Incoming, p.phase)
        assertNull(p.durationSec)
    }

    @Test fun dugmeYazilariAdSayilmaz() {
        val p = CallScreenText.parse(listOf(Node(null, "Aranıyor…"), Node(null, "05321234567"), Node(null, "Sessiz"), Node(null, "Kapat")))
        assertNull(p.name)
        assertEquals(CallPhase.Dialing, p.phase)
        assertEquals("05321234567", p.number)
    }

    @Test fun durumSozcukleri() {
        assertEquals(CallPhase.Incoming, CallScreenText.phaseOf("来电"))
        assertEquals(CallPhase.Active, CallScreenText.phaseOf("通话中"))
        assertEquals(CallPhase.Ended, CallScreenText.phaseOf("Görüşme bitti"))
        assertEquals(CallPhase.Dialing, CallScreenText.phaseOf("Calling…"))
        assertNull(CallScreenText.phaseOf("Ayşe"))
    }

    @Test fun sureVeNumaraAyrimi() {
        assertTrue(CallScreenText.isDuration("1:02:05"))
        assertFalse(CallScreenText.isNumber("12:30"))
        assertTrue(CallScreenText.isNumber("112"))
        assertFalse(CallScreenText.isNumber("12"))
        assertEquals(3725L, CallScreenText.durationSeconds("1:02:05"))
    }

    @Test fun durumKimligiAdSanilmaz() {
        // Android çeviricisinde durum yazısının kimliği de "contact" içeriyor.
        val p = CallScreenText.parse(
            listOf(
                Node("com.android.dialer:id/contactgrid_status_text", "Calling…"),
                Node("com.android.dialer:id/contactgrid_contact_name", "05559876543"),
                Node(null, "Mute"), Node(null, "Add call"), Node(null, "Hold call"),
            ),
        )
        assertEquals(CallPhase.Dialing, p.phase)
        assertEquals("05559876543", p.number)
        // Ad kimlikten okunamadı, yazılardan birden çok aday var: ad uydurulmaz.
        assertNull(p.name)
    }
}
