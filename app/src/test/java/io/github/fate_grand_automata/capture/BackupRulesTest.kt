package io.github.fate_grand_automata.capture

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BackupRulesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var captureDir: File

    @Before
    fun setUp() {
        captureDir = File(context.filesDir, "capture")
        captureDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        captureDir.deleteRecursively()
    }

    private data class RuleElement(
        val section: String?,
        val tag: String,
        val domain: String?,
        val path: String?,
    )

    private fun parseXmlRules(resId: Int, rootTag: String): List<RuleElement> {
        val parser = context.resources.getXml(resId)
        val elements = mutableListOf<RuleElement>()
        var currentSection: String? = null
        var sawRoot = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        rootTag -> sawRoot = true
                        "cloud-backup", "device-transfer" -> currentSection = parser.name
                        "exclude", "include" -> elements += RuleElement(
                            section = currentSection,
                            tag = parser.name,
                            domain = parser.getAttributeValue(null, "domain"),
                            path = parser.getAttributeValue(null, "path"),
                        )
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                        currentSection = null
                    }
                }
            }
            event = parser.next()
        }
        assertTrue(sawRoot, "Expected root element <$rootTag>")
        return elements
    }

    @Test
    fun fullBackupRulesExcludeCaptureDirectoryWithoutIncludes() {
        val rules = parseXmlRules(R.xml.backup_rules, "full-backup-content")
        assertTrue(rules.none { it.tag == "include" })
        assertTrue(rules.any { it.tag == "exclude" && it.domain == "file" && it.path == "capture/" })
    }

    @Test
    fun dataExtractionRulesExcludeCaptureDirectoryForCloudAndTransferWithoutIncludes() {
        val rules = parseXmlRules(R.xml.data_extraction_rules, "data-extraction-rules")
        assertTrue(rules.none { it.tag == "include" })
        assertTrue(
            rules.any {
                it.section == "cloud-backup" && it.tag == "exclude" && it.domain == "file" && it.path == "capture/"
            },
        )
        assertTrue(
            rules.any {
                it.section == "device-transfer" && it.tag == "exclude" && it.domain == "file" && it.path == "capture/"
            },
        )
    }

    @Test
    fun manifestKeepsBackupEnabledAndReferencesBothRuleFiles() {
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)

        val manifestFile = File("src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestFile)
        val app = doc.getElementsByTagName("application").item(0)
        val attrs = app.attributes
        val androidNs = "http://schemas.android.com/apk/res/android"
        assertEquals("true", attrs.getNamedItemNS(androidNs, "allowBackup")?.nodeValue)
        assertEquals("true", attrs.getNamedItemNS(androidNs, "fullBackupOnly")?.nodeValue)
        assertEquals("@xml/backup_rules", attrs.getNamedItemNS(androidNs, "fullBackupContent")?.nodeValue)
        assertEquals("@xml/data_extraction_rules", attrs.getNamedItemNS(androidNs, "dataExtractionRules")?.nodeValue)
    }

    @Test
    fun missingCaptureDirectoryFallsBackToAbsentCertificateWithoutCrashing() {
        assertFalse(captureDir.exists())

        assertFalse(CertStore.exists(context))
        assertFalse(CertStore.existsJp(context))
        assertNull(CertStore.caBytes(context))
        assertFalse(CertStore.isInstalled(context))
        assertNull(CertStore.export(context, folder = null))
        assertFalse(RootCaInjector(context).isPresent())

        val sp = context.getSharedPreferences("backup_restore_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val prefs = PrefsCore(PrefMaker(sp), context)
        prefs.captureTrackerEnabled.set(true)
        prefs.captureConsentVersion.set(CaptureConsent.VERSION)
        prefs.captureJpRoot.set(true)

        val readiness = captureReadiness(context, prefs)
        assertFalse(readiness.krCertificate)
        assertFalse(readiness.jpCertificate)
        assertFalse(readiness.ready)
    }

    @Test
    fun partialCaptureFilesAreTreatedAsAbsent() {
        captureDir.mkdirs()
        File(captureDir, "leaf.p12").writeBytes(byteArrayOf(1, 2, 3))
        File(captureDir, "leaf-jp.p12").writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(CertStore.exists(context))
        assertFalse(CertStore.existsJp(context))
        assertNull(CertStore.caBytes(context))
        assertFalse(CertStore.isInstalled(context))
    }
}
