package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.pm.PackageManager
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.CaptureNames

internal fun allowedGamePackages(installed: Set<String>, jpEnabled: Boolean): List<String> =
    listOfNotNull(CaptureVpnService.GAME_PACKAGE, CaptureVpnService.JP_GAME_PACKAGE.takeIf { jpEnabled })
        .filter { it in installed }

internal fun installedGamePackages(context: Context): Set<String> =
    listOf(CaptureVpnService.GAME_PACKAGE, CaptureVpnService.JP_GAME_PACKAGE).filter { name ->
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
        CaptureVpnService.GAME_PACKAGE in installed, CertStore.exists(context),
        CaptureVpnService.JP_GAME_PACKAGE in installed, prefs.captureJpRoot.get(), CertStore.existsJp(context),
    )
}

/** Only game hosts with a matching leaf can be decrypted. Missing contexts stay blind tunnels. */
internal fun <T : Any> captureServerContexts(kr: T?, jp: T?, jpEnabled: Boolean): Map<String, T> = buildMap {
    if (kr != null) put(CaptureNames.GAME_HOST, kr)
    if (jpEnabled && jp != null) put(CaptureNames.JP_HOST, jp)
}
