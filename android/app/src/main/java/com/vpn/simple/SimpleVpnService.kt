package com.vpn.simple

import android.content.Intent
import android.net.VpnService
import android.os.Build
import io.github.oviron.libmihomo.Clash
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SimpleVpnService : VpnService() {

    // Starting and stopping the tunnel both cross into native code that can block for a
    // moment, so they run on one worker thread: the main looper stays free, and a
    // disconnect can never interleave with the connect queued behind it.
    private val worker = Executors.newSingleThreadExecutor()

    private var tunFd: Int = -1

    // Bumped for every command. A command that finds a newer one queued behind it drops
    // out, so impatient tapping settles on whatever the user asked for last.
    private var commandCounter = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = ++commandCounter
        if (intent?.action == ACTION_DISCONNECT) {
            worker.execute {
                if (id == commandCounter) {
                    stopNative()
                    sendBroadcast(
                        Intent(ACTION_DISCONNECTED).setPackage(packageName).putExtra(EXTRA_MESSAGE, "Disconnected"),
                    )
                }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // A sticky restart arrives with a null intent and no profile. The tunnel lives in
        // this process, so there is nothing to resume: stop quietly rather than reporting
        // a failure the user never caused.
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
        if (profile == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        worker.execute { connect(id, profile, startId) }
        return START_STICKY
    }

    private fun connect(id: Int, profile: String, startId: Int) {
        if (id != commandCounter) return // superseded while queued
        if (running) stopNative() // reconnect: the old tunnel goes first

        try {
            val profileFile = File(profile)
            if (!profileFile.isFile) {
                fail(id, startId, "Profile file is missing")
                return
            }

            // Never hand mihomo a profile that can leave through a USA node.
            val filtered = ProfileFilter.apply(profileFile.readText())

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
                    fail(id, startId, "Preparing the tunnel timed out. Check the connection and try again.")
                    return
                }
            }
            setupError.get()?.let {
                fail(id, startId, "The profile was rejected: $it")
                return
            }
            if (id != commandCounter) return // superseded while setting up

            val tun = Builder()
                .setSession("Bypass")
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .setMtu(1400)
                .establish()
            if (tun == null) {
                fail(id, startId, "Android denied the VPN interface")
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
            sendBroadcast(Intent(ACTION_CONNECTED).setPackage(packageName))
        } catch (t: Throwable) {
            fail(id, startId, t.message ?: "Unable to start the tunnel")
        }
    }

    private fun stopNative() {
        runCatching { Clash.stopTun() }
        tunFd = -1
        running = false
    }

    private fun fail(id: Int, startId: Int, message: String) {
        // Only the latest command may report failure; an older one has already been
        // replaced on screen by the newer request.
        if (id == commandCounter) {
            sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
        }
        stopNative()
        stopSelf(startId)
    }

    override fun onDestroy() {
        // stopSelf() for a superseded command is cancelled by Android, so reaching here
        // while the tunnel is up means the service is genuinely going away and the
        // tunnel has to be released now.
        if (running) stopNative()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val ACTION_ERROR = "com.vpn.simple.ERROR"
        const val ACTION_CONNECTED = "com.vpn.simple.CONNECTED"
        const val ACTION_DISCONNECTED = "com.vpn.simple.DISCONNECTED"
        const val ACTION_DISCONNECT = "com.vpn.simple.DISCONNECT"
        const val EXTRA_MESSAGE = "message"

        /** How long to wait for the core to report back on the profile. */
        private const val SETUP_TIMEOUT_SECONDS = 120L

        var running: Boolean = false
            private set
    }
}
