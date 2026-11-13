package io.github.fate_grand_automata.capture.chaldea

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaSyncClientTest {
    private fun sessionWith(response: () -> String): ChaldeaSyncClient.Session {
        val stub = object : IChaldeaSyncService.Stub() {
            override fun destroy() = Unit
            override fun state(): String = """{"state":"NotRunning"}"""
            override fun forceStop(): String = """{"state":"NotRunning"}"""
            override fun prepare(writePipe: ParcelFileDescriptor?): String = "{}"
            override fun commit(
                patchJson: String?,
                expectedSha256: String?,
                allowCreate: Boolean,
                expectedPackage: String?,
                expectedBundleSha256: String?,
            ): String = "{}"
            override fun restore(readPipe: ParcelFileDescriptor?, expectedSha256: String?, packageName: String?): String = "{}"
            override fun gameData(knownVersion: String?): String = """{"unchanged":true}"""
            override fun gameState(): String = response()
        }
        return ChaldeaSyncClient.Session(stub) {}
    }

    @Test
    fun gameVisibleParsesVisibleResponseAndSurvivesMalformedOrThrowingService() = runBlocking {
        assertTrue(sessionWith { """{"visible":true,"package":"com.netmarble.fgok"}""" }.gameVisible())
        assertFalse(sessionWith { """{"visible":false}""" }.gameVisible())
        assertFalse(sessionWith { "not-json" }.gameVisible())
        assertFalse(sessionWith { throw RemoteException("binder lost") }.gameVisible())
        assertFalse(sessionWith { throw IllegalStateException("binder dead") }.gameVisible())
    }
}
