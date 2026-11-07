package io.github.fate_grand_automata.capture

internal open class FakeJpCaInstaller : JpCaInstaller {
    var root = true
    var present = false
    var result: RootCaResult = RootCaResult.Injected
    var gameRunning = false
    var checks = 0
    var injections = 0
    var certificates = 0
    var removals = 0
    override fun hasRoot() = root
    override fun ensureCertificate() { certificates++ }
    override fun isPresent(): Boolean { checks++; return present }
    override fun inject(): RootCaResult { injections++; return result }
    override fun remove(): Boolean { removals++; present = false; return true }
    override fun isGameRunning() = gameRunning
}
