package app.pano.launcher.ui

import android.Manifest
import android.provider.CallLog
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.phone.PhoneBook
import app.pano.launcher.phone.PhoneCall
import app.pano.launcher.ui.theme.LocalPano

/** Arama düğmesinin yeşili (iOS / CarPlay ile aynı). */
private val CallGreen = Color(0xFF30C552)
private val MissedRed = Color(0xFFE5484D)

/**
 * Pano'nun kendi telefon ekranı: solda son aramalar / kişiler, sağda büyük tuş takımı. Arama
 * aracın telefon bağlantısıyla yapılır ([PhoneCall]); veri Android'in rehber ve arama kayıtlarından
 * gelir ([PhoneBook]). Kayıt yoksa ya da izin verilmediyse boş durum gösterilir.
 */
@Composable
fun PhoneScreen(modifier: Modifier = Modifier) {
    val c = LocalPano.current
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf<List<PhoneBook.Recent>?>(null) }
    var contacts by remember { mutableStateOf<List<PhoneBook.Contact>?>(null) }
    var access by remember { mutableStateOf(PhoneBook.canReadCalls(context) && PhoneBook.canReadContacts(context)) }

    LaunchedEffect(reload) {
        access = PhoneBook.canReadCalls(context) && PhoneBook.canReadContacts(context)
        recents = PhoneBook.recents(context)
        contacts = PhoneBook.contacts(context)
    }

    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload++ }
    // Aranacak numara izin sorulurken bekler; izin verilmezse aracın telefon ekranı numarayla açılır.
    var pendingCall by remember { mutableStateOf<String?>(null) }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingCall?.let { PhoneCall.call(context, it) }
        pendingCall = null
    }
    val call: (String) -> Unit = { n ->
        if (PhoneCall.canCallDirectly(context)) PhoneCall.call(context, n)
        else { pendingCall = n; askCall.launch(Manifest.permission.CALL_PHONE) }
    }

    // Tuş takımına yazılınca liste numarayla eşleşen kişilere daralır.
    val typed = PhoneBook.digits(number)
    val matches = remember(typed, contacts) {
        if (typed.length < 2) emptyList() else contacts.orEmpty().filter { PhoneBook.digits(it.number).contains(typed) }.take(30)
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (typed.length >= 2) {
                Text(
                    "Eşleşen kişiler", color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.height(56.dp).padding(start = 4.dp, top = 16.dp),
                )
            } else {
                Segmented(listOf("Son aramalar", "Kişiler"), tab, { tab = it }, Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                when {
                    !access -> EmptyState(
                        "Rehber ve son aramalar için izin gerekli",
                        "Telefon Bluetooth ile bağlıyken kayıtlar burada görünür.",
                        "İzin ver",
                    ) {
                        askAccess.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG))
                    }
                    typed.length >= 2 -> if (matches.isEmpty()) EmptyState("Eşleşen kişi yok", null) else
                        ContactList(matches, call)
                    tab == 0 -> when {
                        recents == null -> Unit
                        recents!!.isEmpty() -> EmptyState(
                            "Son arama yok",
                            "Telefon Bluetooth ile bağlıyken aramalar burada görünür.",
                        )
                        else -> RecentList(recents!!, call)
                    }
                    else -> when {
                        contacts == null -> Unit
                        contacts!!.isEmpty() -> EmptyState(
                            "Rehber boş",
                            "Telefonun rehberi araca aktarılınca kişiler burada görünür.",
                        )
                        else -> ContactList(contacts!!, call)
                    }
                }
            }
        }
        Dialer(number, { number = it }, { if (number.isNotBlank()) { call(number); number = "" } }, Modifier.width(440.dp).fillMaxHeight())
    }
}

