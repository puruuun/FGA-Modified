package io.github.fate_grand_automata.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper

val overlayType: Int
    get() {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else WindowManager.LayoutParams.TYPE_PHONE
    }

fun Context.dayNightThemed() = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)

/** The launcher and every other overlay dialog use this radius on every device (owner, #613). */
const val OverlayDialogCornerDp = 4

fun showOverlayDialog(context: Context, builder: AlertDialog.Builder.() -> Unit): AlertDialog {
    val alertDialog = AlertDialog.Builder(context.dayNightThemed())
        .apply(builder)
        .create()

    alertDialog.window?.setType(overlayType)
    alertDialog.show()
    alertDialog.applyOverlayShape()

    return alertDialog
}

/**
 * The AppCompat dialog background takes `dialogCornerRadius` from the system theme on API 28+, so the corners were small
 * on one device and large on another. This draws its own background in the dialog surface colour, with the insets that
 * AppCompat's background had, so the size and margins stay as they were.
 */
internal fun AlertDialog.applyOverlayShape() {
    val window = window ?: return
    val insets = Rect().also { window.decorView.background?.getPadding(it) }
    val surface = TypedValue().let {
        if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorBackgroundFloating, it, true) && it.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) it.data
        else if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) 0xFF303030.toInt()
        else Color.WHITE
    }
    val shape = GradientDrawable().apply {
        setColor(surface)
        cornerRadius = OverlayDialogCornerDp * context.resources.displayMetrics.density
    }
    window.setBackgroundDrawable(InsetDrawable(shape, insets.left, insets.top, insets.right, insets.bottom))
}

/**
 * Used with MediaProjection so that we only get landscape images,
 * since the frame size can't be changed during a projection.
 */
fun DisplayMetrics.makeLandscape() = apply {
        // Retrieve images in Landscape
        if (heightPixels > widthPixels) {
            val temp = widthPixels
            widthPixels = heightPixels
            heightPixels = temp
        }
    }