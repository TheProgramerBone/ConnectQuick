package dev.connectquick.controller.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.connectquick.controller.R
import dev.connectquick.controller.identity.KeystoreDeviceIdentity
import dev.connectquick.controller.net.BackendClient
import dev.connectquick.controller.pairing.PairingController
import dev.connectquick.controller.pairing.PairingOutcome
import dev.connectquick.controller.pairing.QrPairingPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** The only screen: "Escanear código QR" → connect to the scanned QR's relay → pair. No session/remote-control UI yet (Phases 4+, not started). */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var connectionJob: Job? = null
    private var awaitingResult = false

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra(ScanActivity.EXTRA_QR_TEXT)
        if (result.resultCode == RESULT_OK && text != null) onQrScanned(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        statusText = TextView(this).apply {
            text = getString(R.string.status_idle)
            gravity = Gravity.CENTER
        }
        val scanButton = Button(this).apply {
            text = getString(R.string.scan_button)
            setOnClickListener { scanLauncher.launch(Intent(this@MainActivity, ScanActivity::class.java)) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 32
            bottomMargin = 32
        }
        root.addView(statusText, lp)
        root.addView(scanButton, lp)
        setContentView(root)
    }

    private fun onQrScanned(text: String) {
        val qr = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(QrPairingPayload.serializer(), text)
        } catch (e: Exception) {
            statusText.text = getString(R.string.status_invalid_qr)
            return
        }

        connectionJob?.cancel()
        awaitingResult = true
        statusText.text = getString(R.string.status_connecting, qr.hostName)

        val identity = KeystoreDeviceIdentity.loadOrCreate(applicationContext)
        val client = BackendClient(qr.relay)
        val controller = PairingController(client, identity, deviceName = Build.MODEL ?: "Android")

        controller.onReady = {
            if (awaitingResult) {
                awaitingResult = false
                controller.submitPairing(qr)
            }
        }
        controller.onOutcome = { outcome -> runOnUiThread { showOutcome(outcome) } }

        connectionJob = lifecycleScope.launch { client.runForever() }
    }

    private fun showOutcome(outcome: PairingOutcome) {
        statusText.text = when (outcome) {
            is PairingOutcome.Success -> getString(R.string.status_success, outcome.hostName)
            is PairingOutcome.Failure -> getString(R.string.status_failure, outcome.reason)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionJob?.cancel()
    }
}
