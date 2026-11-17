package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.CaptureNames

internal const val GAME_PACKAGE = "com.netmarble.fgok"
internal const val JP_GAME_PACKAGE = "com.aniplex.fategrandorder"

internal fun allowedGamePackages(installed: Set<String>, jpEnabled: Boolean): List<String> =
    listOfNotNull(GAME_PACKAGE, JP_GAME_PACKAGE.takeIf { jpEnabled })
        .filter { it in installed }

internal fun installedGamePackages(context: Context): Set<String> =
    listOf(GAME_PACKAGE, JP_GAME_PACKAGE).filter { name ->
        try {
            context.packageManager.getPackageInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }.toSet()

internal data class CaptureReadiness(
    val krInstalled: Boolean,
    val krCertificate: Boolean,
    val jpInstalled: Boolean,
    val jpEnabled: Boolean,
    val jpCertificate: Boolean,
) {
    val krReady get() = krInstalled && krCertificate
    val jpReady get() = jpEnabled && jpInstalled && jpCertificate
    val ready get() = krReady || jpReady
    val gameInstalled get() = krInstalled || (jpEnabled && jpInstalled)
}

internal fun captureReadiness(context: Context, prefs: PrefsCore): CaptureReadiness {
    val installed = installedGamePackages(context)
    return CaptureReadiness(
        GAME_PACKAGE in installed, CertStore.exists(context),
        JP_GAME_PACKAGE in installed, prefs.captureJpRoot.get(), CertStore.existsJp(context),
    )
}

/** Only game hosts with a matching leaf can be decrypted. Missing contexts stay blind tunnels. */
internal fun <T : Any> captureServerContexts(kr: T?, jp: T?, jpEnabled: Boolean): Map<String, T> = buildMap {
    if (kr != null) put(CaptureNames.GAME_HOST, kr)
    if (jpEnabled && jp != null) put(CaptureNames.JP_HOST, jp)
}

internal fun rootTargetUids(
    krUid: Int?,
    krCertificate: Boolean,
    jpUid: Int?,
    jpEnabled: Boolean,
    jpCertificate: Boolean,
): List<Int> = buildList {
    if (krUid != null && krUid > 0 && krCertificate) add(krUid)
    if (jpEnabled && jpUid != null && jpUid > 0 && jpCertificate) add(jpUid)
}.distinct()

internal fun packageUid(context: Context, packageName: String): Int? =
    try {
        context.packageManager.getApplicationInfo(packageName, 0).uid.takeIf { it > 0 }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

@RequiresApi(Build.VERSION_CODES.Q)
internal fun resolveRootTargetUids(context: Context, prefs: PrefsCore): List<Int> =
    rootTargetUids(
        krUid = packageUid(context, GAME_PACKAGE),
        krCertificate = CertStore.exists(context),
        jpUid = packageUid(context, JP_GAME_PACKAGE),
        jpEnabled = prefs.captureJpRoot.get(),
        jpCertificate = CertStore.existsJp(context),
    )