@Composable
private fun EmptyState(title: String, body: String?, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = LocalPano.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body, color = c.secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (action != null && onAction != null) {
            Box(
                Modifier.padding(top = 24.dp).height(64.dp).clip(RoundedCornerShape(32.dp)).background(c.raised)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RecentList(items: List<PhoneBook.Recent>, onCall: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { "${it.date}/${it.number}" }) { r ->
            val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
            val kind = when (r.type) {
                CallLog.Calls.OUTGOING_TYPE -> "Giden"
                CallLog.Calls.INCOMING_TYPE -> "Gelen"
                CallLog.Calls.REJECTED_TYPE -> "Reddedilen"
                else -> if (missed) "Cevapsız" else null
            }
            val time = DateUtils.getRelativeTimeSpanString(
                r.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
            CallRow(
                title = r.name ?: r.number,
                subtitle = listOfNotNull(kind, if (r.name != null) r.number else null, time).joinToString(" · "),
                missed = missed,
                outgoing = r.type == CallLog.Calls.OUTGOING_TYPE,
                showDirection = true,
            ) { onCall(r.number) }
        }
    }
}

@Composable
private fun ContactList(items: List<PhoneBook.Contact>, onCall: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { "${it.id}/${it.number}" }) { p ->
            CallRow(
                title = p.name,
                subtitle = listOfNotNull(p.label, p.number).joinToString(" · "),
                initial = p.name.firstOrNull { it.isLetterOrDigit() }?.uppercase(),
            ) { onCall(p.number) }
        }
    }
}

/** Liste satırı: dokununca arar (sürüşte de büyük dokunma alanı). */
@Composable
private fun CallRow(
    title: String,
    subtitle: String,
    missed: Boolean = false,
    outgoing: Boolean = false,
    showDirection: Boolean = false,
    initial: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalPano.current
    Row(
        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = "Ara", onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
            when {
                showDirection -> Icon(
                    if (outgoing) PanoIcons.CallOut else PanoIcons.CallIn, null,
                    tint = if (missed) MissedRed else c.secondary, modifier = Modifier.size(24.dp),
                )
                initial != null -> Text(initial, color = c.secondary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                else -> Icon(PanoIcons.Phone, null, tint = c.secondary, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(
                title, color = if (missed) MissedRed else c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, color = c.secondary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(PanoIcons.Phone, "Ara", tint = CallGreen, modifier = Modifier.padding(start = 12.dp).size(26.dp))
    }
}

private val KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dialer(number: String, onNumber: (String) -> Unit, onCall: () -> Unit, modifier: Modifier) {
    val c = LocalPano.current
    Column(modifier) {
        // Numara ve silme
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.ifEmpty { "Numara" },
                color = if (number.isEmpty()) c.tertiary else c.text,
                fontSize = if (number.length > 13) 28.sp else 36.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(start = 64.dp),
            )
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .combinedClickable(
                        enabled = number.isNotEmpty(),
                        role = Role.Button,
                        onLongClick = { onNumber("") },
                        onClick = { onNumber(number.dropLast(1)) },
                    )
                    .semantics { contentDescription = "Sil" },
                contentAlignment = Alignment.Center,
            ) {
                if (number.isNotEmpty()) Icon(PanoIcons.Backspace, null, tint = c.secondary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KEYS.chunked(3).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (digit, letters) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(c.raised)
                                .combinedClickable(
                                    role = Role.Button,
                                    // 0'a basılı tutmak "+" yazar (uluslararası numara).
                                    onLongClick = if (digit == "0") ({ onNumber(number + "+") }) else null,
                                    onClick = { if (number.length < 20) onNumber(number + digit) },
                                )
                                .semantics { contentDescription = digit },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(digit, color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Normal)
                            if (letters.isNotEmpty()) {
                                Text(letters, color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(40.dp))
                .background(if (number.isBlank()) CallGreen.copy(alpha = 0.35f) else CallGreen)
                .clickable(enabled = number.isNotBlank(), role = Role.Button, onClickLabel = "Ara", onClick = onCall),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PanoIcons.Phone, null, tint = Color.White, modifier = Modifier.size(30.dp))
            Text("Ara", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
