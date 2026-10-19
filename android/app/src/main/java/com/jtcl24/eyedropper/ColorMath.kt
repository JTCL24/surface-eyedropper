package com.jtcl24.eyedropper

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Rgb(val r: Int, val g: Int, val b: Int) {
    val css get() = "rgb($r, $g, $b)"
    val hex get() = "#%02X%02X%02X".format(r, g, b)
}

data class ColorName(val name: String, val match: String)

object ColorMath {
    /** 8-bit sRGB → linear light */
    val S2L = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun lin2srgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    fun to8(v: Float) = (lin2srgb(v) * 255f).roundToInt()

    private fun toLab(r8: Int, g8: Int, b8: Int): FloatArray {
        val r = S2L[r8]; val g = S2L[g8]; val b = S2L[b8]
        fun f(t: Float) = if (t > 216f / 24389f) cbrt(t) else (24389f / 27f * t + 16f) / 116f
        val x = f((0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f)
        val y = f(0.2126f * r + 0.7152f * g + 0.0722f * b)
        val z = f((0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f)
        return floatArrayOf(116f * y - 16f, 500f * (x - y), 200f * (y - z))
    }

    private class Named(val name: String, val lab: FloatArray)

    private val NAMES: List<Named> = (
        "Black:000000,Dim Gray:696969,Gray:808080,Dark Gray:A9A9A9,Silver:C0C0C0,Light Gray:D3D3D3,Gainsboro:DCDCDC," +
        "White Smoke:F5F5F5,White:FFFFFF,Snow:FFFAFA,Ivory:FFFFF0,Floral White:FFFAF0,Seashell:FFF5EE,Linen:FAF0E6," +
        "Old Lace:FDF5E6,Antique White:FAEBD7,Beige:F5F5DC,Cornsilk:FFF8DC,Lemon Chiffon:FFFACD,Light Yellow:FFFFE0," +
        "Papaya Whip:FFEFD5,Blanched Almond:FFEBCD,Bisque:FFE4C4,Wheat:F5DEB3,Moccasin:FFE4B5,Navajo White:FFDEAD," +
        "Peach Puff:FFDAB9,Misty Rose:FFE4E1,Lavender Blush:FFF0F5,Ghost White:F8F8FF,Alice Blue:F0F8FF,Azure:F0FFFF," +
        "Mint Cream:F5FFFA,Honeydew:F0FFF0,Lavender:E6E6FA,Light Slate Gray:778899,Slate Gray:708090,Dark Slate Gray:2F4F4F," +
        "Maroon:800000,Dark Red:8B0000,Brown:A52A2A,Firebrick:B22222,Crimson:DC143C,Red:FF0000,Tomato:FF6347,Coral:FF7F50," +
        "Indian Red:CD5C5C,Light Coral:F08080,Dark Salmon:E9967A,Salmon:FA8072,Light Salmon:FFA07A,Orange Red:FF4500," +
        "Dark Orange:FF8C00,Orange:FFA500,Gold:FFD700,Dark Goldenrod:B8860B,Goldenrod:DAA520,Pale Goldenrod:EEE8AA," +
        "Dark Khaki:BDB76B,Khaki:F0E68C,Olive:808000,Yellow:FFFF00,Yellow Green:9ACD32,Dark Olive Green:556B2F," +
        "Olive Drab:6B8E23,Lawn Green:7CFC00,Chartreuse:7FFF00,Green Yellow:ADFF2F,Dark Green:006400,Green:008000," +
        "Forest Green:228B22,Lime:00FF00,Lime Green:32CD32,Light Green:90EE90,Pale Green:98FB98,Dark Sea Green:8FBC8F," +
        "Medium Spring Green:00FA9A,Spring Green:00FF7F,Sea Green:2E8B57,Medium Aquamarine:66CDAA,Medium Sea Green:3CB371," +
        "Light Sea Green:20B2AA,Teal:008080,Dark Cyan:008B8B,Cyan:00FFFF,Light Cyan:E0FFFF,Dark Turquoise:00CED1," +
        "Turquoise:40E0D0,Medium Turquoise:48D1CC,Pale Turquoise:AFEEEE,Aquamarine:7FFFD4,Powder Blue:B0E0E6," +
        "Cadet Blue:5F9EA0,Steel Blue:4682B4,Cornflower Blue:6495ED,Deep Sky Blue:00BFFF,Dodger Blue:1E90FF," +
        "Light Blue:ADD8E6,Sky Blue:87CEEB,Light Sky Blue:87CEFA,Midnight Blue:191970,Navy:000080,Dark Blue:00008B," +
        "Medium Blue:0000CD,Blue:0000FF,Royal Blue:4169E1,Light Steel Blue:B0C4DE,Blue Violet:8A2BE2,Indigo:4B0082," +
        "Dark Slate Blue:483D8B,Slate Blue:6A5ACD,Medium Slate Blue:7B68EE,Medium Purple:9370DB,Dark Magenta:8B008B," +
        "Dark Violet:9400D3,Dark Orchid:9932CC,Medium Orchid:BA55D3,Purple:800080,Thistle:D8BFD8,Plum:DDA0DD," +
        "Violet:EE82EE,Magenta:FF00FF,Orchid:DA70D6,Medium Violet Red:C71585,Pale Violet Red:DB7093,Deep Pink:FF1493," +
        "Hot Pink:FF69B4,Light Pink:FFB6C1,Pink:FFC0CB,Rosy Brown:BC8F8F,Sandy Brown:F4A460,Tan:D2B48C," +
        "Burlywood:DEB887,Peru:CD853F,Chocolate:D2691E,Sienna:A0522D,Saddle Brown:8B4513,Rebecca Purple:663399"
    ).split(',').map {
        val (name, hex) = it.split(':')
        val n = hex.toInt(16)
        Named(name, toLab(n shr 16, (n shr 8) and 255, n and 255))
    }

    /** Nearest CSS named colour by CIELAB ΔE; falls back to a description when nothing is close. */
    fun name(c: Rgb): ColorName {
        val lab = toLab(c.r, c.g, c.b)
        var best = NAMES[0]
        var bestD = Float.MAX_VALUE
        for (n in NAMES) {
            val dl = lab[0] - n.lab[0]; val da = lab[1] - n.lab[1]; val db = lab[2] - n.lab[2]
            val d = dl * dl + da * da + db * db
            if (d < bestD) { bestD = d; best = n }
        }
        val dE = sqrt(bestD)
        return when {
            dE < 2.3f -> ColorName(best.name, "exact")
            dE < 8f -> ColorName(best.name, "close")
            else -> ColorName(describe(c), "≈ ${best.name}")
        }
    }

    private fun describe(c: Rgb): String {
        val r = c.r / 255f; val g = c.g / 255f; val b = c.b / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f; val d = mx - mn
        val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        if (s < 0.12f || d < 0.04f) return when {
            l < 0.12f -> "Black"; l > 0.92f -> "White"; l < 0.35f -> "Dark gray"; l > 0.7f -> "Light gray"; else -> "Gray"
        }
        var h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h = (h * 60f + 360f) % 360f
        var hue = when {
            h < 12 -> "red"; h < 40 -> "orange"; h < 62 -> "yellow"; h < 90 -> "yellow-green"; h < 150 -> "green"
            h < 190 -> "teal"; h < 250 -> "blue"; h < 280 -> "violet"; h < 320 -> "purple"; h < 345 -> "pink"; else -> "red"
        }
        if (l < 0.42f && (hue == "orange" || hue == "red" && s < 0.6f)) hue = "brown"
        if (l < 0.4f && hue == "yellow") hue = "olive"
        val tone = when { l < 0.22f -> "very dark "; l < 0.38f -> "dark "; l > 0.82f -> "pale "; l > 0.64f -> "light "; else -> "" }
        val sat = if (s < 0.3f) "muted " else if (s > 0.85f && l > 0.38f && l < 0.64f) "vivid " else ""
        return (tone + sat + hue).replaceFirstChar { it.uppercase() }
    }
}
