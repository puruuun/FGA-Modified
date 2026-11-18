package io.github.fate_grand_automata.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaSyncServiceCommitTest {
    private fun commit(bundleSha: String?): ChaldeaSyncResult {
        val json = ChaldeaSyncService().commit("{}", "abc", true, "pkg", bundleSha)
        return ChaldeaSyncResult.fromJson(json)
    }

    @Test
    fun nullBundleHashIsRefusedAsBadPatch() {
        assertEquals(ChaldeaSyncCode.BadPatch, commit(null).code)
    }

    @Test
    fun blankBundleHashIsRefusedAsBadPatch() {
        assertEquals(ChaldeaSyncCode.BadPatch, commit("").code)
        assertEquals(ChaldeaSyncCode.BadPatch, commit("  ").code)
    }
}
