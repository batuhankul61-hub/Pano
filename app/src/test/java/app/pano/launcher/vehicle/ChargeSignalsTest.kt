package app.pano.launcher.vehicle

import app.pano.launcher.ui.durationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSignalsTest {
    private val names = listOf(
        "CHARGING_GUN_CONNECT_STATE",
        "CHARGING_GUN_LOCK_STATE",
        "CHARGING_CHARGER_WORK_STATE",
        "CHARGING_CAP_STATE",
        "CHARGING_GUN_TYPE",
        "CHARGING_POWER",
        "CHARGING_MAX_POWER",
        "CHARGING_VOLTAGE",
        "CHARGING_CURRENT",
        "CHARGING_REST_TIME_HOUR",
        "CHARGING_REST_TIME_MINUTE",
        "CHARGING_TARGET_SOC",
        "CHARGING_TARGET_SOC_SET",
        "CHARGING_DISCHARGE_POWER",
        "CHARGING_BOOK_CHARGE_STATE",
    )

    @Test fun picksExpectedFields() {
        val p = ChargeFields.pick(names)
        assertEquals("CHARGING_GUN_CONNECT_STATE", p.plugged)
        assertEquals("CHARGING_CHARGER_WORK_STATE", p.state)
        assertEquals("CHARGING_GUN_TYPE", p.kind)
        assertEquals("CHARGING_POWER", p.power)
        assertEquals("CHARGING_VOLTAGE", p.voltage)
        assertEquals("CHARGING_CURRENT", p.current)
        assertEquals("CHARGING_REST_TIME_HOUR", p.restHour)
        assertEquals("CHARGING_REST_TIME_MINUTE", p.restMinute)
        assertNull(p.restTotal)
        assertEquals("CHARGING_TARGET_SOC", p.target)
    }

    @Test fun emptyWhenNothingMatches() {
        assertTrue(ChargeFields.pick(listOf("SPEED_AUTO_SPEED", "AC_TEMP_OUT")).isEmpty)
    }

    @Test fun singleRemainingField() {
        val p = ChargeFields.pick(listOf("CHARGE_REMAIN_TIME"))
        assertEquals("CHARGE_REMAIN_TIME", p.restTotal)
        assertNull(p.restHour)
    }

    private val constants = mapOf(
        "CHARGING_GUN_STATE_NONE" to 0,
        "CHARGING_GUN_STATE_CONNECTED_AC" to 1,
        "CHARGING_GUN_STATE_CONNECTED_DC" to 2,
        "CHARGER_WORK_STATE_CHARGING" to 3,
        "CHARGER_WORK_STATE_FINISH" to 4,
        "CHARGER_WORK_STATE_STOP" to 5,
    )

    @Test fun gunCodesFromConstantNames() {
        val none = ChargeCodes.meaning(0, constants, listOf("GUN"))!!
        assertEquals(false, none.plugged)
        val dc = ChargeCodes.meaning(2, constants, listOf("GUN"))!!
        assertEquals(true, dc.plugged)
        assertEquals(ChargeKind.DC, dc.kind)
        assertNull(dc.charging)
        assertEquals(ChargeKind.AC, ChargeCodes.meaning(1, constants, listOf("GUN"))!!.kind)
    }

    @Test fun stateCodesFromConstantNames() {
        assertEquals(true, ChargeCodes.meaning(3, constants, listOf("CHARG"))!!.charging)
        val done = ChargeCodes.meaning(4, constants, listOf("CHARG"))!!
        assertEquals(true, done.finished)
        assertEquals(false, done.charging)
        assertEquals(false, ChargeCodes.meaning(5, constants, listOf("CHARG"))!!.charging)
        // Sabiti olmayan kodun anlamı uydurulmaz.
        assertNull(ChargeCodes.meaning(9, constants, listOf("CHARG")))
    }

    @Test fun scales() {
        assertEquals(7.2f, ChargeScale.powerKw(7.2, null))
        assertEquals(7.2f, ChargeScale.powerKw(null, 7200))
        assertEquals(11f, ChargeScale.powerKw(0.0, 11))
        assertNull(ChargeScale.powerKw(null, 65534 * 100))
        assertEquals(14 * 60 + 29, ChargeScale.minutes(null, 14, 29))
        assertEquals(95, ChargeScale.minutes(95, null, null))
        assertNull(ChargeScale.minutes(null, 255, 255))
        assertNull(ChargeScale.target(255))
        assertEquals(80, ChargeScale.target(80))
    }

    @Test fun activeState() {
        assertFalse(ChargeState().isActive)
        assertTrue(ChargeState(plugged = true).isActive)
        assertTrue(ChargeState(powerKw = 3.5f).isActive)
        assertFalse(ChargeState(plugged = false, charging = false, powerKw = 0.1f).isActive)
    }

    @Test fun duration() {
        assertEquals("14 sa 29 dk", durationText(869))
        assertEquals("45 dk", durationText(45))
        assertEquals("2 sa", durationText(120))
    }
}
