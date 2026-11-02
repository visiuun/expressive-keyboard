package com.vis.expressivekeyboard.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.Shape
import androidx.graphics.shapes.RoundedPolygon

/** Material 3 Expressive shapes offered for the Enter key. "pill" fills the whole key. */
object KeyShapes {
    val enter: List<Pair<String, String>> = listOf(
        "pill" to "Pill",
        "circle" to "Circle",
        "squircle" to "Squircle",
        "cookie4" to "Cookie 4",
        "cookie6" to "Cookie 6",
        "cookie9" to "Cookie 9",
        "cookie12" to "Cookie 12",
        "sunny" to "Sunny",
        "verysunny" to "Very sunny",
        "clover4" to "Clover 4",
        "clover8" to "Clover 8",
        "flower" to "Flower",
        "softburst" to "Soft burst",
        "burst" to "Burst",
        "puffy" to "Puffy",
        "puffydiamond" to "Puffy diamond",
        "gem" to "Gem",
        "pentagon" to "Pentagon",
        "diamond" to "Diamond",
        "arch" to "Arch",
        "ghost" to "Ghost",
        "heart" to "Heart",
        "pixel" to "Pixel",
        "bun" to "Bun",
    )

    private fun polygon(id: String): RoundedPolygon? = when (id) {
        "cookie4" -> MaterialShapes.Cookie4Sided
        "cookie6" -> MaterialShapes.Cookie6Sided
        "cookie9" -> MaterialShapes.Cookie9Sided
        "cookie12" -> MaterialShapes.Cookie12Sided
        "sunny" -> MaterialShapes.Sunny
        "verysunny" -> MaterialShapes.VerySunny
        "clover4" -> MaterialShapes.Clover4Leaf
        "clover8" -> MaterialShapes.Clover8Leaf
        "flower" -> MaterialShapes.Flower
        "softburst" -> MaterialShapes.SoftBurst
        "burst" -> MaterialShapes.Burst
        "puffy" -> MaterialShapes.Puffy
        "puffydiamond" -> MaterialShapes.PuffyDiamond
        "gem" -> MaterialShapes.Gem
        "pentagon" -> MaterialShapes.Pentagon
        "diamond" -> MaterialShapes.Diamond
        "arch" -> MaterialShapes.Arch
        "ghost" -> MaterialShapes.Ghostish
        "heart" -> MaterialShapes.Heart
        "pixel" -> MaterialShapes.PixelCircle
        "bun" -> MaterialShapes.Bun
        else -> null
    }

    /** Shape for a compact (square) enter key, or null for the full-width pill. */
    @androidx.compose.runtime.Composable
    fun enterShape(id: String): Shape? = when (id) {
        "pill" -> null
        "circle" -> CircleShape
        "squircle" -> RoundedCornerShape(30)
        else -> polygon(id)?.toShape() ?: CircleShape
    }
}
