package app.pano.launcher.assistant

import app.pano.launcher.navigation.Shortcut
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Araç çağrısının doğru katmana, doğru değerlerle gittiğini dener (sahte katmanla). */
class AssistantToolsTest {

    private class FakeBackend : AssistantBackend {
        val calls = mutableListOf<String>()
        var climate: ClimateChange? = null
        override suspend fun climate(change: ClimateChange) = ok("climate").also { climate = change }
        override suspend fun navigateTo(query: String, start: Boolean) = ok("nav:$query:$start")
        override suspend fun navigateToSaved(shortcut: Shortcut, start: Boolean) = ok("saved:$shortcut:$start")
        override suspend fun stopNavigation() = ok("stopNav")
        override suspend fun media(action: MediaAction) = ok("media:$action")
        override suspend fun openApp(name: String) = ok("app:$name")
        override suspend fun vehicleStatus() = ok("vehicle")
        override suspend fun weather() = ok("weather")
        override suspend fun volume(action: VolumeAction, percent: Int?, steps: Int) = ok("volume:$action:$percent:$steps")
        override suspend fun location() = ok("location")
        override suspend fun phoneCall(target: String, confirm: Boolean) = ok("call:$target:$confirm")
        private fun ok(s: String): JSONObject { calls += s; return JSONObject().put("ok", s) }
    }

    private fun call(name: String, args: String = "{}", b: FakeBackend = FakeBackend()) =
        b to runBlocking { AssistantTools.run(ToolCall("1", name, JSONObject(args)), b) }

    @Test fun climateArgsParsed() {
        val (b, r) = call("set_climate", """{"temperature":21.5,"ac":false,"fan":3.0,"auto":"true"}""")
        assertEquals(listOf("climate"), b.calls)
        assertEquals(ClimateChange(temperature = 21.5f, ac = false, fan = 3, auto = true), b.climate)
        assertFalse(r.has("error"))
    }

    @Test fun emptyClimateIsError() {
        val (b, r) = call("set_climate")
        assertTrue(b.calls.isEmpty())
        assertTrue(r.has("error"))
    }

    @Test fun navigationDefaultsToStart() {
        assertEquals(listOf("nav:Kadıköy:true"), call("navigate_to", """{"destination":" Kadıköy "}""").first.calls)
        assertEquals(listOf("nav:Ev Kadıköy:false"), call("navigate_to", """{"destination":"Ev Kadıköy","start":false}""").first.calls)
        assertTrue(call("navigate_to", """{"destination":""}""").second.has("error"))
        assertEquals(listOf("stopNav"), call("stop_navigation").first.calls)
    }

    @Test fun homeAndWorkUseSavedPlaces() {
        assertEquals(listOf("saved:Home:false"), call("navigate_to", """{"destination":"Ev","start":false}""").first.calls)
        assertEquals(listOf("saved:Home:true"), call("navigate_to", """{"destination":"evime git"}""").first.calls)
        assertEquals(listOf("saved:Work:true"), call("navigate_to", """{"destination":"İŞE"}""").first.calls)
        assertEquals(listOf("saved:Work:true"), call("navigate_to", """{"destination":"iş yerime."}""").first.calls)
        assertEquals(listOf("saved:Work:true"), call("navigate_to", """{"destination":"ofis"}""").first.calls)
    }

    @Test fun savedShortcutOnlyForWholeWord() {
        assertEquals(Shortcut.Home, AssistantTools.savedShortcut("Eve"))
        assertEquals(Shortcut.Work, AssistantTools.savedShortcut("işyerim"))
        assertNull(AssistantTools.savedShortcut("Evka 3"))
        assertNull(AssistantTools.savedShortcut("İş Bankası"))
        assertNull(AssistantTools.savedShortcut("en yakın benzinlik"))
    }

    @Test fun mediaActions() {
        listOf("play" to MediaAction.Play, "pause" to MediaAction.Pause, "NEXT" to MediaAction.Next, "previous" to MediaAction.Previous, "status" to MediaAction.Status)
            .forEach { (arg, action) -> assertEquals(listOf("media:$action"), call("media_control", """{"action":"$arg"}""").first.calls) }
        assertTrue(call("media_control", """{"action":"louder"}""").second.has("error"))
    }

    @Test fun otherTools() {
        assertEquals(listOf("app:Spotify"), call("open_app", """{"name":"Spotify"}""").first.calls)
        assertEquals(listOf("vehicle"), call("vehicle_status").first.calls)
        assertEquals(listOf("weather"), call("weather").first.calls)
    }

