package com.octopus.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class SessionTestApplication : Application(), SessionServiceHost {
    override val sessionServiceState = MutableStateFlow(SessionServiceState.STOPPED)
    var stopped = 0
    override fun onSessionServiceStopped() { stopped++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=SessionTestApplication::class)
class AgentSessionServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as SessionTestApplication

    @Test fun foregroundSessionUsesQuietGenericNotificationAndExplicitStop() {
        val controller = Robolectric.buildService(AgentSessionService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(SessionServiceState.RUNNING,app.sessionServiceState.value)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,service.foregroundServiceType)
            val notification = shadowOf(service).lastForegroundNotification
            assertEquals("八爪鱼后台会话",notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(Notification.VISIBILITY_SECRET,notification.visibility)
            assertEquals("停止",notification.actions.single().title.toString())
            val channel = service.getSystemService(NotificationManager::class.java).getNotificationChannel(AgentSessionService.CHANNEL)
            assertEquals(NotificationManager.IMPORTANCE_LOW,channel.importance)
            assertNull(channel.sound);assertFalse(channel.shouldVibrate());assertFalse(channel.canShowBadge())
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(Intent(app,AgentSessionService::class.java),0,1))
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
        assertEquals(SessionServiceState.STOPPED,app.sessionServiceState.value)
        assertEquals(1,app.stopped)
    }

    @Test fun notificationStopEndsServiceWithoutOpeningWorkbench() {
        val controller = Robolectric.buildService(AgentSessionService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app,AgentSessionService::class.java).setAction(AgentSessionService.STOP),0,2)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(app).nextStartedActivity)
        controller.destroy()
        assertEquals(1,app.stopped)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun nullRestartIntentDoesNotAuthorizeResumption() {
        val controller = Robolectric.buildService(AgentSessionService::class.java).create()
        assertEquals(Service.START_NOT_STICKY,controller.get().onStartCommand(null,0,3))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun startForegroundFailureNeverReportsReadyAndStillRevokesSessions() {
        val controller = Robolectric.buildService(AgentSessionService::class.java)
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("denied"))
        controller.create()
        assertEquals(SessionServiceState.FAILED,app.sessionServiceState.value)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
        assertEquals(SessionServiceState.FAILED,app.sessionServiceState.value)
        assertEquals(1,app.stopped)
    }

    @Test fun manifestRestrictsExternalStartsAndDeclaresSpecialUsePermission() {
        val service = app.packageManager.getServiceInfo(ComponentName(app,AgentSessionService::class.java),0)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,service.foregroundServiceType)
        val permissions = app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }
}
