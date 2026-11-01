package com.no5.helm.ui

import com.no5.preparation.ui.RouteDocument
import com.no5.preparation.ui.RouteSaveRequest
import kotlinx.coroutines.CancellationException

/** Signal K is authoritative; a confirmed remote write is never replayed to repair its cache. */
internal sealed interface RouteSaveCompletion {
    data class Saved(val document: RouteDocument) : RouteSaveCompletion
    data class CacheFailed(val document: RouteDocument, val reason: String) : RouteSaveCompletion
}

internal suspend fun persistRouteSave(
    request: RouteSaveRequest,
    remoteSave: suspend (RouteDocument) -> RouteDocument,
    cacheSave: suspend (RouteSaveRequest) -> Unit,
): RouteSaveCompletion {
    val confirmed = (if (request.remoteConfirmed) request.snapshot else remoteSave(request.snapshot)).copy(offlineCopyPending = false)
    check(confirmed.id == request.snapshot.id && confirmed.revision == request.snapshot.revision)
    try { cacheSave(request.copy(snapshot = confirmed, remoteConfirmed = true)) }
    catch (cancel: CancellationException) { throw cancel }
    catch (failure: Exception) { return RouteSaveCompletion.CacheFailed(confirmed, failure.message ?: "Speichern fehlgeschlagen") }
    return RouteSaveCompletion.Saved(confirmed)
}
