package com.no5.helm

import com.no5.domain.EndpointHistory

/** User-requested address suggestion only. Never selects a source, connects or imports keys. */
internal object KnownLanEndpointHistory {
    const val PI_ADDRESS = "https://lysmarine.local:9443"

    fun include(existing: List<String>): List<String> {
        if (PI_ADDRESS in existing) return existing
        // Keep the active/recent order. Only the oldest entry is displaced when all five
        // slots are occupied; an unvisited suggestion must not pretend to be most recent.
        return existing.take(EndpointHistory.MAX_ENTRIES - 1) + PI_ADDRESS
    }
}