    @Test fun volumeArgsParsed() {
        assertEquals(listOf("volume:Up:null:2"), call("set_volume", """{"action":"up"}""").first.calls)
        assertEquals(listOf("volume:Down:null:5"), call("set_volume", """{"action":"DOWN","steps":5.0}""").first.calls)
        assertEquals(listOf("volume:Up:null:10"), call("set_volume", """{"action":"up","steps":40}""").first.calls)
        assertEquals(listOf("volume:Set:100:2"), call("set_volume", """{"action":"set","percent":140}""").first.calls)
        assertEquals(listOf("volume:Mute:null:2"), call("set_volume", """{"action":"mute"}""").first.calls)
        // set için yüzde şart; bilinmeyen işlem hata
        call("set_volume", """{"action":"set"}""").let { (b, r) -> assertTrue(b.calls.isEmpty()); assertTrue(r.has("error")) }
        call("set_volume", """{"action":"louder"}""").let { (b, r) -> assertTrue(b.calls.isEmpty()); assertTrue(r.has("error")) }
        assertEquals(listOf("location"), call("current_location").first.calls)
    }

    @Test fun unknownAndPhysicalToolsRejected() {
        listOf("lock_doors", "open_trunk", "fold_mirrors", "open_window").forEach {
            val (b, r) = call(it)
            assertTrue(b.calls.isEmpty())
            assertTrue(r.has("error"))
        }
        val names = (0 until AssistantTools.declarations().length()).map { AssistantTools.declarations().getJSONObject(it).getString("name") }
        assertEquals(listOf("set_climate", "navigate_to", "stop_navigation", "media_control", "open_app", "vehicle_status", "weather", "set_volume", "phone_call", "current_location"), names)
    }

    @Test fun phoneCallDefaultsToUnconfirmed() {
        val (b, _) = call("phone_call", """{"target":"Ahmet"}""")
        assertEquals(listOf("call:Ahmet:false"), b.calls)
        val (b2, _) = call("phone_call", """{"target":"0555","confirm":true}""")
        assertEquals(listOf("call:0555:true"), b2.calls)
        val (b3, r3) = call("phone_call", """{"target":" "}""")
        assertTrue(b3.calls.isEmpty())
        assertTrue(r3.has("error"))
    }

    @Test fun appMatcher() {
        val apps = listOf("Spotify" to "com.spotify.music", "YouTube Music" to "com.google.android.apps.youtube.music", "YouTube" to "com.google.android.youtube", "Müzik" to "com.byd.mediacenter")
        assertEquals("Spotify", AppMatcher.best("spotify", apps)?.first)
        assertEquals("YouTube", AppMatcher.best("youtube", apps)?.first)
        assertEquals("YouTube Music", AppMatcher.best("youtube music", apps)?.first)
        assertEquals("Müzik", AppMatcher.best("muzik", apps)?.first)
        assertNull(AppMatcher.best("waze", apps))
    }

    @Test fun protocolRoundTrip() {
        val setup = JSONObject(LiveProtocol.setup("talimat", AssistantTools.declarations(), null)).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals("AUDIO", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals(10, setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").length())

        val events = LiveProtocol.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}}]},"outputTranscription":{"text":"Tamam"},"turnComplete":true}}""",
        )
        assertEquals(listOf(LiveEvent.Audio("AAA="), LiveEvent.ModelText("Tamam"), LiveEvent.TurnComplete), events)

        val tool = LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"a1","name":"weather","args":{}}]}}""").single() as LiveEvent.Tools
        assertEquals("weather", tool.calls.single().name)

        val resp = JSONObject(LiveProtocol.toolResponse(listOf(ToolResult("a1", "weather", JSONObject().put("result", 1)))))
        assertEquals("a1", resp.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0).getString("id"))
        assertEquals(listOf(LiveEvent.SetupComplete), LiveProtocol.parse("""{"setupComplete":{}}"""))
        assertEquals(listOf(LiveEvent.ResumeHandle("h")), LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"h","resumable":true}}"""))
        assertEquals(listOf(LiveEvent.SpeechStarted), LiveProtocol.parse("""{"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"2.560s"}}"""))
        assertEquals(listOf(LiveEvent.SpeechEnded), LiveProtocol.parse("""{"voiceActivity":{"type":"ACTIVITY_END"}}"""))
        // Google'ın otomatik konuşma algılaması kapalı; turun başını ve sonunu Pano bildirir.
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertTrue(vad.getBoolean("disabled"))
        assertTrue(JSONObject(LiveProtocol.activityStart()).getJSONObject("realtimeInput").has("activityStart"))
        assertTrue(JSONObject(LiveProtocol.activityEnd()).getJSONObject("realtimeInput").has("activityEnd"))
    }
}
