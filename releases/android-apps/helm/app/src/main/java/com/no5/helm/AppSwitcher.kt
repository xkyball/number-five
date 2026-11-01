package com.no5.helm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.navigation.AppNavigation

/** Small modal on the existing brand target; no additional permanent navigation surface. */
@Composable
internal fun AppSwitcher(night: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var maintenance by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val homes = remember(revision) { AppNavigation.otherHomes(context) }
    val mapInstalled = remember(revision) { AppNavigation.installed(context, AppNavigation.MAP) }
    val homeInstalled = remember(revision) { AppNavigation.installed(context, AppNavigation.HOME) }
    fun open(result: String?) { if (result == null) dismiss() else error = result }
    AlertDialog(onDismissRequest = dismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface, title = { Text(if (maintenance) "NO 5 · Wartung" else "NO 5 · Anwendungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!maintenance) {
                    SwitchButton("HELM · Zurück", dismiss)
                    SwitchButton(if (mapInstalled) "Karte öffnen" else "Karte · nicht installiert") { open(AppNavigation.openApp(context, AppNavigation.MAP, night)) }
                    SwitchButton(if (homeInstalled) "No5 Home öffnen" else "No5 Home · nicht installiert") { open(AppNavigation.openApp(context, AppNavigation.HOME, night)) }
                    SwitchButton("Wartung") { maintenance = true; error = null }
                } else {
                    SwitchButton("Android-Einstellungen") { open(AppNavigation.openSettings(context)) }
                    Text("OEM-Startbildschirm auswählen. Der Android-Standard bleibt unverändert.")
                    if (homes.isEmpty()) Text("Kein anderer HOME-Startbildschirm verfügbar.")
                    homes.forEach { home -> SwitchButton("${home.label}\n${home.component.packageName}") { open(AppNavigation.openOtherHome(context, home)) } }
                    SwitchButton("Zurück zum App-Wechsler") { maintenance = false; error = null }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
            }
        }, confirmButton = { TextButton(onClick = dismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Schließen") } })
}

@Composable
private fun SwitchButton(label: String, action: () -> Unit) {
    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)) { Text(label) }
}
