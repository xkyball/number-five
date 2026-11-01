package com.no5.helm.ui

import com.no5.helm.chart.ChartViewport
import com.no5.preparation.ui.RouteDocument
import com.no5.preparation.ui.RoutePoint
import kotlin.math.*

/** Ellipsoidal rhumb distance: the route's straight Mercator legs, shortest longitude arc. */
internal fun routeLegMetres(a: RoutePoint, b: RoutePoint): Double {
    val radius = 6378137.0; val e2 = 6.6943799901413165e-3; val e = sqrt(e2)
    val p = Math.toRadians(a.latitude); val q = Math.toRadians(b.latitude)
    val lon = Math.toRadians(((b.longitude - a.longitude + 540) % 360) - 180)
    if (abs(q - p) < 1e-12) return abs(lon) * radius * cos(p) / sqrt(1 - e2 * sin(p).pow(2))
    fun psi(lat: Double) = ln(tan(Math.PI / 4 + lat / 2)) - e / 2 * ln((1 + e * sin(lat)) / (1 - e * sin(lat)))
    fun meridian(lat: Double) = radius * (1 - e2) / (1 - e2 * sin(lat).pow(2)).pow(1.5)
    val n = 128; val h = (q - p) / n
    var sum = meridian(p) + meridian(q)
    for (i in 1 until n) sum += (if (i % 2 == 0) 2 else 4) * meridian(p + i * h)
    val north = sum * h / 3
    return hypot(north, north / (psi(q) - psi(p)) * lon)
}
internal fun routeFitViewport(route: RouteDocument, current: ChartViewport): ChartViewport? {
    if (route.points.isEmpty() || route.points.any { !it.valid }) return null
    val lons = route.points.map { (it.longitude + 360) % 360 }.sorted()
    val gap = lons.indices.maxBy { i -> (if (i == lons.lastIndex) lons[0] + 360 else lons[i + 1]) - lons[i] }
    val west = lons[(gap + 1) % lons.size]; val east = lons[gap].let { if (it < west) it + 360 else it }
    fun merc(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
    val south = merc(route.points.minOf { it.latitude }); val north = merc(route.points.maxOf { it.latitude })
    val lat = Math.toDegrees(2 * atan(exp((south + north) / 2)) - Math.PI / 2)
    val lon = ((west + east) / 2 + 540) % 360 - 180
    val ppm = min(current.width * .7 / max(200.0, Math.toRadians(east - west) * 6378137 * cos(Math.toRadians(lat))),
        current.height * .7 / max(200.0, (north - south) * 6378137 * cos(Math.toRadians(lat)))).coerceIn(.00001, 10.0)
    return current.copy(latitude = lat, longitude = lon, rotationRadians = 0.0, metresToPixels = ppm)
}
