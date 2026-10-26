package com.octopus.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow

enum class SessionServiceState { STOPPED, STARTING, RUNNING, FAILED }

interface SessionServiceHost {
    val sessionServiceState: MutableStateFlow<SessionServiceState>
    fun onSessionServiceStopped()
}

/** User starts this while the workbench is visible. No automatic background restart. */
class AgentSessionService : Service() {
    private val host get() = application as SessionServiceHost

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台会话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "用户主动启动的虚拟 App 会话"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, AgentSessionService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_octopus)
                .setContentTitle("八爪鱼后台会话")
                .setContentText("可回到工作台接管，或停止全部后台会话")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            host.sessionServiceState.value = SessionServiceState.RUNNING
        } catch (_: Exception) {
            host.sessionServiceState.value = SessionServiceState.FAILED
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null restart intent never authorizes recreating App sessions.
        if(intent == null || intent.action == STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if(host.sessionServiceState.value != SessionServiceState.FAILED)
            host.sessionServiceState.value = SessionServiceState.STOPPED
        host.onSessionServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STOP = "com.octopus.agent.STOP_SESSIONS"
        const val CHANNEL = "agent-sessions"
        const val NOTIFICATION = 101
    }
}
