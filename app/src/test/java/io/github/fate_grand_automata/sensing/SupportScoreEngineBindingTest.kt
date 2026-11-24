package io.github.fate_grand_automata.sensing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.di.script.ScriptsModule
import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.lib_automata.ExitManager
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SupportScoreEngineBindingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `without the engine the engine is never created`() {
        var created = 0
        val engine = supportScoreEngineFor(false) { created++; error("created without the engine") }
        assertSame(NoSupportScoreEngine, engine)
        assertEquals(0, created)
    }

    @Test fun `a build with the engine creates it`() {
        val made = object : SupportScoreEngine by NoSupportScoreEngine {}
        assertSame(made, supportScoreEngineFor(true) { made })
    }

    /**
     * Main CI runs the unit tests on the ci build type, which does not carry the engine, so the
     * Hilt provider must give the no-op object there. A provider that built RustSupportScoreEngine
     * would not fail on its own (the library loads lazily and a load failure is caught), so the
     * test checks the object it gets. With -Pfga.native=true the ci variant carries the engine and
     * the provider must give the real one.
     */
    @Test fun `the script provider binds the engine exactly when this build carries it`() {
        val engine = ScriptsModule.provideSupportScoreEngine(context, ExitManager())
        if (BuildConfig.NATIVE_READER) {
            assertIs<RustSupportScoreEngine>(engine)
            engine.close()
        } else {
            assertSame(NoSupportScoreEngine, engine)
            assertFalse(engine.available)
        }
    }

    @Test fun `without the engine the stop check is never read`() {
        if (BuildConfig.NATIVE_READER) return
        val engine = supportScoreEngine(context) { error("stop check read") }
        assertSame(NoSupportScoreEngine, engine)
    }

    /** NATIVE_READER and the packaged model come from one source in the build script; this ties them together. */
    @Test fun `NATIVE_READER is true exactly when the variant packages the model`() {
        val pin = Properties().apply { File(System.getProperty("fga.ocrModelPin")!!).inputStream().use { load(it) } }
        val model = pin.getProperty("file")!!.trim()
        val packaged = context.assets.list("ocr").orEmpty().contains(model)
        assertEquals(packaged, BuildConfig.NATIVE_READER)
    }
}
