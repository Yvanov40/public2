package fr.telecommande

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Le téléphone se présente à la TV comme un périphérique Bluetooth HID
 * (un clavier + des touches multimédia), exactement comme une télécommande Bluetooth.
 */
@SuppressLint("MissingPermission")
object Hid {

    enum class State { UNAVAILABLE, BT_OFF, STARTING, READY, CONNECTING, CONNECTED }

    private const val ID_KB = 1
    private const val ID_CC = 2

    /** Descripteur HID : rapport 1 = clavier, rapport 2 = touches « Consumer » (multimédia). */
    private val DESCRIPTOR = bytes(
        // --- Clavier ---
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KB,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02,             // modificateurs
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,             // octet réservé
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, // 6 touches
        0xC0,
        // --- Consumer control ---
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CC,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private lateinit var ctx: Context
    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var proxyRequested = false
    private val main = Handler(Looper.getMainLooper())
    /** Un seul fil pour tout envoi : l'ordre des appuis est conservé (séquences comprises). */
    val worker = Executors.newSingleThreadExecutor()
    /** Fil séparé pour les réponses à la TV (jamais bloqué par un raccourci en cours). */
    private val callbacks = Executors.newSingleThreadExecutor()

    var state = State.BT_OFF; private set
    var host: BluetoothDevice? = null; private set
    var message: String? = null; private set
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun set(s: State, msg: String? = null) {
        state = s; message = msg
        listeners.toList().forEach { it() }
    }

    fun init(c: Context) {
        ctx = c.applicationContext
        adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> start()
                    BluetoothAdapter.STATE_OFF -> { reset(); set(State.BT_OFF) }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    val isEnabled get() = adapter?.isEnabled == true
    val btAdapter get() = adapter

    private fun reset() {
        hid = null; registered = false; proxyRequested = false; host = null
    }

    /** À appeler une fois les autorisations accordées. */
    fun start() {
        val a = adapter ?: return set(State.UNAVAILABLE, "Ce téléphone n'a pas de Bluetooth.")
        try {
            if (!a.isEnabled) return set(State.BT_OFF)
            val h = hid
            if (h != null) {
                if (!registered) register(h)
                return
            }
            if (proxyRequested) return
            proxyRequested = true
            set(State.STARTING)
            val ok = a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile != BluetoothProfile.HID_DEVICE || proxy == null) return
                    hid = proxy as BluetoothHidDevice
                    register(proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    reset(); set(State.BT_OFF)
                }
            }, BluetoothProfile.HID_DEVICE)
            if (!ok) {
                proxyRequested = false
                set(State.UNAVAILABLE, "Ce téléphone ne permet pas aux applis de jouer le rôle de clavier/télécommande Bluetooth (profil HID Device désactivé par le fabricant).")
            }
        } catch (e: SecurityException) {
            proxyRequested = false
            set(State.UNAVAILABLE, "Autorisation Bluetooth refusée.")
        }
    }

    private fun register(h: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Télécommande", "Télécommande Bluetooth", "Télécommande",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
        )
        try {
            val ok = h.registerApp(sdp, null, null, callbacks, callback)
            if (!ok) set(State.UNAVAILABLE, "Impossible d'activer la télécommande Bluetooth. Une autre appli (clavier Bluetooth…) l'utilise peut-être déjà : fermez-la puis réessayez.")
        } catch (e: SecurityException) {
            set(State.UNAVAILABLE, "Autorisation Bluetooth refusée.")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            main.post {
                this@Hid.registered = registered
                if (registered) {
                    set(State.READY)
                    if (pluggedDevice != null) connect(pluggedDevice) else autoConnect()
                } else {
                    host = null
                    if (state != State.UNAVAILABLE) set(if (isEnabled) State.STARTING else State.BT_OFF)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            main.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        device?.let { Prefs.lastDevice = it.address }
                        set(State.CONNECTED)
                    }
                    BluetoothProfile.STATE_CONNECTING -> if (host == null) set(State.CONNECTING)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (host == null || host?.address == device?.address) {
                            host = null
                            if (registered) set(State.READY)
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            try {
                val data = if (id.toInt() == ID_CC) ByteArray(2) else ByteArray(8)
                hid?.replyReport(device, type, id, data)
            } catch (_: Exception) {}
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            try { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) } catch (_: Exception) {}
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            main.post { host = null; if (registered) set(State.READY) }
        }
    }

    private fun autoConnect() {
        val addr = Prefs.lastDevice ?: return
        val dev = try { adapter?.getRemoteDevice(addr) } catch (_: Exception) { null } ?: return
        if (dev.bondState == BluetoothDevice.BOND_BONDED) connect(dev)
    }

    fun connect(device: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) { start(); return }
        try {
            host?.let { if (it.address != device.address) h.disconnect(it) }
            set(State.CONNECTING, device.name ?: device.address)
            if (!h.connect(device)) set(State.READY, "La connexion à ${device.name ?: device.address} a échoué.")
        } catch (e: SecurityException) {
            set(State.READY, "Autorisation Bluetooth refusée.")
        }
    }

    fun disconnect() {
        val h = hid ?: return
        val d = host ?: return
        try { h.disconnect(d) } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            hid?.unregisterApp()
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {}
        reset()
    }

    val connected get() = state == State.CONNECTED && host != null

    // ------------------------------------------------------------------ Envoi

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        try { h.sendReport(d, id, data) } catch (_: Exception) {}
    }

    private fun kbReport(code: Int, mod: Int = 0) = bytes(mod, 0, code, 0, 0, 0, 0, 0)

    private fun downNow(k: Key) {
        when (k.kind) {
            Kind.KB -> send(ID_KB, kbReport(k.code))
            Kind.CC -> send(ID_CC, bytes(k.code and 0xFF, (k.code shr 8) and 0xFF))
        }
    }

    private fun upNow(k: Key) {
        when (k.kind) {
            Kind.KB -> send(ID_KB, ByteArray(8))
            Kind.CC -> send(ID_CC, ByteArray(2))
        }
    }

    /** Touche enfoncée (le doigt reste posé : la TV répète elle-même la touche). */
    fun down(k: Key) = worker.execute { downNow(k) }
    fun up(k: Key) = worker.execute { upNow(k) }

    /** Appui bref (ou long si [Key.holdMs]). */
    fun tap(k: Key) = worker.execute { tapNow(k) }

    private fun tapNow(k: Key) {
        downNow(k)
        Thread.sleep(if (k.holdMs > 0) k.holdMs else 70)
        upNow(k)
    }

    fun type(text: String) = worker.execute { typeNow(text) }

    private fun typeNow(text: String) {
        for (c in Keys.plain(text)) {
            val (code, shift) = Keys.charToHid(c) ?: continue
            send(ID_KB, kbReport(code, if (shift) 0x02 else 0))
            Thread.sleep(25)
            send(ID_KB, ByteArray(8))
            Thread.sleep(25)
        }
    }

    /**
     * Joue une séquence de raccourci. Étapes :
     *  k:<touche>  appui sur une touche
     *  w:<ms>      pause
     *  t:<texte>   saisie de texte
     */
    fun play(steps: List<String>) = worker.execute {
        for (s in steps) {
            if (!connected) break
            val arg = s.substringAfter(':', "")
            when (s.substringBefore(':')) {
                "k" -> Keys.get(arg)?.let { tapNow(it); Thread.sleep(Prefs.stepDelay.toLong()) }
                "w" -> Thread.sleep((arg.toLongOrNull() ?: 500L).coerceIn(0, 30_000))
                "t" -> { typeNow(arg); Thread.sleep(Prefs.stepDelay.toLong()) }
            }
        }
    }
}
