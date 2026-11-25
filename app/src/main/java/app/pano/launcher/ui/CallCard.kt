package app.pano.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.phone.CallHub
import app.pano.launcher.phone.CallInfo
import app.pano.launcher.phone.CallPhase
import app.pano.launcher.phone.formatCallDuration
import app.pano.launcher.phone.label
import app.pano.launcher.ui.theme.LocalPano
import kotlinx.coroutines.delay

/** Cevapla yeşili ve kapat kırmızısı (telefon kartındaki "Ara" ile aynı yeşil). */
private val AnswerGreen = Color(0xFF30C552)
private val HangUpRed = Color(0xFFE5484D)

/** Görüşme kartının genişliği (1600×900 tasarım tuvalinde). */
val CallCardWidth = 640.dp

/**
 * Pano'nun görüşme kartı: kişi (ad yoksa numara), durum ve süre; gelen aramada Cevapla / Reddet,
 * görüşmede Sessiz, Tuşlar, Telefona aktar ve Kapat. Ufuk'ta haritanın üstünde, başka uygulama
 * öndeyken erişilebilirlik katmanında aynı kart çizilir. Ölçüler tasarım tuvaline göre.
 * Yapılamayan eylemin düğmesi gösterilmez (ör. DTMF için Telecom ya da Bluetooth bağlantısı yoksa).
 */
@Composable
fun CallCard(call: CallInfo, modifier: Modifier = Modifier) {
    val c = LocalPano.current
    val shape = RoundedCornerShape(36.dp)
    var keypad by remember(call.phase) { mutableStateOf(false) }
    Column(
        modifier.clip(shape).background(c.card).border(1.dp, c.hairline, shape)
            .padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 28.dp),
    ) {
        Header(call)
        when (call.phase) {
            CallPhase.Incoming -> Row(
                Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PillButton("Reddet", PanoIcons.PhoneDown, HangUpRed, Modifier.weight(1f)) { CallHub.reject() }
                PillButton("Cevapla", PanoIcons.Phone, AnswerGreen, Modifier.weight(1f)) { CallHub.answer() }
            }
            CallPhase.Ended -> Unit
            else -> {
                AnimatedVisibility(keypad && call.canDtmf, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    DtmfPad(Modifier.padding(top = 24.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallAction(
                        if (call.muted) PanoIcons.MicOff else PanoIcons.Mic,
                        if (call.muted) "Sessizde" else "Sessiz", on = call.muted,
                    ) { CallHub.toggleMute() }
                    if (call.canDtmf) CallAction(PanoIcons.Keypad, "Tuşlar", on = keypad) { keypad = !keypad }
                    if (call.canTransfer) {
                        CallAction(
                            PanoIcons.Handset, if (call.audioOnPhone == true) "Araca al" else "Telefona aktar",
                            on = call.audioOnPhone == true,
                        ) { CallHub.transfer() }
                    }
                    CallAction(PanoIcons.PhoneDown, "Kapat", tint = HangUpRed) { CallHub.hangUp() }
                }
            }
        }
    }
}

@Composable
private fun Header(call: CallInfo) {
    val c = LocalPano.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
            val initial = call.name?.firstOrNull { it.isLetter() }?.uppercase()
            if (initial != null) Text(initial, color = c.secondary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            else Icon(PanoIcons.Phone, null, tint = c.secondary, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f).padding(start = 22.dp)) {
            Text(
                call.title, color = c.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val status = listOfNotNull(call.phase.label(), call.subtitleNumber).joinToString(" · ")
                Text(
                    status, color = if (call.phase == CallPhase.Incoming) AnswerGreen else c.secondary,
                    fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                call.activeSince?.let { since -> Duration(since, call.endedAt) }
            }
        }
    }
}

/** Saniyede bir ilerleyen görüşme süresi. */
@Composable
private fun Duration(since: Long, endedAt: Long?) {
    val c = LocalPano.current
    var now by remember { mutableLongStateOf(endedAt ?: SystemClock.elapsedRealtime()) }
    LaunchedEffect(since, endedAt) {
        if (endedAt != null) { now = endedAt; return@LaunchedEffect }
        while (true) { now = SystemClock.elapsedRealtime(); delay(1000L - (now - since) % 1000L) }
    }
    Text(
        "  " + formatCallDuration((now - since) / 1000), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
private fun PillButton(label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(88.dp).clip(RoundedCornerShape(44.dp)).background(color)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Yuvarlak eylem düğmesi ve altında adı (≥ 88 birim dokunma alanı). */
@Composable
private fun CallAction(icon: ImageVector, label: String, on: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val c = LocalPano.current
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bg = when { tint != null -> tint; on -> c.text; else -> c.raised }
        val fg = when { tint != null -> Color.White; on -> c.card; else -> c.text }
        Box(Modifier.size(88.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(34.dp))
        }
        Text(
            label, color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val DTMF_KEYS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

/** Görüşme sırasında tuş gönderme (ör. çağrı merkezi menüsü). Basılan tuşlar üstte görünür. */
@Composable
private fun DtmfPad(modifier: Modifier) {
    val c = LocalPano.current
    var sent by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth()) {
        Text(
            sent.ifEmpty { " " }, color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Clip, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DTMF_KEYS.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(c.raised)
                                .clickable(role = Role.Button) {
                                    sent = (sent + k).takeLast(24)
                                    CallHub.dtmf(k[0])
                                }
                                .semantics { contentDescription = k },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(k, color = c.text, fontSize = 32.sp, fontWeight = FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}
