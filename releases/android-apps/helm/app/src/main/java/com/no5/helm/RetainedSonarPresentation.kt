package com.no5.helm

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy

/** Acquisition continues unchanged; only actual persisted preferences invalidate the host. */
internal fun retainedSonarPresentation(controller:SonarNativeController)=
    derivedStateOf(structuralEqualityPolicy()){controller.exportPresentationState()}
