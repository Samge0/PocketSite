package com.samge.pocketsite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Hosts one StaticSiteServer per running site. Kept alive via foreground notification. */
class SiteServerService : Service() {
    companion object {
        const val CHANNEL_ID = "siteserver"
        const val NOTIF_ID = 1001
        const val ACTION_START_SITE = "com.samge.pocketsite.START_SITE"
        const val ACTION_STOP_SITE = "com.samge.pocketsite.STOP_SITE"
        const val ACTION_STOP_ALL = "com.samge.pocketsite.STOP_ALL"
        const val EXTRA_SITE = "site"
        const val EXTRA_PORT = "port"

        // siteName -> server
        private val servers = ConcurrentHashMap<String, StaticSiteServer>()

        fun runningSites(): Set<String> = servers.keys.toSet()

        fun isRunning(site: String): Boolean = servers[site]?.isRunning == true

        fun runningCount(): Int = servers.size

        fun portOf(site: String): Int? = servers[site]?.let { if (it.isRunning) it.port else null }

        /** Static helpers callable from anywhere (post Intents to the service). */
        fun stopSiteStatic(ctx: android.content.Context, site: String) {
            val intent =
                android.content.Intent(ctx, SiteServerService::class.java)
                    .setAction(ACTION_STOP_SITE)
                    .putExtra(EXTRA_SITE, site)
            ctx.startService(intent)
        }

        fun startSiteStatic(ctx: android.content.Context, site: String, port: Int) {
            val intent =
                android.content.Intent(ctx, SiteServerService::class.java)
                    .setAction(ACTION_START_SITE)
                    .putExtra(EXTRA_SITE, site)
                    .putExtra(EXTRA_PORT, port)
            ctx.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "站点服务器", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SITE -> {
                val site = intent.getStringExtra(EXTRA_SITE) ?: return finishIfIdle()
                val port = intent.getIntExtra(EXTRA_PORT, SitesRepository.sitePort(this, site))
                startSite(site, port)
            }
            ACTION_STOP_SITE -> {
                val site = intent.getStringExtra(EXTRA_SITE) ?: return finishIfIdle()
                stopSite(site)
            }
            ACTION_STOP_ALL -> {
                servers.keys.toList().forEach { stopSite(it) }
            }
        }
        return finishIfIdle()
    }

    private fun finishIfIdle(): Int {
        if (servers.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startSite(site: String, port: Int) {
        if (isRunning(site)) {
            updateNotification()
            return
        }
        val root = File(SitesRepository.rootDir(this), site)
        if (!root.isDirectory) {
            // site content missing (data cleared / config imported without content)
            android.widget.Toast.makeText(
                this,
                "站点「$site」数据不存在，请重新导入或更新站点包",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            stopSite(site)
            return
        }
        val s = StaticSiteServer(port, root)
        if (s.start()) {
            servers[site] = s
            startForeground(NOTIF_ID, buildNotification())
        } else {
            // most likely cause: port taken by another app since allocation
            android.widget.Toast.makeText(
                this,
                "「$site」启动失败：端口 $port 被占用，请在站点详情中更换端口后重试",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            updateNotification()
        }
    }

    private fun stopSite(site: String) {
        servers.remove(site)?.stop()
        if (servers.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun buildNotification(): Notification {
        val running = servers.keys.joinToString(" · ")
        val open =
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopAll =
            PendingIntent.getService(
                this, 1,
                Intent(this, SiteServerService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("PocketSite 服务运行中")
            .setContentText(if (running.isEmpty()) "启动中…" else running)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "全部停止", stopAll)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        servers.values.forEach { it.stop() }
        servers.clear()
        super.onDestroy()
    }
}
