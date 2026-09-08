package com.betterlyrics.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The dozen icons this app actually uses, as path data.
 *
 * `material-icons-extended` carries every Material icon ever drawn — around 40 MB of
 * generated code in a debug build, which was more than the rest of the app put
 * together. Twelve paths cost nothing.
 */
object AppIcons {

    val PlayArrow by lazy { icon("PlayArrow", "M8,5v14l11,-7z") }

    /** Also the settings section chevron, rotated 90 degrees when the section is open. */
    val ChevronRight by lazy {
        icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    }

    val Pause by lazy { icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }

    val SkipNext by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }

    val SkipPrevious by lazy { icon("SkipPrevious", "M6,6h2v12H6zm3.5,6l8.5,6V6z") }

    val MusicNote by lazy {
        icon(
            "MusicNote",
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 " +
                "4,-1.79 4,-4V7h4V3h-6z",
        )
    }

    val Tune by lazy {
        icon(
            "Tune",
            "M3,17v2h6v-2H3zM3,5v2h10V5H3zm10,16v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zm14," +
                "4v-2H11v2h10zm-6,-4h2V7h4V5h-4V3h-2v6z",
        )
    }

    val Refresh by lazy {
        icon(
            "Refresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.57,8 8,8c3.73,0 " +
                "6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 " +
                "-6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        )
    }

    val Translate by lazy {
        icon(
            "Translate",
            "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1" +
                ".99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.33,9.19 6.79,8h-2c0.63,1.41" +
                " 1.5,2.75 2.61,3.96l-4.24,4.19 1.42,1.42L9,13.5l2.87,2.87 1,-1.3zM18.5,10h-2L12," +
                "22h2l1.12,-3h4.75L21,22h2l-4.5,-12zm-2.62,7l1.62,-4.33L19.12,17h-3.24z",
        )
    }

    val NoteAdd by lazy {
        icon(
            "NoteAdd",
            "M14,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,16h" +
                "-3v3h-2v-3H8v-2h3v-3h2v3h3v2zM13,9V3.5L18.5,9H13z",
        )
    }

    val ArrowUpward by lazy {
        icon("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }

    val ArrowDownward by lazy {
        icon("ArrowDownward", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
    }

    /** Cinema view: album art beside the words. */
    val Cinema by lazy {
        icon(
            "Cinema",
            "M3,5h8v14H3V5zm10,1h8v2h-8V6zm0,4h8v2h-8v-2zm0,4h6v2h-6v-2zm0,4h8v2h-8v-2z",
        )
    }

    /** Popup lyrics: the floating window. */
    val PopupWindow by lazy {
        icon(
            "PopupWindow",
            "M19,11h-8v6h8V11zM3,3h18c1.1,0 2,0.9 2,2v14c0,1.1 -0.9,2 -2,2H3c-1.1,0 -2,-0.9 " +
                "-2,-2V5c0,-1.1 0.9,-2 2,-2zM3,5v14h18V5H3z",
        )
    }

    /** Jump the scroll back to the line that is playing. */
    val CenterFocus by lazy {
        icon(
            "CenterFocus",
            "M5,15H3v4c0,1.1 0.9,2 2,2h4v-2H5V15zM5,5h4V3H5c-1.1,0 -2,0.9 -2,2v4h2V5zM19,3h-4v2h4" +
                "v4h2V5c0,-1.1 -0.9,-2 -2,-2zM19,19h-4v2h4c1.1,0 2,-0.9 2,-2v-4h-2V19zM12,9c-1.66," +
                "0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    /** Move the album-art panel to the other side. */
    val SwapSides by lazy {
        icon(
            "SwapSides",
            "M6.99,11L3,15l3.99,4v-3H14v-2H6.99V11zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z",
        )
    }

    val Copy by lazy {
        icon(
            "Copy",
            "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1" +
                ".1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }

    val VolumeUp by lazy {
        icon(
            "VolumeUp",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2" +
                ".25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01," +
                "-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    val VolumeMute by lazy {
        icon(
            "VolumeMute",
            "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05," +
                "-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0," +
                "-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27L7.73,9H3v6" +
                "h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.9" +
                "5 3.69,-1.81L19.73,21L21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09L12,8.18V4z",
        )
    }

    val Close by lazy {
        icon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 " +
                "19,17.59 13.41,12z",
        )
    }

    val Check by lazy {
        icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.White),
            )
        }.build()
}
