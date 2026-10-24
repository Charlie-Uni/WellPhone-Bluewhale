package com.octopus.core

data class LoadSample(val imeVisible: Boolean, val busy: Boolean, val reliable: Boolean)
data class Pacing(val observationsPerSecond: Double, val actionGapMillis: Long, val conservative: Boolean)
class LoadGovernor {
    private var stableSamples = 0
    private var conservative = true
    fun sample(load: LoadSample): Pacing {
        if (!load.reliable || load.imeVisible || load.busy) {
            stableSamples = 0
            conservative = true
        } else if (++stableSamples >= 3) conservative = false
        return if (conservative) Pacing(0.5, 1_000, true) else Pacing(2.0, 500, false)
    }
}
