package app.pano.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Pano'nun kendi ikon seti: Apple SF Symbols havasında ince, yuvarlak uçlu çizgiler.
 * Hepsi 24×24 birimlik bir ızgarada çizildi; renk Icon(tint = …) ile verilir.
 */
object PanoIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun line(name: String, d: String, width: Float = 1.7f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(d),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    private fun solid(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(d),
            fill = SolidColor(Color.Black),
        ).build()

    val Home = line("home", "M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z")

    /** Yandan araç: araç ayarları. */
    val Car = line(
        "car",
        "M4 16v-3l1.8-4.2A2 2 0 0 1 7.6 7.6h8.8a2 2 0 0 1 1.8 1.2L20 13v3a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" +
            "M4.5 13h15M7.5 17v1.5M16.5 17v1.5",
    )

    val Apps = line(
        "apps",
        "M6 4h2.5a2 2 0 0 1 2 2v2.5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M15.5 4H18a2 2 0 0 1 2 2v2.5a2 2 0 0 1-2 2h-2.5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M6 13.5h2.5a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2.5a2 2 0 0 1 2-2z" +
            "M15.5 13.5H18a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2h-2.5a2 2 0 0 1-2-2v-2.5a2 2 0 0 1 2-2z",
    )
    val Navigation = line("navigation", "M12 3.5l6.5 16-6.5-3.6-6.5 3.6z")
    val Music = line("music", "M9 17.5V6.2l10-2v11.3" + circle(6.5f, 17.5f, 2.5f) + circle(16.5f, 15.5f, 2.5f))
    val Phone = line(
        "phone",
        "M6.2 4h2.6l1.6 4.2-2 1.3a11 11 0 0 0 6.1 6.1l1.3-2 4.2 1.6v2.6a2 2 0 0 1-2.2 2A15.8 15.8 0 0 1 4.2 6.2 2 2 0 0 1 6.2 4z",
    )
    val Fan = line(
        "fan",
        circle(12f, 12f, 1.6f) +
            "M12 10.4c-.6-2.8.4-6.4 3-6.4 2.4 0 2.6 3.8-1.6 6.6" +
            "M13.4 12.8c2.7.9 5.3 3.6 4 5.8-1.2 2.1-4.6.4-4.9-4.6" +
            "M10.6 12.8c-2.1 1.9-5.7 2.9-7 .6-1.2-2.1 2-4.1 6.5-1.9",
    )
    /** Mikrofon: sesli asistan. */
    val Mic = line("mic", "M9 6.5a3 3 0 0 1 6 0V12a3 3 0 0 1-6 0zM5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3")

    val Sliders = line("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9" + circle(15f, 7f, 2f) + circle(9f, 17f, 2f))
    val Lock = line("lock", "M7 10.5h10a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6.5a2 2 0 0 1 2-2zM8 10.5V8a4 4 0 0 1 8 0v2.5")
    val Unlock = line("unlock", "M7 10.5h10a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6.5a2 2 0 0 1 2-2zM8 10.5V8a4 4 0 0 1 7.6-1.8")
    val Trunk = line("trunk", "M4 14.5l2-5.5h12l2 5.5V18a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1zM4 14.5h16M12 2.5v4M9.8 4.5L12 2.5l2.2 2")
    val Bolt = line("bolt", "M13 3L5.5 13h6l-1 8L18 11h-6z")
    val Mirror = line("mirror", "M4 10a4 4 0 0 1 4-4h6a5 5 0 0 1 0 10H8a4 4 0 0 1-4-4zM19 11h2")
    val ChevronLeft = line("chevron.left", "M14.5 5l-7 7 7 7", 2f)
    val ChevronRight = line("chevron.right", "M9.5 5l7 7-7 7", 2f)
    val ChevronUp = line("chevron.up", "M5 15l7-7 7 7", 2f)
    val ChevronDown = line("chevron.down", "M5 9l7 7 7-7", 2f)
    val Close = line("xmark", "M6.5 6.5l11 11M17.5 6.5l-11 11", 2f)
    val Message = line("message", "M12 4c4.7 0 8.5 3.1 8.5 7s-3.8 7-8.5 7c-1 0-2-.1-2.9-.4L5 19.5l1.2-3.4C4.5 14.8 3.5 13 3.5 11c0-3.9 3.8-7 8.5-7z")
    val Minus = line("minus", "M5 12h14", 2f)
    val Plus = line("plus", "M5 12h14M12 5v14", 2f)
    val Power = line("power", "M12 3.5v7.5M7.2 6.6a7 7 0 1 0 9.6 0", 2f)
    val AirFace = line("air.face", circle(17f, 5.5f, 2f) + "M17 9v5.5l2 6M3 8.5h8.5M8.5 5.5l3 3-3 3")
    val AirFeet = line("air.feet", circle(17f, 4.5f, 2f) + "M17 8v6l-2.5 6M3 17.5h8.5M8.5 14.5l3 3-3 3")
    val Defrost = line("defrost", "M3.5 16c3-6.5 14-6.5 17 0M8 9.5c-1-1.2 1-2.3 0-3.5M12 8.5c-1-1.2 1-2.3 0-3.5M16 9.5c-1-1.2 1-2.3 0-3.5M5 20h14")
    // Klima anahtar karoları
    val Sync = line(
        "sync",
        "M4.5 12a7.5 7.5 0 0 1 13-5.1M17.5 3.5v3.4h-3.4M19.5 12a7.5 7.5 0 0 1-13 5.1M6.5 20.5v-3.4h3.4",
    )
    val Recirculation = line(
        "recirculation",
        "M3 19.5h18M4.5 19.5V14l3-8h9l3 8v5.5" +
            "M15.2 14.2a3.2 3.2 0 1 0-1.1 2.4M11.9 16.2l2.2.4-.3 2.2",
    )
    val RearDefrost = line(
        "rear.defrost",
        "M5 4h14a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 5 4z" +
            "M8 21c-1-1.2 1-2.3 0-3.5M12 21c-1-1.2 1-2.3 0-3.5M16 21c-1-1.2 1-2.3 0-3.5M7 9h10",
    )
    val Location =line("location", "M20 4L3.5 11l7.2 2.3L13 20.5z")
    val Search = line("search", circle(10.5f, 10.5f, 6.5f) + "M15.3 15.3L20 20", 2f)
    val Dashboard = line(
        "dashboard",
        "M6 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        2f,
    )
    // Hava durumu
    val Sun = line(
        "sun",
        circle(12f, 12f, 4f) + "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
    )
    val Moon = line("moon", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z")
    val Cloud = line("cloud", "M7 18.5h10a4 4 0 0 0 .6-8 5.5 5.5 0 0 0-10.6 1.4A3.3 3.3 0 0 0 7 18.5z")
    val Rain = line("rain", "M7 15.5h10a4 4 0 0 0 .6-8 5.5 5.5 0 0 0-10.6 1.4A3.3 3.3 0 0 0 7 15.5zM8.5 18.5l-1 2.5M12.5 18.5l-1 2.5M16.5 18.5l-1 2.5")
    val Snow = line("snow", "M7 15.5h10a4 4 0 0 0 .6-8 5.5 5.5 0 0 0-10.6 1.4A3.3 3.3 0 0 0 7 15.5zM8 19.5h.01M12 21h.01M16 19.5h.01", 2.2f)
    val Fog = line("fog", "M4 9h16M6 13h12M4 17h16")
    val Storm = line("storm", "M7 15.5h10a4 4 0 0 0 .6-8 5.5 5.5 0 0 0-10.6 1.4A3.3 3.3 0 0 0 7 15.5zM12.5 14l-2 3.5h3l-2 3.5")

    val Seat = line("seat", "M8 3.5c-1 4-1 8 .5 11.5h7.5a2 2 0 0 1 2 2v2.5M8.5 15l-1.5 5.5M11 9h3")

    val Star = solid("star", "M12 2.8l2.75 5.6 6.15.9-4.45 4.35 1.05 6.1L12 16.85l-5.5 2.9 1.05-6.1L3.1 9.3l6.15-.9z")
    val Play = solid("play", "M8 4.8v14.4a1 1 0 0 0 1.5.9l11-7.2a1 1 0 0 0 0-1.8l-11-7.2A1 1 0 0 0 8 4.8z")
    val Pause = solid("pause", "M7 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM14 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Next = solid("next", "M3 6.6v10.8a.8.8 0 0 0 1.2.7l7.8-5.4a.8.8 0 0 0 0-1.4L4.2 5.9a.8.8 0 0 0-1.2.7zM12 6.6v10.8a.8.8 0 0 0 1.2.7l7.8-5.4a.8.8 0 0 0 0-1.4l-7.8-5.4a.8.8 0 0 0-1.2.7z")
    val Previous = solid("previous", "M21 6.6v10.8a.8.8 0 0 1-1.2.7L12 12.7a.8.8 0 0 1 0-1.4l7.8-5.4a.8.8 0 0 1 1.2.7zM12 6.6v10.8a.8.8 0 0 1-1.2.7L3 12.7a.8.8 0 0 1 0-1.4l7.8-5.4a.8.8 0 0 1 1.2.7z")
}
