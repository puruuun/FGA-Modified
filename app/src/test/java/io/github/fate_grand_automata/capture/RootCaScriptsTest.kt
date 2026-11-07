package io.github.fate_grand_automata.capture

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootCaScriptsTest {
    private val pem = "/data/user/0/io.github.fate_grand_automata.beta/cache/jp-ca-123.pem"
    private fun inject(api: Int) = RootCaScripts.inject(api, "bad93d86", pem)

    @Test fun android13CopiesSystemStoreIntoTmpfs() {
        val script = inject(33)
        assertContains(script, "cp \"\$SYS\"/*")
        assertContains(script, "mount -t tmpfs tmpfs \"\$SYS\"")
        assertContains(script, RootCaScripts.SYSTEM)
        assertContains(script, "chown root:root")
        assertContains(script, "chmod 644")
        assertContains(script, "chcon u:object_r:system_file:s0")
        assertContains(script, "Disabled|Permissive) ;;")
        assertContains(script, "*) exit 15 ;;")
        assertContains(script, "trap 'rm -rf")
        assertFalse(script.contains("mount --bind"))
        assertFalse(script.contains("nsenter"))
    }

    @Test fun android14CopiesApexAndBindsBothZygotesAndTheirChildren() {
        for (api in listOf(34, 35, 36)) {
            val script = inject(api)
            assertContains(script, "cp \"\$APEX\"/*")
            assertContains(script, RootCaScripts.APEX)
            assertContains(script, "pidof zygote64 zygote")
            assertContains(script, "ps -o PID -P")
            assertContains(script, "nsenter --mount=/proc/\$1/ns/mnt")
            assertContains(script, "/system/bin/mount --bind")
            assertContains(script, "if bind_store \"\$P\"")
            assertContains(script, "FGA_APPS=")
            assertContains(script, "pidof zygote64 2>/dev/null || pidof zygote")
        }
    }

    @Test fun rejectsAllNonHashesForEveryScript() {
        for (hash in listOf("", "BAD93D86", "bad93d8", "bad93d860", "bad93d86\n", "bad93d86;id", "../abcde", "éabcdefg")) {
            for (build in listOf(RootCaScripts::inject, RootCaScripts::remove, RootCaScripts::isPresent)) {
                assertFailsWith<IllegalArgumentException> { build(34, hash, pem) }
            }
        }
    }

    @Test fun rejectsNonPrivatePathsAndShellCharacters() {
        for (path in listOf("/sdcard/ca.pem", "/data/local/tmp/ca.pem", "$pem;id", "$pem'foo", "/data/user/0/app/cache/../ca.pem")) {
            assertFailsWith<IllegalArgumentException> { RootCaScripts.inject(34, "bad93d86", path) }
        }
    }

    @Test fun idempotenceAndCollisionGuardsPrecedeMounts() {
        for (api in listOf(33, 34)) {
            val script = inject(api)
            assertTrue(script.indexOf("if present") < script.indexOf("mount -t tmpfs"))
            assertContains(script, "/proc/mounts")
            assertContains(script, "\"tmpfs\" { found=1 }")
            assertContains(script, "FGA_ALREADY_PRESENT")
            assertContains(script, "FGA_HASH_COLLISION")
            if (api >= 34) assertTrue(script.indexOf("if ! nsenter") < script.indexOf("/system/bin/mount --bind"))
        }
    }

    @Test fun namespaceGuardPrecedesFirstMountInEveryScript() {
        for (api in listOf(33, 34)) for (script in listOf(inject(api), RootCaScripts.isPresent(api, "bad93d86", pem),
            RootCaScripts.remove(api, "bad93d86", pem))) {
            val guard = script.indexOf("readlink /proc/self/ns/mnt")
            assertTrue(guard > script.indexOf("FGA_DENIED"))
            assertContains(script, "readlink /proc/1/ns/mnt")
            assertContains(script, "FGA_WRONG_NS; exit 18")
            listOf("mount -t tmpfs", "mount --bind", "umount").map { script.indexOf(it) }.filter { it >= 0 }
                .forEach { assertTrue(guard in 0 until it) }
        }
    }

    @Test fun removeUnmountsChildrenZygotesAndSystemAndVerifiesAbsence() {
        val new = RootCaScripts.remove(34, "bad93d86", pem)
        assertContains(new, "for P in $(children \"\$Z\") \"\$Z\"")
        assertContains(new, "nsenter --mount=/proc/\$P/ns/mnt -- /system/bin/umount \"\$APEX\" || true")
        for (api in listOf(33, 34)) {
            val script = RootCaScripts.remove(api, "bad93d86", pem)
            assertContains(script, "umount \"\$SYS\" || true")
            assertContains(script, "if present; then echo FGA_REMOVE_FAILED")
            assertContains(script, "FGA_REMOVED")
        }
    }

    @Test fun neverRemountsOrWritesARealPartition() {
        for (api in listOf(33, 34)) for (script in listOf(inject(api), RootCaScripts.remove(api, "bad93d86", pem))) {
            assertFalse(script.contains("remount"))
            assertFalse(script.contains(",rw"))
            assertFalse(script.contains("blockdev"))
            assertFalse(script.contains("/dev/block"))
        }
    }
}
