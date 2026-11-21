package io.github.fate_grand_automata.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.util.OverlayDialogCornerDp
import io.github.fate_grand_automata.util.showOverlayDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #613: the overlay dialogs have the same small corners whatever `dialogCornerRadius` the system theme has. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM], qualifiers = "w800dp-h360dp-xhdpi")
class OverlayDialogShapeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun radiusAndInsets(base: Context): Triple<Float, Rect, Float> {
        val themed = ContextThemeWrapper(base, io.github.fate_grand_automata.R.style.LargeDialogCornerTheme)
        val asked = themed.theme.obtainStyledAttributes(intArrayOf(android.R.attr.dialogCornerRadius)).use { it.getDimension(0, 0f) }
        assertEquals("the test theme must ask for large corners", 28f * base.resources.displayMetrics.density, asked, 0.5f)
        val dialog = showOverlayDialog(themed) { setMessage("m") }
        val background = dialog.window!!.decorView.background
        assertTrue(background is InsetDrawable)
        val shape = (background as InsetDrawable).drawable as GradientDrawable
        val insets = Rect().also { background.getPadding(it) }
        return Triple(shape.cornerRadius, insets, base.resources.displayMetrics.density)
    }

    @Test
    fun `corner radius is 4dp even when the theme asks for large corners`() {
        val (radius, _, density) = radiusAndInsets(context)
        assertEquals(OverlayDialogCornerDp * density, radius, 0.01f)
        assertEquals(4f * density, radius, 0.01f)
    }

    @Test
    fun `the background keeps the insets of the AppCompat dialog`() {
        val (_, insets, _) = radiusAndInsets(context)
        assertTrue("insets $insets", insets.left > 0 && insets.top > 0 && insets.right > 0 && insets.bottom > 0)
    }
}
