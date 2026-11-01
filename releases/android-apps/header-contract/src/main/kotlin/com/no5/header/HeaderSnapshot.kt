package com.no5.header

/** App data has no navigation field. These are the original system action codes. */
enum class SystemNavigationAction(val keyCode: Int) {
    LOGO_HOME(3), HOME(3), BACK(4), RECENTS(187),
}

// Append only: the separate read-only consumer encodes these values as stable ordinal bytes.
enum class HeaderPage { HOME, APPS, HELM, AUDIO, SONAR, SETTINGS, CHART, ROUTES, AIS, LAYERS, DASHBOARD }
enum class HeaderProfile { BASE_SYSTEM, HOME_CONTEXT, BRAND_SOURCE, MARINE_SAFETY }
enum class HeaderTheme { DAY, RED_NIGHT }
enum class HeaderQuality { LIVE, STALE, LOST, INVALID, UNAVAILABLE, UNKNOWN }
enum class HeaderProvenance { REAL, SIMULATED, UNKNOWN }
enum class HeaderTransport { LOADING, READY, EMPTY, OFFLINE, STALE, ERROR, UNKNOWN }
enum class HeaderUnit { RPM, KNOTS, KMH, MPH }

object HeaderApps {
    const val HOME = "com.no5.home"
    const val MAP = "com.no5.map"
    const val HELM = "com.no5.helm"

    fun allowed(packageName: String) = packageName == HOME || packageName == MAP || packageName == HELM

    fun profile(packageName: String, page: HeaderPage): HeaderProfile? = when (packageName) {
        HOME -> when (page) {
            HeaderPage.HOME, HeaderPage.APPS -> HeaderProfile.HOME_CONTEXT
            else -> null
        }
        MAP -> when (page) {
            HeaderPage.CHART, HeaderPage.ROUTES, HeaderPage.AIS, HeaderPage.LAYERS, HeaderPage.SETTINGS -> HeaderProfile.BRAND_SOURCE
            else -> null
        }
        HELM -> when (page) {
            HeaderPage.HELM -> HeaderProfile.BRAND_SOURCE
            HeaderPage.AUDIO, HeaderPage.SONAR, HeaderPage.SETTINGS, HeaderPage.DASHBOARD -> HeaderProfile.MARINE_SAFETY
            else -> null
        }
        else -> null
    }
}

/** Monotonic transport timestamps must never be used as measurement timestamps. */
data class HeaderPublication(
    val packageName: String,
    val producerSession: String,
    val foregroundEpoch: Long,
    val pageGeneration: Long,
    val sourceSessionId: String?,
    val sequence: Long,
    val publishedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
)

data class HeaderFreshness(val staleAfterMs: Long = 5_000, val lostAfterMs: Long = 30_000)

/** Fully immutable: only scalars, enums, strings and other immutable data classes. */
data class HeaderMeasurement(
    val value: Double? = null,
    val unit: HeaderUnit,
    val sourceId: String? = null,
    val sourceObservedAtEpochMs: Long? = null,
    val quality: HeaderQuality = HeaderQuality.UNKNOWN,
    val provenance: HeaderProvenance = HeaderProvenance.UNKNOWN,
    val freshness: HeaderFreshness = HeaderFreshness(),
) {
    /** Age can degrade quality. Receipt/publication never upgrades it or changes the observation. */
    fun at(nowEpochMs: Long): HeaderMeasurement {
        val status = measurementQuality(quality, sourceId, sourceObservedAtEpochMs, freshness, nowEpochMs)
        val usable = value?.takeIf { it.isFinite() && it >= 0.0 }
        val result = when {
            value != null && usable == null -> HeaderQuality.INVALID
            value == null && (status == HeaderQuality.LIVE || status == HeaderQuality.STALE) -> HeaderQuality.UNKNOWN
            else -> status
        }
        return copy(value = usable.takeIf { result == HeaderQuality.LIVE || result == HeaderQuality.STALE }, quality = result)
    }
}

internal fun measurementQuality(
    quality: HeaderQuality,
    sourceId: String?,
    observedAt: Long?,
    freshness: HeaderFreshness,
    nowEpochMs: Long,
): HeaderQuality {
    if (quality != HeaderQuality.LIVE && quality != HeaderQuality.STALE) return quality
    if (sourceId.isNullOrBlank() || observedAt == null || observedAt < 0 || nowEpochMs < 0) return HeaderQuality.UNKNOWN
    if (freshness.staleAfterMs <= 0 || freshness.lostAfterMs < freshness.staleAfterMs) return HeaderQuality.INVALID
    // Double subtraction avoids signed Long overflow; threshold semantics match HELM's domain.
    val age = nowEpochMs.toDouble() - observedAt.toDouble()
    return when {
        age < -5_000 -> HeaderQuality.STALE
        age > freshness.lostAfterMs -> HeaderQuality.LOST
        age > freshness.staleAfterMs || quality == HeaderQuality.STALE -> HeaderQuality.STALE
        else -> HeaderQuality.LIVE
    }
}

data class HeaderExtras(
    val portRpm: HeaderMeasurement? = null,
    val starboardRpm: HeaderMeasurement? = null,
    val sog: HeaderMeasurement? = null,
) {
    fun at(nowEpochMs: Long) = HeaderExtras(portRpm?.at(nowEpochMs), starboardRpm?.at(nowEpochMs), sog?.at(nowEpochMs))
    fun isEmpty() = portRpm == null && starboardRpm == null && sog == null
    // No vessel clock field: the existing HELM clock slot has no connected source.
}

data class HeaderSource(
    val label: String? = null,
    val detail: String? = null,
    val sourceId: String? = null,
    val backendSessionId: String? = null,
    val transport: HeaderTransport = HeaderTransport.UNKNOWN,
    val quality: HeaderQuality = HeaderQuality.UNKNOWN,
    val provenance: HeaderProvenance = HeaderProvenance.UNKNOWN,
    val freshness: HeaderFreshness = HeaderFreshness(),
)

data class HeaderContext(val title: String? = null, val subtitle: String? = null, val pageStatus: String? = null)

data class HeaderSnapshotV1(
    val publication: HeaderPublication,
    val pageId: HeaderPage,
    val theme: HeaderTheme,
    val profile: HeaderProfile,
    val context: HeaderContext = HeaderContext(),
    val source: HeaderSource = HeaderSource(),
    /** Original source envelope observation; each numeric field also keeps its OWN timestamp. */
    val sourceObservedAtEpochMs: Long? = null,
    val extras: HeaderExtras = HeaderExtras(),
    val schemaVersion: Int = 1,
) {
    fun at(nowEpochMs: Long) = copy(
        source = source.copy(quality = measurementQuality(source.quality, source.sourceId,
            sourceObservedAtEpochMs, source.freshness, nowEpochMs)),
        extras = extras.at(nowEpochMs),
    )
}
