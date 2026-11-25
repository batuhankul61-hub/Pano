package app.pano.launcher.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pano.launcher.Launch
import app.pano.launcher.messages.IncomingMessage
import app.pano.launcher.messages.MessageHub
import app.pano.launcher.settings.LocalStyle
import app.pano.launcher.ui.theme.Accent
import app.pano.launcher.ui.theme.LocalPano
import kotlinx.coroutines.delay

/** Banner'ın ekranda kalma süresi. */
private const val VISIBLE_MS = 7_000L

/**
 * Yeni WhatsApp / SMS mesajı geldiğinde ekranın üstünde birkaç saniye görünen kart.
 * Dokununca mesajın uygulaması (mümkünse doğrudan sohbet) açılır; kapat düğmesi hemen gizler.
 * Ekranın tamamını kaplayan bir katmandır; düzenlerin en üstüne tek satırla eklenir.
 * [start] / [end]: kartın ortalanacağı alanın yanlardan boşluğu (tasarım tuvali birimi); Ufuk'ta
 * kart araç sütununu ve saati örtmesin diye harita alanına sığdırılır, dar alanda kart daralır.
 */
@Composable
fun MessageBanner(start: Dp = 0.dp, end: Dp = 0.dp) {
    val message by MessageHub.state.collectAsState()
    val enabled = LocalStyle.current.showMessages
    // Kayarak çıkarken içerik boşalmasın diye son mesajı tut.
    var last by remember { mutableStateOf<IncomingMessage?>(null) }
    if (message != null) last = message

    // Her yeni mesajda süre baştan başlar.
    LaunchedEffect(message?.key, message?.text, message?.sender) {
        if (message != null) {
            delay(VISIBLE_MS)
            MessageHub.clear()
        }
    }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        DesignCanvas {
            Box(Modifier.fillMaxSize().padding(start = start, end = end), contentAlignment = Alignment.TopCenter) {
                AnimatedVisibility(
                    visible = enabled && message != null,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    last?.let { BannerCard(it) }
                }
            }
        }
    }
}

@Composable
private fun BannerCard(msg: IncomingMessage) {
    val colors = LocalPano.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier
            .padding(top = 22.dp)
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .height(124.dp)
            .surface(shape, colors.card, colors.isDark, colors.hairline, elevation = 28.dp)
            .clickable(role = Role.Button, onClickLabel = "${msg.appName} uygulamasını aç") {
                open(context, msg)
                MessageHub.clear()
            }
            .padding(start = 22.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SourceIcon(msg.packageName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${msg.appName} · ${msg.sender}",
                color = colors.secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                msg.text,
                color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp,
            )
        }
        RoundIconButton(
            PanoIcons.Close, "Mesajı kapat", { MessageHub.clear() },
            background = colors.raised, tint = colors.text, size = 64.dp, iconSize = 26.dp,
        )
    }
}

/** Uygulamanın kendi ikonu; bulunamazsa genel mesaj simgesi. */
@Composable
private fun SourceIcon(packageName: String) {
    val context = LocalContext.current
    val launcherClass = remember(packageName) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.className
    }
    if (launcherClass != null) {
        AppIcon(packageName, launcherClass, 76.dp)
    } else {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(Accent.Green),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PanoIcons.Message, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/** Önce bildirimin kendi isteği (sohbete gider), olmazsa uygulamanın ana ekranı. */
private fun open(context: Context, msg: IncomingMessage) {
    val pi = msg.contentIntent
    if (pi != null) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                pi.send()
            }
            return
        } catch (_: PendingIntent.CanceledException) {
            // Bildirim iptal edilmiş; uygulamayı açmaya geç.
        }
    }
    val launch = context.packageManager.getLaunchIntentForPackage(msg.packageName)
    if (launch != null) Launch.intent(context, launch)
}
