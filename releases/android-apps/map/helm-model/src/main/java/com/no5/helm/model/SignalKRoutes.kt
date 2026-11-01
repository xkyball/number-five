package com.no5.helm.model

import kotlinx.coroutines.flow.StateFlow

data class SignalKRoutePoint(val id: String, val name: String, val latitude: Double, val longitude: Double)
data class SignalKRouteResource(val id: String, val name: String, val points: List<SignalKRoutePoint>,
    val token: String, val resourceJson: String)
data class SignalKRoutesState(val origin: String? = null, val ready: Boolean = false,
    val routes: List<SignalKRouteResource> = emptyList(), val issue: String? = null)
interface SignalKRouteActions {
    val routeResources: StateFlow<SignalKRoutesState>
    suspend fun refreshRoutes()
    suspend fun saveRoute(origin: String, id: String, name: String, points: List<SignalKRoutePoint>,
        expectedToken: String?, resourceJson: String?): SignalKRouteResource
    suspend fun deleteRoute(origin: String, id: String, expectedToken: String)
}
