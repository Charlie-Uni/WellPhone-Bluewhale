package com.octopus.agent

import android.app.Application
import com.octopus.agent.storage.LocalStore
import com.octopus.core.SessionBroker
import kotlinx.coroutines.sync.Mutex

class OctopusApplication : Application() {
    val broker = SessionBroker()
    val calendarWriter = Mutex()
    val store by lazy { LocalStore(this) }
}
