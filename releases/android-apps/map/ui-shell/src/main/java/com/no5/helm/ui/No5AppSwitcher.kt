package com.no5.helm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.preparation.ui.No5CompactAction
import com.no5.preparation.ui.No5PanelDialog
import com.no5.ui.No5Text

/** Modal window keeps the existing shell, renderer and chart state mounted. */
@Composable internal fun No5AppSwitcher(night: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var discovery by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) discovery++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val available = remember(context, discovery) {
        No5AppNavigation.Target.entries.associateWith { No5AppNavigation.available(context, it) }
    }
    No5PanelDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("app-switcher"),
        title = { Text(No5Text.ui("No5 Apps")) },
        confirmButton = { No5CompactAction("Schließen", onDismiss, Modifier.testTag("app-switcher-close")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            No5AppNavigation.Target.entries.forEach { target ->
                val title = No5Text.ui(target.title)
                No5CompactAction(if (available[target] == true) title else No5Text.ui("%s · nicht verfügbar", title), onClick = {
                    failure = No5AppNavigation.open(context, target, night)
                    if (failure == null) onDismiss() else discovery++
                }, modifier = Modifier.fillMaxWidth().testTag("app-switch-${target.name}"), fullFace = true)
            }
            failure?.let { Text(it, modifier = Modifier.testTag("app-switcher-error")) }
        } })
}
