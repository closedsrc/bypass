package com.vpn.simple

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.oviron.libmihomo.Clash
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SimpleVpnService : VpnService() {

    // Starting and stopping the tunnel both cross into native code that can block for a
    // moment, so they run on one worker thread: the main looper stays free, and a
    // disconnect can never interleave with the connect queued behind it.
    private val worker = Executors.newSingleThreadExecutor()

    // Samples live throughput while connected so the notification and the UI show real
    // numbers rather than an animation.
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var tickTask: ScheduledFuture<*>? = null

    private var tunFd: Int = -1

    // Bumped for every command. A command that finds a newer one queued behind it drops
    // out, so impatient tapping settles on whatever the user asked for last.
    private var commandCounter = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = ++commandCounter
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                VpnStateHolder.setPhase(VpnPhase.DISCONNECTING)
                publishNotification()
                worker.execute {
                    if (id == commandCounter) {
                        stopNative()
                        VpnStateHolder.setPhase(VpnPhase.DISCONNECTED)
                        stopForegroundCompat()
                        sendBroadcast(Intent(ACTION_DISCONNECTED).setPackage(packageName))
                    }
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }

        // A sticky restart arrives with a null intent and no profile. The tunnel lives in
        // this process, so there is nothing to resume: stop quietly rather than reporting
        // a failure the user never caused.
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
        if (profile == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // The tunnel must be visible for as long as it is up, so promote to foreground
        // before the (potentially slow) native setup begins.
        startForegroundCompat()
        VpnStateHolder.setPhase(VpnPhase.CONNECTING)
        publishNotification()

        worker.execute { connect(id, profile, startId) }
        return START_STICKY
    }

    private fun connect(id: Int, profile: String, startId: Int) {
        if (id != commandCounter) return // superseded while queued
        if (running) stopNative() // reconnect: the old tunnel goes first

        try {
            val profileFile = File(profile)
            if (!profileFile.isFile) {
                fail(id, startId, "The saved profile is missing. Import it again from Settings.")
                return
            }

            // Never hand mihomo a profile that can leave through a USA node.
            val filtered = ProfileFilter.apply(profileFile.readText())
            VpnStateHolder.update {
                it.copy(usableNodes = filtered.kept.size, removedNodes = filtered.removed.size)
            }

            // mihomo reads its profile from <home-dir>/config.yaml. There is no channel
            // for a profile path in the setup call, so the file has to be in place before
            // the core is initialised; otherwise it silently runs its built-in default
            // config, which sends every connection out DIRECT.
            val home = File(filesDir, "mihomo").apply { mkdirs() }
            File(home, "config.yaml").writeText(filtered.yaml)

            Clash.load(applicationInfo.nativeLibraryDir)
            Clash.assertReady()

            // quickSetup parses and applies the profile on a goroutine, so the config is
            // not in effect when the call returns. Start the tunnel before the callback
            // lands and mihomo is still on its default config.
            val setupError = AtomicReference<String?>(null)
            val applied = CountDownLatch(1)
            Clash.quickSetup(
                "{\"home-dir\":\"${home.absolutePath}\",\"version\":${Build.VERSION.SDK_INT}}",
                "{}",
            ) { result ->
                if (!result.isNullOrEmpty()) setupError.set(result)
                applied.countDown()
            }
            // A profile with GEOIP/GEOSITE rules makes the core fetch its geo database the
            // first time, which can take a while. Wait in short steps so a tap on the
            // switch is still answered promptly instead of queueing behind the setup.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETUP_TIMEOUT_SECONDS)
            while (!applied.await(500, TimeUnit.MILLISECONDS)) {
                if (id != commandCounter) return // superseded while setting up
                if (System.nanoTime() > deadline) {
                    fail(id, startId, "Preparing the tunnel timed out. Check your connection and try again.")
                    return
                }
            }
            setupError.get()?.let {
                fail(id, startId, "The profile was rejected: $it")
                return
            }
            if (id != commandCounter) return // superseded while setting up

            // Excluded apps never enter the tunnel at all: Android hands them straight to
            // the device's own network, so their traffic is untouched by the profile.
            val builder = Builder()
                .setSession("Bypass")
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .setMtu(1400)
            for (pkg in AppExclusions.load(this)) {
                if (pkg == packageName) continue
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "could not exclude $pkg", it) }
            }

            val tun = builder.establish()
            if (tun == null) {
                fail(id, startId, "Android denied the VPN interface. Check for another VPN app and try again.")
                return
            }

            // From here the raw fd belongs to native. Closing it in Java would abort the
            // process, so the only way back out is Clash.stopTun().
            tunFd = tun.detachFd()
            Clash.startTUN(
                tunFd,
                { fd -> protect(fd) },
                { _, _, _, _ -> "" },
                "bypass",
                "system",
                "172.19.0.1/30",
                "1.1.1.1,1.0.0.1",
                1400,
            )
            running = true
            VpnStateHolder.update {
                it.copy(
                    phase = VpnPhase.CONNECTED,
                    error = null,
                    stats = VpnStats(sessionStartedAt = System.currentTimeMillis()),
                )
            }
            startTicker()
            publishNotification()
            sendBroadcast(Intent(ACTION_CONNECTED).setPackage(packageName))
        } catch (t: Throwable) {
            fail(id, startId, t.message ?: "Unable to start the tunnel")
        }
    }

    private fun startTicker() {
        tickTask?.cancel(false)
        tickTask = ticker.scheduleWithFixedDelay({
            if (!running) return@scheduleWithFixedDelay
            runCatching {
                val now = parseTraffic(Clash.getTraffic())
                val total = parseTraffic(Clash.getTotalTraffic())
                VpnStateHolder.update {
                    it.copy(
                        stats = it.stats.copy(
                            upBytesPerSec = now.first,
                            downBytesPerSec = now.second,
                            totalUpBytes = total.first,
                            totalDownBytes = total.second,
                        ),
                    )
                }
                publishNotification()
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    /** `getTraffic` returns {"up":n,"down":n}; tolerate anything else without crashing. */
    private fun parseTraffic(json: String?): Pair<Long, Long> {
        if (json.isNullOrBlank()) return 0L to 0L
        return runCatching {
            val obj = org.json.JSONObject(json)
            obj.optLong("up", 0L) to obj.optLong("down", 0L)
        }.getOrElse { 0L to 0L }
    }

    private fun stopNative() {
        tickTask?.cancel(false)
        tickTask = null
        runCatching { Clash.stopTun() }
        tunFd = -1
        running = false
        VpnStateHolder.update { it.copy(stats = VpnStats()) }
    }

    private fun fail(id: Int, startId: Int, message: String) {
        // Only the latest command may report failure; an older one has already been
        // replaced on screen by the newer request.
        if (id == commandCounter) {
            VpnStateHolder.setPhase(VpnPhase.ERROR, message)
            sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
        }
        stopNative()
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onDestroy() {
        // stopSelf() for a superseded command is cancelled by Android, so reaching here
        // while the tunnel is up means the service is genuinely going away and the
        // tunnel has to be released now.
        if (running) stopNative()
        if (VpnStateHolder.status.value.phase != VpnPhase.ERROR) {
            VpnStateHolder.setPhase(VpnPhase.DISCONNECTED)
        }
        worker.shutdown()
        ticker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------
    // Foreground notification: the tunnel's persistent, system-level surface.
    // -----------------------------------------------------------------------

    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows whether Bypass is carrying your traffic, with live speed."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun publishNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val status = VpnStateHolder.status.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, SimpleVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle(status.phase))
            .setContentText(notificationBody(status))
            .setContentIntent(open)
            .setOngoing(status.phase == VpnPhase.CONNECTED || status.isBusy)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (status.phase == VpnPhase.CONNECTED || status.isBusy) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Disconnect",
                    disconnect,
                ).build(),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_SECRET)
        }
        return builder.build()
    }

    private fun notificationTitle(phase: VpnPhase): String = when (phase) {
        VpnPhase.CONNECTED -> "Bypass is on"
        VpnPhase.CONNECTING -> "Connecting…"
        VpnPhase.DISCONNECTING -> "Disconnecting…"
        VpnPhase.ERROR -> "Bypass stopped"
        VpnPhase.DISCONNECTED -> "Bypass is off"
    }

    private fun notificationBody(status: VpnStatus): String = when (status.phase) {
        VpnPhase.CONNECTED -> {
            val up = formatSpeed(status.stats.upBytesPerSec)
            val down = formatSpeed(status.stats.downBytesPerSec)
            val nodes = if (status.usableNodes > 0) " · ${status.usableNodes} nodes" else ""
            "$up up · $down down$nodes"
        }
        VpnPhase.ERROR -> status.error ?: "The connection stopped."
        VpnPhase.CONNECTING -> "Setting up the tunnel…"
        VpnPhase.DISCONNECTING -> "Releasing the tunnel…"
        VpnPhase.DISCONNECTED -> "Tap to open Bypass."
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val ACTION_ERROR = "com.vpn.simple.ERROR"
        const val ACTION_CONNECTED = "com.vpn.simple.CONNECTED"
        const val ACTION_DISCONNECTED = "com.vpn.simple.DISCONNECTED"
        const val ACTION_DISCONNECT = "com.vpn.simple.DISCONNECT"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "bypass.connection"
        private const val NOTIFICATION_ID = 4711

        /** How long to wait for the core to report back on the profile. */
        private const val SETUP_TIMEOUT_SECONDS = 120L

        private const val TAG = "SimpleVpnService"

        var running: Boolean = false
            private set

        fun formatSpeed(bytesPerSec: Long): String {
            val b = bytesPerSec.coerceAtLeast(0)
            return when {
                b < 1024 -> "$b B/s"
                b < 1024 * 1024 -> "%.1f KB/s".format(b / 1024.0)
                else -> "%.1f MB/s".format(b / (1024.0 * 1024.0))
            }
        }

        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0)
            return when {
                b < 1024 -> "$b B"
                b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
                b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024.0))
                else -> "%.2f GB".format(b / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}
