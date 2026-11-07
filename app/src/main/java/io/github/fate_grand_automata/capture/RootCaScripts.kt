package io.github.fate_grand_automata.capture

/** Fixed scripts with validated hashes and private paths, never caller-supplied shell fragments. */
object RootCaScripts {
    const val SYSTEM = "/system/etc/security/cacerts"
    const val APEX = "/apex/com.android.conscrypt/cacerts"

    private fun header(hash: String, pem: String): String {
        require(Regex("^[0-9a-f]{8}$").matches(hash)) { "Invalid CA hash" }
        require(Regex("^/data/(user(_de)?/[0-9]+|data)/[A-Za-z0-9_.]+/(cache|files)/[A-Za-z0-9_./-]+$").matches(pem) &&
            pem.split('/').none { it == ".." || it == "." }) { "Expected an app-private path" }
        return """
            set -eu
            if [ "${'$'}(id -u)" != 0 ]; then echo FGA_DENIED; exit 13; fi
            echo FGA_ROOT
            SYS='$SYSTEM'
            APEX='$APEX'
            CERT='$hash.0'
            PEM='$pem'
            zygotes() { pidof zygote64 zygote 2>/dev/null || true; }
            children() { ps -o PID -P "${'$'}1" 2>/dev/null | tail -n +2 || true; }
            system_tmpfs() { awk -v s="${'$'}SYS" '${'$'}2 == s && ${'$'}3 == "tmpfs" { found=1 } END { exit !found }' /proc/mounts; }
            present() {
        """.trimIndent() + "\n"
    }

    private fun presence(api: Int) = if (api >= 34) """
                Z=${'$'}(pidof zygote64 2>/dev/null || pidof zygote 2>/dev/null || true)
                Z=${'$'}{Z%% *}
                [ -n "${'$'}Z" ] && nsenter --mount=/proc/${'$'}Z/ns/mnt -- /system/bin/sh -c 'test -f "${'$'}1/${'$'}2" && cmp -s "${'$'}1/${'$'}2" "${'$'}3"' sh "${'$'}APEX" "${'$'}CERT" "${'$'}PEM"
            }
    """.trimIndent() else """
                [ -f "${'$'}SYS/${'$'}CERT" ] && cmp -s "${'$'}SYS/${'$'}CERT" "${'$'}PEM"
            }
    """.trimIndent()

    fun isPresent(api: Int, hash: String, pem: String) = header(hash, pem) + presence(api) + "\n" + """
        if present; then echo FGA_PRESENT; else echo FGA_ABSENT; fi
    """.trimIndent() + "\n"

    fun inject(api: Int, hash: String, pem: String): String = header(hash, pem) + presence(api) + "\n" + """
        if present && system_tmpfs; then echo FGA_ALREADY_PRESENT; exit 0; fi
        TMP="${'$'}PEM.store"
        trap 'rm -rf "${'$'}TMP"' EXIT
        # Never stack tmpfs overlays.
        if ! system_tmpfs; then
            mkdir -m 700 "${'$'}TMP"
            cp ${if (api >= 34) "\"${'$'}APEX\"/*" else "\"${'$'}SYS\"/*"} "${'$'}TMP/"
            mount -t tmpfs tmpfs "${'$'}SYS"
            cp "${'$'}TMP/"* "${'$'}SYS/"
        fi
        # Do not replace an unrelated trusted certificate on a subject-hash collision.
        if [ -f "${'$'}SYS/${'$'}CERT" ] && ! cmp -s "${'$'}SYS/${'$'}CERT" "${'$'}PEM"; then
            echo FGA_HASH_COLLISION; exit 14
        fi
        cp "${'$'}PEM" "${'$'}SYS/${'$'}CERT"
        chown root:root "${'$'}SYS/"*
        chmod 644 "${'$'}SYS/"*
        if ! chcon u:object_r:system_file:s0 "${'$'}SYS/"*; then
            case "${'$'}(getenforce 2>/dev/null || true)" in
                Disabled|Permissive) ;;
                *) exit 15 ;;
            esac
        fi
        rm -rf "${'$'}TMP"
    """.trimIndent() + "\n" + (if (api >= 34) """
        bind_store() {
            # Equality in the target namespace prevents stacking bind mounts.
            if ! nsenter --mount=/proc/${'$'}1/ns/mnt -- /system/bin/sh -c 'cmp -s "${'$'}1/${'$'}2" "${'$'}3"' sh "${'$'}APEX" "${'$'}CERT" "${'$'}PEM"; then
                nsenter --mount=/proc/${'$'}1/ns/mnt -- /system/bin/mount --bind "${'$'}SYS" "${'$'}APEX"
            fi
        }
        APPS=0
        for Z in ${'$'}(zygotes); do
            bind_store "${'$'}Z"
            for P in ${'$'}(children "${'$'}Z"); do
                if bind_store "${'$'}P"; then APPS=${'$'}((APPS + 1)); fi
            done
        done
        echo "FGA_APPS=${'$'}APPS"
    """.trimIndent() + "\n" else "") + """
        if present; then echo FGA_INJECTED; else echo FGA_VERIFY_FAILED; exit 16; fi
    """.trimIndent() + "\n"

    fun remove(api: Int, hash: String, pem: String): String = header(hash, pem) + presence(api) + "\n" +
        (if (api >= 34) """
            for Z in ${'$'}(zygotes); do
                for P in ${'$'}(children "${'$'}Z") "${'$'}Z"; do
                    nsenter --mount=/proc/${'$'}P/ns/mnt -- /system/bin/umount "${'$'}APEX" || true
                done
            done
        """.trimIndent() + "\n" else "") + """
            umount "${'$'}SYS" || true
            if present; then echo FGA_REMOVE_FAILED; exit 17; else echo FGA_REMOVED; fi
        """.trimIndent() + "\n"
}
