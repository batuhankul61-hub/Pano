package app.pano.launcher.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Sağdan kayarak gelen panel (Tesla'daki gibi): launcher'dan çıkmadan web uygulamaları
 * (YouTube vb.) panelin içinde çalışır. Android başka uygulamaların penceresini gömmeye izin
 * vermediği için içerik Pano'nun kendi web görünümünde açılır; yerel uygulamalar tam ekran açılır.
 */
object AppSheet {
    data class Page(val title: String, val url: String)

    var page by mutableStateOf<Page?>(null)
        private set

    /** Sağ ekranda Pano'nun kendi "Şimdi çalıyor" ekranı açık mı. Web sayfasıyla aynı anda açılmaz. */
    var music by mutableStateOf(false)
        private set

    /** Sağ ekranda Pano navigasyonunun "Nereye?" ekranı (arama, rota önizleme) açık mı. */
    var nav by mutableStateOf(false)
        private set

    fun open(title: String, url: String) {
        music = false
        nav = false
        page = Page(title, url)
    }

    fun openMusic() {
        page = null
        nav = false
        music = true
    }

    fun openNav() {
        page = null
        music = false
        nav = true
    }

    fun close() {
        page = null
        music = false
        nav = false
    }

    /** Panelde açılabilen web uygulamaları (adada sabit durur). */
    val YouTube = Page("YouTube", "https://m.youtube.com")
}

/** Panelin web görünümü; panel kapanınca yok edilir (ses / video da durur). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SheetWebView(url: String, modifier: Modifier, onReady: (WebView) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // Bağlantılar panelin içinde açılsın, dış tarayıcıya gitmesin.
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                setBackgroundColor(android.graphics.Color.BLACK)
                loadUrl(url)
                onReady(this)
            }
        },
        onRelease = { web ->
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        },
    )
}
