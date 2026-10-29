package io.github.lib_automata

class ResizedScreenshotProvider(
    private val original: ScreenshotService,
    private var scale: Double,
    platformImpl: PlatformImpl
): ScreenshotService {
    private val resizeTarget = platformImpl.getResizableBlankPattern()

    @Synchronized
    fun resize(scale: Double) { this.scale = scale }

    @Synchronized
    override fun takeScreenshot(): Pattern {
        val shot = original.takeScreenshot()
        shot.resize(resizeTarget, shot.size * scale)
        return resizeTarget
    }

    override fun startRecording() = original.startRecording()

    override fun close() {
        original.close()
        resizeTarget.close()
    }
}
