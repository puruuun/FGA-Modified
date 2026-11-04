package io.github.fate_grand_automata.capturespike

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val refresh: () -> Unit = {
        runOnUiThread {
            logView.text = SpikeLog.snapshot()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
            root.addView(this)
        }
        button("인증서 만들기") { onCreateCert() }
        button("보안 설정 열기 (인증서 설치)") { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        button("VPN 시작") { onStartVpn() }
        button("VPN 중지") { onStopVpn() }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // targetSdk 35+ draws edge to edge, so the action bar covered the first buttons; drop it and pad by the system bars
        actionBar?.hide()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        setContentView(root)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onStart() {
        super.onStart()
        SpikeLog.listeners += refresh
        logUserCas()
        refresh()
    }

    /** Lists the user-installed CAs the platform trust store reports, to check the install without root. */
    private fun logUserCas() = thread {
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val users = ks.aliases().toList().filter { it.startsWith("user:") }
            SpikeLog.log("user CAs installed: ${users.size}")
            users.forEach { alias ->
                val cert = ks.getCertificate(alias) as java.security.cert.X509Certificate
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                SpikeLog.log("  $alias ${cert.subjectX500Principal.name} sha256=${sha.take(16)}…")
            }
        }.onFailure { SpikeLog.log("user CA listing failed: $it") }
    }

    override fun onStop() {
        SpikeLog.listeners -= refresh
        super.onStop()
    }

    private fun onCreateCert() {
        if (CertStore.leafExists(this)) {
            SpikeLog.log("leaf already exists")
            AlertDialog.Builder(this)
                .setMessage("이미 인증서가 있습니다. 새로 만들면 기존 CA는 못 쓰게 됩니다. 다시 만들까요?")
                .setPositiveButton("다시 만들기") { _, _ -> createCert() }
                .setNegativeButton("취소", null)
                .show()
        } else {
            createCert()
        }
    }

    private fun createCert() {
        SpikeLog.log("creating certificates...")
        thread {
            try {
                CertStore.create(this)
                SpikeLog.log("certificates created; install the CA from Download/fga-capture-ca.cer")
            } catch (e: Exception) {
                SpikeLog.log("cert creation failed: $e")
            }
        }
    }

    private fun onStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQ_VPN) else startVpn()
    }

    private fun startVpn() {
        startForegroundService(Intent(this, CaptureVpnService::class.java))
    }

    private fun onStopVpn() {
        startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
    }

    @Deprecated("simple spike")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn() else SpikeLog.log("VPN consent denied")
        }
    }

    private companion object {
        const val REQ_VPN = 100
    }
}
