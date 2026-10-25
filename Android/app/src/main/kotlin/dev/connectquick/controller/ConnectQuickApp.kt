package dev.connectquick.controller

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.connectquick.controller.data.HostStore
import dev.connectquick.controller.files.DownloadsSink
import dev.connectquick.controller.identity.KeystoreDeviceIdentity
import dev.connectquick.controller.session.ControllerHub
import dev.connectquick.controller.session.PairedHost
import dev.connectquick.controller.stream.WebRtcEngine

/**
 * Process-wide holder of the one connection to the paired PC. The link runs while the app is on
 * screen (plus a short grace period so switching to another app to copy something does not drop the
 * session) and stops by itself afterwards: no background service, no battery drain, and no remote
 * session left open on a phone nobody is looking at.
 */
class ConnectQuickApp : Application() {
    val identity by lazy { KeystoreDeviceIdentity.loadOrCreate(this) }
    val store by lazy { HostStore(this) }
    val rtc by lazy { WebRtcEngine(this) }

    @Volatile var hub: ControllerHub? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var started = 0
    private val stopLater = Runnable { if (started == 0) hub?.stop() }

    override fun onCreate() {
        super.onCreate()
        dev.connectquick.controller.ui.Ui.light = store.lightTheme()
        store.load()?.let { buildHub(it) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                main.removeCallbacks(stopLater)
                hub?.let { if (it.state.phase == dev.connectquick.controller.session.LinkState.Phase.STOPPED) it.start() }
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started == 0) main.postDelayed(stopLater, GRACE_MS)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        watchNetwork()
    }

    /** Called after a successful pairing, or when switching PC. */
    fun adoptHost(host: PairedHost) {
        hub?.release()
        store.save(host)
        buildHub(host)
        hub?.start()
    }

    /** Forgets the PC on this phone. (The PC keeps its own list; revoke this phone there as well if it should lose access.) */
    fun forgetHost() {
        hub?.release()
        hub = null
        store.clear()
    }

    private fun buildHub(host: PairedHost) {
        val h = ControllerHub(host, identity, rtc, { offer -> DownloadsSink(this, offer.name, offer.transferId) })
        h.onRemoteClipboard = { text -> writeClipboard(text); h.clipboard?.noteApplied(text) }
        h.onRelaysLearned = { relays -> store.save(host.copy(relays = relays)) }
        hub = h
    }

    private fun writeClipboard(text: String) {
        main.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ConnectQuick", text))
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                // First callback is the current network; later ones mean it changed (Wi-Fi <-> mobile data).
                if (last != null && last != network) hub?.networkChanged()
                last = network
            }
            override fun onLost(network: Network) {
                if (last == network) last = null
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm.registerDefaultNetworkCallback(callback)
        }
    }

    companion object {
        private const val GRACE_MS = 60_000L
    }
}

val Activity.connectQuick: ConnectQuickApp get() = application as ConnectQuickApp
