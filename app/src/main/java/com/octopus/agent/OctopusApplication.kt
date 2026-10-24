package com.octopus.agent

import android.app.Application
import com.octopus.agent.storage.LocalStore
import com.octopus.core.SessionBroker

class OctopusApplication : Application() {
    val broker = SessionBroker()
    val store by lazy { LocalStore(this) }
}
