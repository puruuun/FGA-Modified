package io.github.fate_grand_automata.spike

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.fate_grand_automata.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShizukuSpikeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShizukuSpike"
        private const val REQUEST_CODE_PERMISSION = 2026
    }

    private var userService: IShizukuSpikeService? = null
    private val isServiceBound = mutableStateOf(false)
    private val logEntries = mutableStateListOf<String>()
    private val showForceStopDialog = mutableStateOf(false)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_PERMISSION) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            appendLog("Permission Result", if (granted) "Permission GRANTED" else "Permission DENIED")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appendLog("Shizuku Event", "Binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        appendLog("Shizuku Event", "Binder dead")
        userService = null
        isServiceBound.value = false
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, ShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("spike")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val service = IShizukuSpikeService.Stub.asInterface(binder)
                userService = service
                isServiceBound.value = true
                try {
                    val uid = service.uid
                    val pid = service.pid
                    appendLog(
                        "C. Bind UserService",
                        "Connected! UID=$uid (${if (uid == 0) "root" else "shell"}), PID=$pid"
                    )
                } catch (t: Throwable) {
                    appendLog("C. Bind UserService", "Connected, but querying UID/PID failed: ${t.message}")
                }
            } else {
                appendLog("C. Bind UserService", "onServiceConnected: binder is null or inactive")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            isServiceBound.value = false
            appendLog("C. Bind UserService", "onServiceDisconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        appendLog("Init", "ShizukuSpikeActivity started. ApplicationId=${BuildConfig.APPLICATION_ID}")

        setContent {
            MaterialTheme {
                SpikeScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (isServiceBound.value) {
            unbindUserService()
        }
    }

    private fun appendLog(action: String, result: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$time] $action:\n$result"
        logEntries.add(entry)
        Timber.tag(TAG).i("%s -> %s", action, result)
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- Action Handlers ---

    private fun checkStatus() {
        try {
            val alive = Shizuku.pingBinder()
            if (!alive) {
                appendLog("A. Status", "Shizuku binder is NOT alive. Make sure Shizuku app is running.")
                return
            }
            val version = Shizuku.getVersion()
            val permGranted = if (Shizuku.isPreV11()) {
                "Pre-v11 (Permission not required via API)"
            } else {
                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                if (granted) "GRANTED" else "NOT GRANTED"
            }
            val uid = Shizuku.getUid()
            val uidRole = when (uid) {
                0 -> "0 (root)"
                2000 -> "2000 (shell)"
                else -> "$uid (other)"
            }
            val statusSummary = "Binder: Alive\nVersion: $version\nPermission: $permGranted\nServer UID: $uidRole"
            appendLog("A. Status", statusSummary)
        } catch (t: Throwable) {
            appendLog("A. Status", "Error checking status: ${t.message}")
        }
    }

    private fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                appendLog("B. Request permission", "Failed: Shizuku binder is NOT alive.")
                return
            }
            if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                appendLog("B. Request permission", "Permission is ALREADY GRANTED.")
                return
            }
            appendLog("B. Request permission", "Requesting Shizuku permission...")
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (t: Throwable) {
            appendLog("B. Request permission", "Error requesting permission: ${t.message}")
        }
    }

    private fun bindUserService() {
        try {
            if (isServiceBound.value) {
                appendLog("C. Bind UserService", "Already bound.")
                return
            }
            if (!Shizuku.pingBinder()) {
                appendLog("C. Bind UserService", "Failed: Shizuku binder is not alive.")
                return
            }
            if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                appendLog("C. Bind UserService", "Failed: Permission not granted. Run Request Permission first.")
                return
            }
            appendLog("C. Bind UserService", "Binding UserService...")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            appendLog("C. Bind UserService", "Error binding UserService: ${t.message}")
        }
    }

    private fun unbindUserService() {
        try {
            if (!isServiceBound.value) {
                appendLog("Unbind UserService", "Not bound.")
                return
            }
            userService?.let { service ->
                try {
                    service.destroy()
                } catch (ignored: Throwable) {}
            }
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            userService = null
            isServiceBound.value = false
            appendLog("Unbind UserService", "UserService unbound successfully.")
        } catch (t: Throwable) {
            appendLog("Unbind UserService", "Error unbinding UserService: ${t.message}")
        }
    }

    private fun checkChaldeaState() {
        val service = userService
        if (service == null) {
            appendLog("D. Chaldea state", "Failed: UserService is not bound. Run Bind UserService first.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val state = service.chaldeaState
                withContext(Dispatchers.Main) {
                    appendLog("D. Chaldea state", state)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendLog("D. Chaldea state", "Error querying Chaldea state: ${t.message}")
                }
            }
        }
    }

    private fun readUserDataFile() {
        val service = userService
        if (service == null) {
            appendLog("E. Read file", "Failed: UserService is not bound. Run Bind UserService first.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = service.readUserDataInfo()

                // Test ParcelFileDescriptor pipe transfer
                val pfd = service.openUserDataPipe("userdata.json")
                val pfdMsg = if (pfd != null) {
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                    val sha = calculateSha256(bytes)
                    "ParcelFileDescriptor pipe: Received ${bytes.size} bytes, SHA-256=$sha"
                } else {
                    "ParcelFileDescriptor pipe: file not available"
                }

                // Test chunked transfer
                val chunk = service.readUserDataChunk("userdata.json", 0, 500 * 1024)
                val chunkMsg = if (chunk != null) {
                    "Chunked transfer (0..500KB): Read ${chunk.size} bytes"
                } else {
                    "Chunked transfer: file not available"
                }

                val combined = "$info\n---\n$pfdMsg\n$chunkMsg"
                withContext(Dispatchers.Main) {
                    appendLog("E. Read file", combined)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendLog("E. Read file", "Error reading file: ${t.message}")
                }
            }
        }
    }

    private fun rewriteIdentically() {
        val service = userService
        if (service == null) {
            appendLog("F. Rewrite identically", "Failed: UserService is not bound. Run Bind UserService first.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = service.rewriteIdentically()
                withContext(Dispatchers.Main) {
                    appendLog("F. Rewrite identically", result)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendLog("F. Rewrite identically", "Error during rewrite: ${t.message}")
                }
            }
        }
    }

    private fun forceStopChaldea() {
        val service = userService
        if (service == null) {
            appendLog("G. Force-stop Chaldea", "Failed: UserService is not bound. Run Bind UserService first.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val output = service.forceStopChaldea()
                withContext(Dispatchers.Main) {
                    appendLog("G. Force-stop Chaldea", output)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendLog("G. Force-stop Chaldea", "Error stopping Chaldea: ${t.message}")
                }
            }
        }
    }

    // --- UI Composables ---

    @Composable
    private fun SpikeScreen() {
        val scrollState = rememberScrollState()

        LaunchedEffect(logEntries.size) {
            if (logEntries.isNotEmpty()) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }

        if (showForceStopDialog.value) {
            AlertDialog(
                onDismissRequest = { showForceStopDialog.value = false },
                title = { Text("Force-Stop Chaldea") },
                text = { Text("Are you sure you want to force-stop cc.narumi.chaldea via am force-stop?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showForceStopDialog.value = false
                            forceStopChaldea()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Force Stop")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showForceStopDialog.value = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FGA Shizuku Test",
                        fontSize = 18.sp,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isServiceBound.value) "Service: BOUND" else "Service: UNBOUND",
                        color = if (isServiceBound.value) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Button Grid / Rows
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { checkStatus() }) {
                        Text("A. Status")
                    }
                    Button(onClick = { requestPermission() }) {
                        Text("B. Request Perm")
                    }
                    Button(onClick = { bindUserService() }) {
                        Text("C. Bind Service")
                    }
                    OutlinedButton(onClick = { unbindUserService() }) {
                        Text("Unbind Service")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { checkChaldeaState() }) {
                        Text("D. Chaldea State")
                    }
                    Button(onClick = { readUserDataFile() }) {
                        Text("E. Read File")
                    }
                    Button(onClick = { rewriteIdentically() }) {
                        Text("F. Rewrite Identically")
                    }
                    Button(
                        onClick = { showForceStopDialog.value = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("G. Force-stop")
                    }
                    TextButton(onClick = { logEntries.clear() }) {
                        Text("Clear Log")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Log console
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, Color.Gray)
                        .background(Color(0xFF1E1E1E))
                        .padding(8.dp)
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            if (logEntries.isEmpty()) {
                                Text(
                                    text = "Log is empty. Tap any action button above.",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                logEntries.forEach { entry ->
                                    Text(
                                        text = entry,
                                        color = Color(0xFFD4D4D4),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
