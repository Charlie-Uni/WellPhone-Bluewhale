package com.octopus.agent

import android.app.Application
import com.octopus.agent.storage.LocalStore
import com.octopus.core.SessionBroker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow

class OctopusApplication : Application(), SessionServiceHost {
    override val sessionServiceState = MutableStateFlow(SessionServiceState.STOPPED)
    private val controllerDelegate = lazy { WorkbenchController(this) }
    val controller by controllerDelegate
    override fun onSessionServiceStopped() {
        if(controllerDelegate.isInitialized()) controller.stopSessions()
    }
    val broker = SessionBroker()
    val calendarWriter = Mutex()
    val store by lazy { LocalStore(this) }
}
