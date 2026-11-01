package com.no5.helm.ui

import com.no5.helm.model.MapPositionState
import com.no5.helm.model.PositionMode

/** Host camera ownership is separate from the engine's exact FULL/source/data admission.
 * A paused Follow request still owns future camera work. Never disable it to admit a test.
 * This check does not claim that an engine camera queue is empty; the owner must recheck it.
 */
fun rendererDiagnosticCameraStartBlocker(position: MapPositionState, otherOperationActive: Boolean): String? =
    if (otherOperationActive || position.intent.follow || position.followActive ||
        position.mode == PositionMode.FOLLOW || position.touchActive)
        "Andere Kartenarbeit zuerst abschließen."
    else null
