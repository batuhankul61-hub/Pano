package app.pano.launcher.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Live API (`BidiGenerateContent`, v1beta) mesaj biçimi. Ağdan bağımsız, saf JSON
 * kurucu ve ayrıştırıcılar; birim testleri bunları doğrudan dener.
 *
 * Kaynak: Google'ın Live API dokümanı (aistudio.google.com/docs/live-api, api-reference/live),
 * Kasım 2026'da doğrulandı. Alan adları camelCase.
 */
object LiveProtocol {
    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** Google'ın önerdiği Live modeli: doğal ses çıkışı, function calling, dil kendiliğinden seçilir. */
    const val MODEL = "models/gemini-3.8-live"
    const val VOICE = "Kore"

    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 24_000

    /** Anahtar URL'de gider; bu adres asla loglanmamalı. */
    fun url(apiKey: String) = "$ENDPOINT?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"

    fun setup(systemPrompt: String, tools: JSONArray, resumeHandle: String?): String {
        val setup = JSONObject()
            .put("model", MODEL)
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))),
                    ),
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", if (resumeHandle != null) JSONObject().put("handle", resumeHandle) else JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            // Google'ın otomatik konuşma algılaması kapalı: arka plan gürültüsünde (araçta yol/motor sesi,
            // emülatörde oda sesi) konuşmanın başladığını görüp bittiğini görmüyor, cevap vermeden
            // bekleyip kalıyordu. Turun başını ve sonunu Pano bildirir ([TurnDetector], [activityStart]).
            .put(
                "realtimeInputConfig",
                JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)),
            )
        return JSONObject().put("setup", setup).toString()
    }

    fun audio(base64Pcm: String): String = JSONObject()
        .put("realtimeInput", JSONObject().put("audio", JSONObject().put("data", base64Pcm).put("mimeType", "audio/pcm;rate=$INPUT_RATE")))
        .toString()

    /** Kullanıcının konuşması başladı (otomatik algılama kapalıyken). Ardından ses parçaları gelir. */
    fun activityStart(): String = JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString()

    /** Kullanıcının konuşması bitti: Google cevap üretmeye başlar. */
    fun activityEnd(): String = JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString()

    fun audioStreamEnd(): String = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    fun toolResponse(responses: List<ToolResult>): String {
        val arr = JSONArray()
        responses.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("name", r.name).put("response", r.response))
        }
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr)).toString()
    }

    /** Sunucudan gelen tek mesajı olaylara çevirir (bir mesajda birkaç olay olabilir). */
    fun parse(text: String): List<LiveEvent> {
        val o = JSONObject(text)
        val out = ArrayList<LiveEvent>()
        if (o.has("setupComplete")) out += LiveEvent.SetupComplete
        o.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    val inline = p.optJSONObject("inlineData")
                    if (inline != null && inline.optString("mimeType").startsWith("audio/")) {
                        out += LiveEvent.Audio(inline.optString("data"))
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { out += LiveEvent.UserText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { out += LiveEvent.ModelText(it) }
            if (sc.optBoolean("interrupted")) out += LiveEvent.Interrupted
            if (sc.optBoolean("turnComplete")) out += LiveEvent.TurnComplete
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val list = (0 until calls.length()).mapNotNull { i ->
                val c = calls.optJSONObject(i) ?: return@mapNotNull null
                ToolCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject())
            }
            if (list.isNotEmpty()) out += LiveEvent.Tools(list)
        }
        o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            out += LiveEvent.ToolsCancelled((0 until ids.length()).map { ids.optString(it) })
        }
        // Google'ın konuşma algılayıcısı: kullanıcının konuşması başladı / bitti.
        when (o.optJSONObject("voiceActivity")?.optString("type")) {
            "ACTIVITY_START" -> out += LiveEvent.SpeechStarted
            "ACTIVITY_END" -> out += LiveEvent.SpeechEnded
        }
        if (o.has("goAway")) out += LiveEvent.GoAway
        o.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val handle = u.optString("newHandle")
            if (u.optBoolean("resumable") && handle.isNotEmpty()) out += LiveEvent.ResumeHandle(handle)
        }
        o.optJSONObject("usageMetadata")?.let { u ->
            out += LiveEvent.Usage(u.optInt("promptTokenCount"), u.optInt("responseTokenCount"), u.optInt("totalTokenCount"))
        }
        return out
    }
}

data class ToolCall(val id: String, val name: String, val args: JSONObject)

data class ToolResult(val id: String, val name: String, val response: JSONObject)

sealed interface LiveEvent {
    data object SetupComplete : LiveEvent
    data class Audio(val base64Pcm: String) : LiveEvent
    data class UserText(val text: String) : LiveEvent
    data class ModelText(val text: String) : LiveEvent
    data object Interrupted : LiveEvent
    data object TurnComplete : LiveEvent
    data class Tools(val calls: List<ToolCall>) : LiveEvent
    data class ToolsCancelled(val ids: List<String>) : LiveEvent
    data object SpeechStarted : LiveEvent
    data object SpeechEnded : LiveEvent
    data object GoAway : LiveEvent
    data class ResumeHandle(val handle: String) : LiveEvent
    data class Usage(val prompt: Int, val response: Int, val total: Int) : LiveEvent
}
