package io.github.fate_grand_automata.ui.log

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.outsideNames
import java.io.File
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.net.URI
import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.capture.outsideFixture
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod

internal fun resourceLogFixture(keys: Set<String>): List<CaptureLogItem> {
    fun counts(type: Int) = keys.filter { it.substringBefore(':').toInt() == type }
        .associate { it.substringAfter(':').toLong() to 1L }
    val period = OutsidePeriod("synthetic", 1).add(ChangeSource.of("present/receive"),
        AccountDelta(items = counts(2), svts = counts(LogResource.SVT), commandCodes = counts(LogResource.COMMAND_CODE)), 2)
    return listOf(CaptureLogItem(outside = outsideFixture().copy(period = period)))
}

/** Robolectric runs on Windows: decode local files explicitly, and prohibit every network request. */
internal fun localIconLoader(context: Context) = ImageLoader.Builder(context).components {
    add(object : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val bitmap = runCatching {
                val uri = URI(chain.request.data.toString())
                check(uri.scheme == "file")
                BitmapFactory.decodeFile(File(uri).path)
            }.getOrNull()
            return if (bitmap == null) ErrorResult(image = null, request = chain.request, throwable = java.io.IOException("Synthetic missing local icon"))
            else SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.DISK)
        }
    })
}.build()

/** Generated local test images; no game assets, real accounts or network calls. */
internal fun localIconNames(context: Context): GameNameMap {
    val ids = iconItemIds + listOf(6510L, 6001L)
    val icons = ids.associateWith { id ->
        val file = File(context.filesDir, "capture/icons/$id.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (id == 1L) Color.rgb(225, 180, 55) else Color.rgb(75 + (id % 100).toInt(), 130, 200)
        canvas.drawRoundRect(2f, 2f, 46f, 46f, 10f, 10f, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (id == 1L) "QP" else (id % 100).toString(), 24f, 31f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        "https://static.atlasacademy.io/test-only/$id.png"
    }
    return outsideNames.copy(itemIcons = icons, equips = mapOf(9400010L to "완강"), servants = mapOf(100100L to "알트리아"))
}
