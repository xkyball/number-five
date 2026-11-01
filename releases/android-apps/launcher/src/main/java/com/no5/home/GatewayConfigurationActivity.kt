package com.no5.home

import android.app.Activity
import android.content.Intent
import com.no5.helm.security.*
import com.no5.gateway.GatewayOrigin
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import com.no5.gateway.*

/** One native configuration surface for address selection; origin-bound secure material is imported once into Home. */
class GatewayConfigurationActivity : Activity() {
    private lateinit var store: GatewayConfigurationStore
    private lateinit var monitor: GatewayMonitor
    private lateinit var origin: EditText
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var caStatus: TextView
    private lateinit var caStore: GatewayCaStore
    private lateinit var panelStyle: GatewaySettingsStyle
    private var caChecking = false
    private var importOrigin: String? = null
    private var editing = false
    private var updatingFields = false
    private val passwordWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var passwordBusy = false
    internal var gatewayAccessDialog: android.app.AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GatewayConfigurationStore(this)
        caStore = GatewayCaStore(this)
        val seed = store.hint() ?: GatewayOrigin.parse(intent.getStringExtra(HomeGatewayContract.ORIGIN).orEmpty())?.takeIf { GatewayEnvironment.accepts(it) }
            ?: if (GatewayEnvironment.emulator) GatewayOrigin.parse("https://${GatewayEnvironment.LAB_HOST}:9443") else null
        seed?.let { store.offer(it) }
        val night = getSharedPreferences("no5_home", MODE_PRIVATE).getBoolean("night", false)
        panelStyle = GatewaySettingsStyle(this, night)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8))
        }
        fun divider() { body.addView(panelStyle.divider()) }
        fun row(title: String, child: android.view.View, detail: String? = null) {
            body.addView(panelStyle.row(title, child, detail))
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        lateinit var connectionTab: Button
        lateinit var advancedTab: Button
        fun showAdvanced(open: Boolean) {
            advanced.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            connectionTab.isSelected = !open; advancedTab.isSelected = open
            connectionTab.invalidate(); advancedTab.invalidate()
        }
        connectionTab = panelStyle.button("Verbindung") { showAdvanced(false) }.apply { isSelected = true }
        advancedTab = panelStyle.button("Erweitert") { showAdvanced(advanced.visibility != android.view.View.VISIBLE) }
        tabs.addView(connectionTab); tabs.addView(advancedTab)
        body.addView(tabs)
        caStatus = panelStyle.text("CA wird geprüft …", 16f).apply {
            id = CA_STATUS_ID; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        row("Zertifikat", caStatus, "Öffentliche Gateway-CA")
        divider()
        origin = panelStyle.input().apply {
            id = ORIGIN_ID; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            typeface = resources.getFont(R.font.barlow_condensed_regular)
            hint = "https://gateway-name.local:9443"; setText(seed?.url.orEmpty())
        }
        row("Gatewayname", origin, "HTTPS-Adresse")
        address = panelStyle.input().apply {
            id = ADDRESS_ID; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = resources.getFont(R.font.barlow_condensed_regular)
            hint = "IPv4 oder IPv6"; setText(store.read()?.address.orEmpty())
        }
        row("Verbindungsadresse", address, "IPv4 oder IPv6")
        val addressActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addressActions.addView(panelStyle.button("Automatisch suchen") { startCheck(false) })
        addressActions.addView(panelStyle.button("Adresse prüfen und übernehmen") { startCheck(true) })
        row("Adresse prüfen", addressActions)
        status = panelStyle.text(if (store.corrupt()) "Gespeicherte Konfiguration beschädigt. Bitte die Adresse ausdrücklich prüfen und übernehmen." else "Adresse wird geprüft …").apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        row("Verbindung", status)
        divider()
        row("Zugang", panelStyle.button("Gateway-Zugang") { credentialDialog() }, "Für HELM und MAP")
        val accessNote = panelStyle.text("Key-Datei laden oder Key bzw. Passwort eingeben. Die öffentliche CA lädt Home automatisch.")
        body.addView(accessNote)
        advanced.addView(panelStyle.divider())
        advanced.addView(panelStyle.row("Andere Gateway-CA", panelStyle.button("Andere Gateway-CA importieren") { pick(CA_REQUEST) }))
        advanced.addView(panelStyle.text("Für einen anderen Gateway kann hier eine CA manuell hinterlegt werden."))
        body.addView(advanced)
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panelStyle.panelBackground(); clipToOutline = true
            addView(android.view.View(this@GatewayConfigurationActivity).apply { setBackgroundColor(panelStyle.accent) }, LinearLayout.LayoutParams(-1, dp(2)))
            addView(LinearLayout(this@GatewayConfigurationActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0)
                addView(panelStyle.text("GATEWAY", 22f, R.font.barlow_condensed_extrabold), LinearLayout.LayoutParams(0, -2, 1f))
                addView(panelStyle.text("${GatewayEnvironment.label} · HELM + MAP", 14f))
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(panelStyle.divider())
            addView(ScrollView(this@GatewayConfigurationActivity).apply {
                id = FORM_ID; isFillViewport = true; addView(body)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val back = panelStyle.button("‹ Zurück zu Home") { finish() }
        val stage = HomeStage(this, night).apply {
            setBackgroundColor(panelStyle.surface)
            place(frame, HomeStage.SAFE_COLUMN_LEFT, 32f, HomeStage.SAFE_COLUMN_WIDTH, 984)
            place(back, HomeStage.SAFE_COLUMN_LEFT, 1060f, HomeStage.SAFE_COLUMN_WIDTH, 124)
        }
        setContentView(stage)
        updateCaStatus()
        monitor = GatewayMonitor(store, GatewayCheckerFactory.create(this)) { record, online, error ->
            status.text = error ?: when {
                !online -> "Kein Netzwerk. Die gespeicherte Konfiguration bleibt erhalten."
                record == null || record.origin != GatewayOrigin.parse(origin.text.toString()) -> "Keine passende Adresse gefunden. Bitte die Verbindungsadresse eingeben."
                !record.usable -> "Gateway-Prüfung fehlgeschlagen. Bitte Adresse und Zugang überprüfen."
                else -> "Adresse erreichbar · ${if (record.source == GatewayAddressSource.MDNS) "automatisch gefunden" else "manuell konfiguriert"}"
            }
            caChecking = false; updateCaStatus(online, error != null)
            if (!editing && record?.origin == GatewayOrigin.parse(origin.text.toString())) {
                updatingFields = true; address.setText(record?.address.orEmpty()); updatingFields = false
                monitor.start(delayed = true)
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updatingFields) { editing = true; monitor.stop(); caChecking = false; updateCaStatus() }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        origin.addTextChangedListener(watcher); address.addTextChangedListener(watcher)
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (!editing && GatewayOrigin.parse(origin.text.toString()) != null) { caChecking = true; updateCaStatus(); monitor.start() }
        else if (origin.text.isEmpty()) status.text = "Bitte zuerst den HTTPS-Gatewaynamen eingeben."
    }
    override fun onPause() { monitor.stop(); super.onPause() }
    override fun onDestroy() { gatewayAccessDialog?.dismiss(); monitor.close(); passwordWorker.shutdownNow(); super.onDestroy() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
    private fun updateCaStatus(online: Boolean = true, failed: Boolean = false) {
        val target = GatewayOrigin.parse(origin.text.toString())?.takeIf { GatewayEnvironment.accepts(it) }
        if (target == null) { caStatus.text = "CA · zuerst den zugeordneten Gatewaynamen eingeben"; return }
        val secure = com.no5.helm.security.GatewayOrigin.fromEndpoint(target.url)
        val state = caStore.status(secure)
        caStatus.text = when {
            state != GatewayCaState.UNAVAILABLE -> state.message
            GatewayKnownAuthorities.fingerprint(secure) == null -> "CA für diesen Gateway fehlt · Import unter Erweitert"
            !online -> "CA noch nicht verfügbar · wartet auf Netzwerk"
            caChecking -> "CA wird automatisch geladen und geprüft …"
            failed -> "CA konnte nicht geladen werden · Home versucht es bei der nächsten Verbindungsprüfung erneut"
            else -> "CA fehlt oder ist beschädigt · wird automatisch geladen"
        }
    }
    private fun credentialDialog() {
        if (passwordBusy) return
        val input = panelStyle.input().apply {
            id = CREDENTIAL_ID; hint = "Gateway-Key oder Passwort"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = resources.getFont(R.font.barlow_condensed_regular)
            isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val dialogBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(panelStyle.text("Key eingeben, Key-Datei laden oder dein Passwort verwenden."))
            addView(input)
        }
        val dialogTitle = panelStyle.text("GATEWAY-ZUGANG", 22f, R.font.barlow_condensed_extrabold).apply {
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val dialog = android.app.AlertDialog.Builder(this).setCustomTitle(dialogTitle).setView(dialogBody)
            .setPositiveButton("Verbinden") { _, _ ->
                val chars = CharArray(input.text.length)
                android.text.TextUtils.getChars(input.text, 0, chars.size, chars, 0)
                input.text.clear(); useCredential(chars)
            }.setNeutralButton("Key-Datei laden") { _, _ -> input.text.clear(); pick(KEY_REQUEST) }
            .setNegativeButton("Abbrechen") { _, _ -> input.text.clear() }.create()
        dialog.setOnDismissListener { input.text.clear(); gatewayAccessDialog = null }
        gatewayAccessDialog = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawable(panelStyle.panelBackground())
        listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEUTRAL, android.app.AlertDialog.BUTTON_NEGATIVE).forEach {
            panelStyle.styleButton(dialog.getButton(it))
        }
        val scale = minOf(resources.displayMetrics.widthPixels / 2000f, resources.displayMetrics.heightPixels / 1200f)
        dialog.window?.setLayout((HomeStage.SAFE_COLUMN_WIDTH * scale).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun useCredential(chars: CharArray) {
        val target = GatewayOrigin.parse(origin.text.toString())?.takeIf { GatewayEnvironment.accepts(it) }
        if (target == null) { chars.fill('\u0000'); status.text = "Bitte den zugeordneten HTTPS-Gatewaynamen eingeben."; return }
        val current = store.read()?.takeIf { it.origin == target }
        val numeric = gatewayAddress(address.text.toString())?.hostAddress ?: current?.address
        if (numeric == null) { chars.fill('\u0000'); status.text = "Bitte zuerst die Gatewayadresse suchen oder eingeben."; return }
        if (chars.isEmpty() || chars.size > 512) {
            chars.fill('\u0000'); status.text = "Bitte den Gateway-Key oder dein Passwort eingeben."; return
        }
        passwordBusy = true; monitor.stop(); status.text = "Gateway-Zugang wird geprüft …"
        caChecking = true; updateCaStatus()
        val record = GatewayConfiguration(target, numeric, GatewayAddressSource.MANUAL, checkedAt = 0)
        passwordWorker.execute {
            val success = try {
                GatewayPasswordClient(record, caStore, AndroidGatewayCredentialStore(this)).useCredential(chars)
                store.materialChanged(); true
            } catch (_: Exception) { false }
            finally { chars.fill('\u0000') }
            runOnUiThread {
                if (!isDestroyed) {
                    passwordBusy = false
                    caChecking = false; updateCaStatus(failed = !success)
                    status.text = if (success) "Gateway-Zugang gespeichert. HELM und MAP übernehmen ihn nach der Adressprüfung." else "Gateway-Zugang abgewiesen. Bitte Eingabe und Adresse prüfen."
                    if (success && GatewayOrigin.parse(origin.text.toString()) == target) startCheck(true)
                }
            }
        }
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun startCheck(manual: Boolean) {
        val target = GatewayOrigin.parse(origin.text.toString())
        if (target == null) { status.text = "Bitte eine HTTPS-Adresse mit Gatewaynamen und gültigem Port eingeben."; return }
        if (!GatewayEnvironment.accepts(target)) { status.text = "Falsche Umgebung: ${GatewayEnvironment.label}. Bitte den zugeordneten Gatewaynamen verwenden."; return }
        store.offer(target)
        if (manual && gatewayAddress(address.text.toString()) == null) { status.text = "Bitte eine gültige Verbindungsadresse eingeben."; return }
        editing = false
        caChecking = true; updateCaStatus()
        status.text = if (manual) "Adresse wird geprüft …" else "Automatische Suche läuft …"
        monitor.check(target, if (manual) address.text.toString() else null)
    }
    @Suppress("DEPRECATION")
    private fun pick(request: Int) {
        val selected = GatewayOrigin.parse(origin.text.toString())?.takeIf { GatewayEnvironment.accepts(it) }
        if (selected == null) { status.text = "Bitte den zugeordneten HTTPS-Gatewaynamen eingeben."; return }
        store.offer(selected)
        importOrigin = selected.url
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), request)
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = importOrigin; importOrigin = null
        if (resultCode != RESULT_OK || data?.data == null || target == null) return
        if (GatewayOrigin.parse(origin.text.toString())?.url != target) { status.text = "Gateway geändert. Bitte erneut auswählen."; return }
        val uri = data.data!!
        if (uri.scheme != "content") { status.text = "Bitte ein Dokument aus der Dateiauswahl verwenden."; return }
        val imported = when (requestCode) {
            KEY_REQUEST -> GatewayKeyImporter(AndroidGatewayCredentialStore(this)).import(object : GatewayKeyDocument {
                override fun open() = contentResolver.openInputStream(uri)
                override fun delete() = GatewayKeyDocumentDelete.MANUAL_DELETE_REQUIRED
            }, target, false) is GatewayKeyImportResult.Imported
            CA_REQUEST -> GatewayTrustImporter(AndroidGatewayTrustStore(this)).import(object : GatewayTrustDocument {
                override fun open() = contentResolver.openInputStream(uri)
            }, target) is GatewayTrustImportResult.Imported
            else -> false
        }
        if (imported) {
            if (requestCode == CA_REQUEST) caStore.imported(com.no5.helm.security.GatewayOrigin.fromEndpoint(target))
            caChecking = false; updateCaStatus()
            try { store.materialChanged(); status.text = "In Home gespeichert. HELM und MAP übernehmen den Zugang; HTTPS-Abnahme erfolgt beim Datenabruf." }
            catch (_: RuntimeException) { status.text = "Material gespeichert. Bitte Home erneut öffnen, damit beide Apps den Zugang übernehmen." }
        } else status.text = "Import abgelehnt. Bitte ein gültiges Dokument für diesen Gateway auswählen."
    }
    companion object { private const val KEY_REQUEST = 41; private const val CA_REQUEST = 42; const val ORIGIN_ID = 0x4e0501; const val ADDRESS_ID = 0x4e0502; const val CREDENTIAL_ID = 0x4e0503; const val CA_STATUS_ID = 0x4e0504; const val FORM_ID = 0x4e0505 }
}

/** Production uses Android services; host tests explicitly substitute a deterministic checker. */
internal object GatewayCheckerFactory {
    var testFactory: ((android.content.Context) -> GatewayAddressChecker)? = null
    fun create(context: android.content.Context): GatewayAddressChecker = testFactory?.invoke(context) ?: AndroidGatewayAddressChecker(context)
}
