package com.no5.helm.ocean

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.*

/** World east/up/north. Defaults are explicitly decorative, never observations. */
data class OceanEnvironment(val sunEast: Float=-.22f, val sunUp: Float=.52f,
    val sunNorth: Float=.8253484f, val cloudCover: Float=.18f,
    val fogDensity: Float=.000045f, val windToRadians: Float=.9272952f)

/** NOAA fractional-year approximation; UTC, east-positive longitude, no simulator clock input.
 * Geometric solar centre: atmospheric refraction is deliberately not represented as measured data.
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF
 */
object OceanSolar {
    fun direction(epochMs: Long, latitude: Double, longitude: Double): OceanEnvironment? {
        if(epochMs<0 || !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return null
        val date=runCatching { Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC) }.getOrNull() ?: return null
        if(date.year !in 1900..2200) return null
        val hour=date.hour+date.minute/60.0+date.second/3600.0
        val g=2*PI/(if(date.toLocalDate().isLeapYear)366 else 365)*(date.dayOfYear-1+(hour-12)/24)
        val equation=229.18*(.000075+.001868*cos(g)-.032077*sin(g)-.014615*cos(2*g)-.040849*sin(2*g))
        val declination=.006918-.399912*cos(g)+.070257*sin(g)-.006758*cos(2*g)+.000907*sin(2*g)-.002697*cos(3*g)+.00148*sin(3*g)
        val h=Math.toRadians((hour*60+equation+4*longitude)/4-180)
        val lat=Math.toRadians(latitude)
        // Direct vector form remains stable at zenith and the poles (no azimuth division).
        return OceanEnvironment((-cos(declination)*sin(h)).toFloat(),
            (sin(lat)*sin(declination)+cos(lat)*cos(declination)*cos(h)).toFloat(),
            (cos(lat)*sin(declination)-sin(lat)*cos(declination)*cos(h)).toFloat())
    }
}

internal class OceanEnvironmentTransition {
    private var target=OceanEnvironment()
    private var current=OceanEnvironment()
    private var initialized=false
    fun accept(input: OceanInputs) {
        val defaults=OceanEnvironment()
        val sun=if(input.latitudeDegrees!=null && input.longitudeDegrees!=null && input.utcEpochMs!=null)
            OceanSolar.direction(input.utcEpochMs,input.latitudeDegrees,input.longitudeDegrees) else null
        val live=input.weatherQuality==OceanInputQuality.LIVE
        val direction=input.windDirectionFromDegrees?.takeIf { live && input.windCapability && it.isFinite() && it in 0.0..360.0 }
        val clouds=input.cloudCoverPercent?.takeIf { live && it.isFinite() && it in 0.0..100.0 }
        val visibility=input.visibilityMeters?.takeIf { live && it.isFinite() && it in 0.0..1_000_000.0 }
        target=(sun ?: defaults).copy(cloudCover=clouds?.div(100)?.toFloat() ?: defaults.cloudCover,
            // Authored extinction approximation, not a claim about measured fog or wave height.
            fogDensity=visibility?.let { (4.0/it.coerceIn(50.0,100_000.0)).toFloat() } ?: defaults.fogDensity,
            windToRadians=direction?.let { Math.toRadians((it+180)%360).toFloat() } ?: defaults.windToRadians)
        if(!initialized){current=target;initialized=true}
    }
    fun frame(dt: Float): OceanEnvironment {
        if(dt<=0f)return current
        val a=(1-exp(-dt/4f));fun mix(x: Float,y: Float)=x+(y-x)*a
        // Spherical angles also converge for exactly opposite sun vectors; poles stay finite.
        val from=atan2(current.sunEast,current.sunNorth);val to=atan2(target.sunEast,target.sunNorth)
        val azimuth=from+atan2(sin(to-from),cos(to-from))*a
        val elevation=mix(asin(current.sunUp.coerceIn(-1f,1f)),asin(target.sunUp.coerceIn(-1f,1f)))
        val x=sin(azimuth)*cos(elevation);val y=sin(elevation);val z=cos(azimuth)*cos(elevation)
        val delta=atan2(sin(target.windToRadians-current.windToRadians),cos(target.windToRadians-current.windToRadians))
        current=OceanEnvironment(x,y,z,mix(current.cloudCover,target.cloudCover),
            mix(current.fogDensity,target.fogDensity),current.windToRadians+delta*a)
        return current
    }
}
