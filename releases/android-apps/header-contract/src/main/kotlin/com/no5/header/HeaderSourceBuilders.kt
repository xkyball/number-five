package com.no5.header

/** HOME parity: the current app header is literal context; gateway notice and theme button sit below it. */
object HomeHeaderSnapshotBuilder {
    fun build(publication: HeaderPublication, theme: HeaderTheme, appsVisible: Boolean = false): HeaderSnapshotV1 {
        require(publication.packageName == HeaderApps.HOME)
        return HeaderSnapshotV1(publication, if (appsVisible) HeaderPage.APPS else HeaderPage.HOME,
            theme, HeaderProfile.HOME_CONTEXT, HeaderContext("NO5 · HOME", "STELLENBOSCH · AN BORD"))
    }
}

/** Exact native MAP header inputs. Legacy transport READY alone carries no measurement/provenance proof. */
data class MapHeaderSourceInput(
    val serverId: String?,
    val sourceDetail: String,
    val transport: HeaderTransport,
    val sourceId: String? = null,
    val observedAtEpochMs: Long? = null,
    val quality: HeaderQuality = HeaderQuality.UNKNOWN,
    val provenance: HeaderProvenance = HeaderProvenance.UNKNOWN,
    val localSimulation: Boolean = false,
    /** Optional display label; serverId remains the unchanged backend-session identity. */
    val sourceLabel: String? = null,
)

object MapHeaderSnapshotBuilder {
    fun build(publication: HeaderPublication, page: HeaderPage, confirmedTheme: HeaderTheme,
              source: MapHeaderSourceInput): HeaderSnapshotV1 {
        require(publication.packageName == HeaderApps.MAP)
        require(HeaderApps.profile(HeaderApps.MAP, page) == HeaderProfile.BRAND_SOURCE)
        return HeaderSnapshotV1(publication, page, confirmedTheme, HeaderProfile.BRAND_SOURCE,
            HeaderContext("EXPLORE FURTHER", "BY WATER"),
            HeaderSource(if (source.localSimulation) "LOKALE SIMULATION" else source.sourceLabel ?: source.serverId ?: "Kein Helm-Snapshot",
                source.sourceDetail, source.sourceId, source.serverId, source.transport, source.quality,
                if (source.localSimulation) HeaderProvenance.SIMULATED else source.provenance),
            source.observedAtEpochMs)
    }
}
