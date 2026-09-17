package com.vpn.simple

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var toggle: WarpToggle
    private lateinit var statusText: TextView
    private var profilePath: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                File(filesDir, "profile.yaml").outputStream().use { output -> input.copyTo(output) }
            }
            profilePath = File(filesDir, "profile.yaml").absolutePath
            toggle.isEnabled = true
            toggle.alpha = 1f
        }.onFailure {
            toast("Could not import profile: ${it.message ?: "read failed"}")
            return@registerForActivityResult
        }
        statusText.text = "Disconnected"
        requestConnect()
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            toggle.checked = false
            toggle.contentDescription = "VPN switch"
            statusText.text = "Disconnected"
            updateStatusText()
            toast(intent?.getStringExtra(SimpleVpnService.EXTRA_MESSAGE) ?: "The connection stopped")
        }
    }

    private val connectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            toggle.checked = true
            toggle.contentDescription = "Connected. Tap to disconnect."
            statusText.text = "Connected"
            updateStatusText()
        }
    }

    private val disconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            toggle.checked = false
            toggle.contentDescription = "VPN switch"
            statusText.text = "Disconnected"
            updateStatusText()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val pad = dp(24f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFE9E1FA.toInt())
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "BYPASS"
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            paint.shader = LinearGradient(
                0f, 0f, paint.measureText("BYPASS"), 0f,
                0xFFF0455F.toInt(), 0xFFFF8A3C.toInt(), Shader.TileMode.CLAMP,
            )
        }

        // The window draws behind the system bars on modern Android, so the lavender has
        // to be pushed in from under them or the title and switch sit too high.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(pad, pad + bars.top, pad, pad + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        toggle = WarpToggle(this).apply {
            onToggle = { on -> if (on) requestConnect() else disconnect() }
        }

        statusText = TextView(this).apply {
            text = "Disconnected"
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
        }
        updateStatusText()

        root.addView(title)
        root.addView(
            toggle,
            LinearLayout.LayoutParams(dp(220f), dp(110f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(48f)
            },
        )
        root.addView(
            statusText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20f)
            },
        )
        setContentView(root)

        ContextCompat.registerReceiver(this, errorReceiver, IntentFilter(SimpleVpnService.ACTION_ERROR), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, connectedReceiver, IntentFilter(SimpleVpnService.ACTION_CONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, disconnectedReceiver, IntentFilter(SimpleVpnService.ACTION_DISCONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)

        val saved = File(filesDir, "profile.yaml")
        if (saved.isFile) {
            profilePath = saved.absolutePath
            if (SimpleVpnService.running) {
                toggle.checked = true
                toggle.contentDescription = "Connected. Tap to disconnect."
                statusText.text = "Connected"
                updateStatusText()
            }
        }
    }

    private fun requestConnect() {
        val profile = profilePath
        if (profile == null) {
            picker.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "application/octet-stream"))
            return
        }
        statusText.text = "Connecting..."
        updateStatusText()
        val permission = VpnService.prepare(this)
        if (permission != null) {
            pendingProfile = profile
            startActivityForResult(permission, 40)
        } else startVpn(profile)
    }

    private fun disconnect() {
        statusText.text = "Disconnecting..."
        updateStatusText()
        // Teardown has to run inside the service: mihomo lives in this process, so simply
        // stopping the service leaves the tunnel routing. The service stops itself once done.
        startService(
            Intent(this, SimpleVpnService::class.java).setAction(SimpleVpnService.ACTION_DISCONNECT),
        )
    }

    private var pendingProfile: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 40 && resultCode == RESULT_OK) startVpn(pendingProfile ?: return)
        else if (requestCode == 40) {
            toggle.checked = false
            statusText.text = "Disconnected"
            updateStatusText()
            toast("VPN permission was not granted")
        }
    }

    private fun startVpn(profile: String) {
        startService(Intent(this, SimpleVpnService::class.java).putExtra(SimpleVpnService.EXTRA_PROFILE, profile))
    }

    override fun onDestroy() {
        listOf(errorReceiver, connectedReceiver, disconnectedReceiver).forEach { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusText() {
        statusText.setTextColor(
            when (statusText.text.toString()) {
                "Connected" -> 0xFF1B8A3A.toInt()
                "Connecting...", "Disconnecting..." -> 0xFFB25B00.toInt()
                else -> 0xFFD6416B.toInt()
            }
        )
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
