package app.pano.launcher.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.pano.launcher.ui.DesignCanvas
import app.pano.launcher.ui.PanoIcons

/**
 * Dock düğmesinin işi: oturum açıksa kapatır; anahtar yoksa ayarlara yönlendirir ([onNeedKey]);
 * mikrofon izni yoksa ister, verilince başlatır.
 */
@Composable
fun rememberAssistantLauncher(onNeedKey: () -> Unit): () -> Unit {
    val context = LocalContext.current
    AssistantKey.load(context)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PanoAssistant.start(context)
        else Toast.makeText(context, "Pano Asistan için mikrofon izni gerekli", Toast.LENGTH_SHORT).show()
    }
    return remember(context) {
        {
            when {
                PanoAssistant.isActive -> PanoAssistant.stop()
                !AssistantKey.hasKey.value -> {
                    Toast.makeText(context, "Önce Pano Asistan için API anahtarını gir", Toast.LENGTH_SHORT).show()
                    onNeedKey()
                }
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                    PanoAssistant.start(context)
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

private val Panel = Color(0xF0141418)
private val Listen = Color(0xFF0A84FF)
private val Think = Color(0xFFBF5AF2)
private val Speak = Color(0xFF30D158)
private val Fail = Color(0xFFFF453A)

/**
 * Asistan açıkken ekranın altında, dock'un üstünde görünen büyük ve sade gösterge: ne yaptığı
 * (dinliyor / düşünüyor / konuşuyor), duyduğu ve cevabı tek satırda, ✕ ile kapatma. Her iki
 * düzenin üstünde tek satırla çizilir; sürüşte de kullanılabilir (yalnızca okunur, büyük ✕).
 */
@Composable
fun AssistantOverlay() {
    val ui by PanoAssistant.ui.collectAsState()
    val visible = ui.phase != AssistantPhase.Idle
    DesignCanvas {
        Box(Modifier.fillMaxSize().padding(bottom = 150.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visible,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 },
            ) {
                Panel(ui)
            }
        }
    }
}

@Composable
private fun Panel(ui: AssistantUi) {
    val (title, color) = when (ui.phase) {
        AssistantPhase.Connecting -> "Bağlanıyor…" to Listen.copy(alpha = 0.6f)
        AssistantPhase.Listening -> "Dinliyorum" to Listen
        AssistantPhase.Thinking -> "Düşünüyorum…" to Think
        AssistantPhase.Speaking -> "Konuşuyor" to Speak
        AssistantPhase.Error -> "Pano Asistan" to Fail
        AssistantPhase.Idle -> "" to Listen
    }
    val line = when {
        ui.phase == AssistantPhase.Error -> ui.error.orEmpty()
        ui.reply.isNotEmpty() && ui.phase != AssistantPhase.Listening -> ui.reply
        ui.heard.isNotEmpty() -> "“${ui.heard}”"
        ui.phase == AssistantPhase.Listening -> "Klima, navigasyon, müzik ya da araç durumu için konuş"
        else -> ""
    }
    Row(
        Modifier.width(980.dp).clip(RoundedCornerShape(40.dp)).background(Panel)
            // Göstergeye dokunmak arkadaki haritaya geçmesin.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Orb(ui, color)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            if (line.isNotEmpty()) {
                Text(
                    line, color = Color.White.copy(alpha = 0.72f), fontSize = 22.sp, lineHeight = 28.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                .clickable(role = Role.Button, onClickLabel = "Asistanı kapat") { PanoAssistant.stop() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(PanoIcons.Close, contentDescription = "Asistanı kapat", tint = Color.White, modifier = Modifier.size(36.dp))
        }
    }
}

/** Duruma göre renk alan, sesle büyüyüp küçülen daire. */
@Composable
private fun Orb(ui: AssistantUi, color: Color) {
    val target = when (ui.phase) {
        AssistantPhase.Listening -> 1f + (ui.level * 8f).coerceAtMost(0.35f)
        AssistantPhase.Speaking -> 1.15f
        AssistantPhase.Thinking -> 0.92f
        else -> 1f
    }
    val s by animateFloatAsState(target, tween(140), label = "asistan seviyesi")
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(104.dp).scale(s).clip(CircleShape).background(color.copy(alpha = 0.28f)))
        Box(Modifier.size(64.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(PanoIcons.Assistant, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
