package fi.arabella.ptt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.arabella.ptt.transport.Transport

/**
 * Foreground service that owns the [PttEngine] while connected, so the
 * intercom keeps running with the screen off or the activity gone.
 *
 * Lifecycle: the activity binds while visible (BIND_AUTO_CREATE), so the
 * service exists whenever the UI is up. [connect] additionally promotes it to
 * a started foreground service with a partial wake lock and a low-latency
 * Wi-Fi lock; [disconnect] releases all of that and stops the service, which
 * then dies as soon as the activity unbinds.
 *
 * The ongoing notification mirrors the engine's status line and carries a
 * Disconnect action, so the session can be ended without reopening the app.
 */
class PttService : Service() {

    inner class LocalBinder : Binder() {
        val service: PttService get() = this@PttService
    }

    lateinit var engine: PttEngine
        private set

    /** Last status string from the engine; the activity shows it when it (re)binds. */
    @Volatile var lastStatus: String = "Not connected"
        private set

    /** Set by the bound activity. Called on whichever thread reported the status. */
    @Volatile var statusListener: ((String) -> Unit)? = null

    /** Last roster the engine published; the activity shows it when it (re)binds. */
    @Volatile var lastRoster: List<Peer> = emptyList()
        private set

    /** Set by the bound activity. Called on the engine's heartbeat or a transport thread. */
    @Volatile var rosterListener: ((List<Peer>) -> Unit)? = null

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    /** Creates the engine and the notification channel; the engine lives as long as the service. */
    override fun onCreate() {
        super.onCreate()
        engine = PttEngine(this, ::onStatus, ::onRoster)
        createChannel()
    }

    /** Hands the activity a local binder; the service is in-process only. */
    override fun onBind(intent: Intent?): IBinder = binder

    /** Handles the notification's Disconnect action; plain starts (from [connect]) need no work here. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) disconnect()
        // A restart after the process was killed has no transports to resume; stay dead.
        return START_NOT_STICKY
    }

    /** Last line of defence: tear the session down if the system destroys the service. */
    override fun onDestroy() {
        engine.disconnect()
        releaseLocks()
        super.onDestroy()
    }

    /** Starts the transports and keeps the phone awake until [disconnect]. Call from the foreground UI. */
    fun connect(transports: List<Transport>) {
        // Started + foreground so the service outlives the activity's unbind.
        ContextCompat.startForegroundService(this, Intent(this, PttService::class.java))
        try {
            showForeground("Connecting…")
        } catch (e: Exception) {   // e.g. ForegroundServiceStartNotAllowedException when not in the foreground
            stopSelf()
            onStatus("Can't start service: ${e.message}")
            return
        }
        acquireLocks()
        engine.connect(transports)
    }

    /** Stops the transports, releases the locks and leaves the foreground; safe to call when already idle. */
    fun disconnect() {
        val wasConnected = engine.isConnected
        engine.disconnect()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (wasConnected) onStatus("Disconnected")
    }

    /** Engine status sink: remembers the line, forwards it to the UI and mirrors it in the notification. */
    private fun onStatus(msg: String) {
        lastStatus = msg
        statusListener?.invoke(msg)
        if (engine.isConnected) showForeground(msg)
    }

    /** Engine roster sink: remembers the list, forwards it to the UI and puts the head count in the notification title. */
    private fun onRoster(peers: List<Peer>) {
        lastRoster = peers
        rosterListener?.invoke(peers)
        if (engine.isConnected) showForeground(lastStatus)
    }

    // ---- foreground notification -------------------------------------------------

    /** (Re)posts the foreground notification. Re-calling startForeground is the documented way to update it. */
    private fun showForeground(text: String) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), foregroundTypes())
    }

    /** Foreground service types to declare: always connectedDevice, plus microphone where the API has it. */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // Mic access from the background needs the microphone type on Android 11+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }

    /** Silent, ongoing notification: tap opens the activity, the action disconnects. */
    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, PttService::class.java).setAction(ACTION_DISCONNECT),
            flags
        )
        val online = lastRoster.size
        val title = getString(R.string.notification_title) + if (online > 0) " · $online online" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_disconnect), disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Low-importance channel so the ongoing notification never makes a sound. */
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    // ---- wake / Wi-Fi locks --------------------------------------------------------

    /**
     * Keeps the CPU and the Wi-Fi radio awake for the session. Idempotent.
     *
     * Wi-Fi needs two locks because of how the platform scopes them:
     * - FULL_LOW_LATENCY only takes effect while the screen is on and the app is in the foreground.
     * - FULL_HIGH_PERF keeps the radio out of power save with the screen off or the app in the
     *   background, which is what LAN multicast and Aware links need during a screen-off session.
     * Holding both is the documented combination: low latency wins while visible, high perf
     * otherwise. Android 14 deprecates HIGH_PERF and silently turns it into a LOW_LATENCY lock,
     * so there it adds nothing and is skipped; screen-off Wi-Fi then runs in normal power save.
     */
    @SuppressLint("WakelockTimeout") // held for the whole session, released in disconnect()
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ptt:engine").also { it.acquire() }
        }
        if (wifiLocks.isEmpty()) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val modes = mutableListOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) modes += highPerfWifiMode()
            for (mode in modes) {
                wifiLocks += wm.createWifiLock(mode, "ptt:wifi:$mode").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            }
        }
    }

    /** Isolated so the deprecation (API 34, where it aliases LOW_LATENCY anyway) is suppressed in one place. */
    @Suppress("DEPRECATION")
    private fun highPerfWifiMode(): Int = WifiManager.WIFI_MODE_FULL_HIGH_PERF

    /** Releases whatever [acquireLocks] took; safe when nothing is held. */
    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        for (lock in wifiLocks) if (lock.isHeld) lock.release()
        wifiLocks.clear()
    }

    companion object {
        const val ACTION_DISCONNECT = "fi.arabella.ptt.action.DISCONNECT"
        private const val CHANNEL_ID = "ptt"
        private const val NOTIFICATION_ID = 1
    }
}
