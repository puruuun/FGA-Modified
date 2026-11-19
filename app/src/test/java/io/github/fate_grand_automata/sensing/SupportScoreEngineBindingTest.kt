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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SupportScoreEngineBindingTest {
    @Test fun `outside the rust build type the engine is never created`() {
        var created = 0
        val engine = supportScoreEngineFor(false) { created++; error("created outside the rust build type") }
        assertSame(NoSupportScoreEngine, engine)
        assertEquals(0, created)
    }

    @Test fun `the rust build type creates the engine`() {
        val made = object : SupportScoreEngine by NoSupportScoreEngine {}
        assertSame(made, supportScoreEngineFor(true) { made })
    }

    /**
     * The unit tests run on the ci build type. The Hilt provider must give the no-op object there
     * and must not touch libfga_ffi.so, which is not on a JVM test's library path: a load attempt
     * would throw UnsatisfiedLinkError.
     */
    @Test fun `the script provider binds the no-op engine in this build`() {
        assertFalse(BuildConfig.RUST_TEST_BUILD)
        val engine = ScriptsModule.provideSupportScoreEngine(ApplicationProvider.getApplicationContext<Application>())
        assertSame(NoSupportScoreEngine, engine)
        assertFalse(engine.available)
    }
}
