package com.museum.reader.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.museum.reader.ui.theme.LightColors
import com.museum.reader.ui.theme.DarkColors
import com.museum.reader.ui.theme.prominentCardSubtitleColor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    @Test fun importantTextAndGraphicsMeetContrastInBothThemes() {
        val measurements = JSONArray()
        var theme = ""
        fun verify(role: String, foreground: Color, background: Color, minimum: Double = 4.5) {
            val ratio = ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
            measurements.put(JSONObject().put("theme", theme).put("role", role)
                .put("foregroundArgb", Integer.toHexString(foreground.toArgb()))
                .put("backgroundArgb", Integer.toHexString(background.toArgb()))
                .put("ratio", ratio).put("minimum", minimum))
            assertTrue("$theme $role contrast=$ratio", ratio >= minimum)
        }
        for (colors in listOf(LightColors, DarkColors)) {
            theme = if (colors === LightColors) "light" else "dark"
            val textPairs = mapOf(
                "body" to (colors.onSurface to colors.surface),
                "background" to (colors.onBackground to colors.background),
                "secondary text" to (colors.onSurfaceVariant to colors.surfaceVariant),
                "primary button" to (colors.onPrimary to colors.primary),
                "selected sentence" to (colors.onPrimaryContainer to colors.primaryContainer),
                "search highlight" to (colors.onTertiaryContainer to colors.tertiaryContainer),
                "prominent card subtitle" to (colors.prominentCardSubtitleColor.compositeOver(colors.primary) to colors.primary),
            )
            for ((role, pair) in textPairs) {
                verify(role, pair.first, pair.second)
            }
            verify("key action icon", colors.primary, colors.surface, 3.0)
            val ordinarySurfaces = mapOf(
                "background" to colors.background, "surface" to colors.surface,
                "variant" to colors.surfaceVariant, "low container" to colors.surfaceContainerLow,
                "container" to colors.surfaceContainer, "high container" to colors.surfaceContainerHigh,
                "highest container" to colors.surfaceContainerHighest,
                "selected card" to colors.primaryContainer,
                "cloud card" to colors.primaryContainer.copy(alpha = 0.62f).compositeOver(colors.background),
                "help card" to colors.primaryContainer.copy(alpha = 0.55f).compositeOver(colors.background),
                "range sentence" to colors.primaryContainer.copy(alpha = 0.45f).compositeOver(colors.background),
            )
            for ((backgroundRole, background) in ordinarySurfaces) {
                for ((foregroundRole, foreground) in mapOf(
                    "body" to colors.onSurface, "secondary text" to colors.onSurfaceVariant,
                    "action text" to colors.primary,
                )) {
                    verify("$foregroundRole on $backgroundRole", foreground, background)
                }
            }
            for (background in listOf(colors.background, colors.surface, colors.surfaceContainerHigh)) {
                verify("error text", colors.error, background)
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "theme-contrast.json").writeText(JSONObject()
            .put("passed", true).put("measurements", measurements).put("timestampMillis", System.currentTimeMillis())
            .put("scope", "Resolved app theme colors and actual alpha composites; combine with screen review for A14-04.")
            .toString(2))
    }
}
