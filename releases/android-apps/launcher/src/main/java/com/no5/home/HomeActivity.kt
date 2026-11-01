package com.no5.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioButton
import android.widget.RadioGroup
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import android.graphics.Color
import android.util.TypedValue
import com.no5.navigation.AppNavigation
import com.no5.gateway.*
import com.no5.header.*

/** Ordinary HOME candidate. No system header, preferred-HOME or firmware changes. */
class HomeActivity : Activity() {
    internal val systemKnobs by lazy { com.no5.appcontrol.ProductKnobClient(this) }
    internal val systemHeader by lazy { com.no5.appcontrol.ProductHeaderProducer(this, HeaderPage.HOME) }
    // Only unassigned holds are claimed. The six ordinary audio inputs remain with
    // Control's existing default owner; no gateway session or knob wire is added.
    private val knobFeedbackOwner=Any()
    private val ownedWindows by lazy { com.no5.ui.No5OwnedWindowFocus(this) { focused ->
        systemKnobs.onWindowFocusChanged(focused);systemHeader.onWindowFocusChanged(focused)
    } }
    internal fun reportUnassignedHold(ticket:com.no5.appcontrol.KnobActionTicket){
        if(ticket.active){
            val id=com.no5.header.artwork.No5MomentFeedback.begin(knobFeedbackOwner,"Nicht belegt")
            com.no5.header.artwork.No5MomentFeedback.complete(id,com.no5.header.artwork.No5MomentFeedback.Phase.FAILED)
        }
        ticket.complete(com.no5.control.ClientDisposition.REFUSED)
    }
    private lateinit var controller: HomeController
    private var dialog: android.app.Dialog? = null
    private var workspace: String? = null
    private var restoreLanguageDialog = false
    private lateinit var status: TextView
    private var appList: HomeWorkspaceView? = null
    private var appScroll = 0
    private lateinit var gatewayMonitor: LuminaHomeGatewayMonitor
    private var gatewayStatusLabel = "Gateway · Zugang in Lumina Control einrichten"
    private var gatewayHealthy = false
    private var gatewayButton: Button? = null
    private val style get() = HomeStyle(controller.night)
    private val regular by lazy { resources.getFont(R.font.barlow_condensed_regular) }
    private val label by lazy { resources.getFont(R.font.barlow_condensed_semibold) }
    private val hero by lazy { resources.getFont(R.font.barlow_condensed_extrabold) }
    // One decoded image per activity, reused for theme changes and returns from other apps.
    private val boatPhoto by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.home_boat,
            BitmapFactory.Options().apply { inScaled = false })
    }
    private fun t(value: String) = No5Text.text(this, value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private lateinit var uiStateClient: com.no5.ui.No5UiClient
    private var mediaHeader: com.no5.header.artwork.No5HeaderMediaView? = null
    private var headerControls: com.no5.header.artwork.No5HeaderControlsView? = null
    private var uiLanguage = "de"
    private var uiResumed = false
    private var languageChoices: RadioGroup? = null
    private var languageNotice: TextView? = null
    private var requestedLanguage: String? = null
    private val uiObserver = object : com.no5.ui.No5UiClient.Observer {
        override fun onUiStateChanged(snapshot: com.no5.ui.No5UiClient.Snapshot) {
            if (uiLanguage != snapshot.language) {
                uiLanguage = snapshot.language
                if (::controller.isInitialized) refreshLanguageUi()
            }
            syncLanguageChoices(snapshot.language)
            if (requestedLanguage == snapshot.language) {
                requestedLanguage = null
                languageNotice?.text = t("Einmal wählen · alle Apps übernehmen die Sprache")
            }
            mediaHeader?.render(snapshot, controller.night, uiResumed,
                !android.animation.ValueAnimator.areAnimatorsEnabled())
        }
        override fun onUiError(code: String) {
            // RadioButton has already toggled itself when its click listener runs.
            // A failed write must never look like an acknowledged global choice.
            syncLanguageChoices(uiStateClient.snapshot.language)
            if (requestedLanguage != null) {
                requestedLanguage = null
                languageNotice?.text = t("Nicht bestätigt · bisherige Sprache bleibt aktiv")
            }
        }
    }
    private fun syncLanguageChoices(confirmed: String) {
        languageChoices?.let { choices ->
            for (index in 0 until choices.childCount) {
                val button = choices.getChildAt(index) as RadioButton
                button.isChecked = button.tag == confirmed
            }
        }
    }
    internal fun setUiLanguage(language: String) {
        requestedLanguage = language
        languageNotice?.text = t("Sprachwechsel angefragt · Bestätigung ausstehend")
        syncLanguageChoices(uiStateClient.snapshot.language)
        uiStateClient.setLanguage(language)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = HomeController(this)
        uiStateClient = com.no5.ui.No5UiClient(this)
        uiLanguage = uiStateClient.snapshot.language
        uiStateClient.addObserver(uiObserver)
        gatewayMonitor = LuminaHomeGatewayMonitor(this, ::updateGatewayNotice)
        systemKnobs.requestLeaseAsync(mapOf(
            com.no5.control.NormalizedKnobInput.LEFT_LONG_PUSH to ::reportUnassignedHold,
            com.no5.control.NormalizedKnobInput.RIGHT_LONG_PUSH to ::reportUnassignedHold))
        if (savedInstanceState == null) controller.acceptHome(intent)
        if (savedInstanceState?.getBoolean("apps") == true) controller.showApps()
        workspace=savedInstanceState?.getString("workspace")?.takeIf{it in setOf("setup","homes")}
        restoreLanguageDialog=savedInstanceState?.getBoolean("languageDialog")==true
        appScroll = savedInstanceState?.getInt("appsScroll",0) ?: 0
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("apps", controller.appsVisible)
        outState.putString("workspace",workspace)
        outState.putBoolean("languageDialog",dialog is HomeLanguageDialog && dialog?.isShowing==true)
        outState.putInt("appsScroll",appList?.scrollOffset ?: appScroll)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.acceptHome(intent)
        workspace=null
        render()
    }
    override fun onResume() { super.onResume(); uiResumed = true; uiStateClient.start(); render(); immersive(); gatewayMonitor.start(); ownedWindows.onResume(); systemKnobs.onResume(); systemHeader.onResume() }
    override fun onPause() { ownedWindows.onPause(); com.no5.header.artwork.No5MomentFeedback.invalidate(knobFeedbackOwner); uiResumed = false; headerControls?.setForeground(false); uiStateClient.stop(); mediaHeader?.render(uiStateClient.snapshot, controller.night, false, true); systemHeader.onPause(); systemKnobs.onPause(); gatewayMonitor.stop(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); ownedWindows.windowChanged(); if (hasFocus) immersive() }
    @Suppress("DEPRECATION")
    private fun immersive() {
        // Existing public Android request only. Firmware may retain its own dock.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    @Deprecated("HOME remains visible; Back closes only the current secondary surface")
    override fun onBackPressed() {
        if (dialog?.isShowing == true) { dialog?.dismiss(); return }
        if(workspace!=null){backWorkspace();return}
        if (controller.appsVisible) { controller.back(); render() }
    }
    override fun onDestroy() { if (::uiStateClient.isInitialized) { uiStateClient.removeObserver(uiObserver); uiStateClient.close() }; mediaHeader = null; ownedWindows.close(); systemHeader.close(); systemKnobs.close(); gatewayMonitor.close(); dialog?.dismiss(); super.onDestroy() }

    private fun openGateway() {
        if (!gatewayMonitor.gateway.openSetup()) report("Lumina Control ist nicht verfügbar.")
    }
    internal fun updateGatewayNotice(label: String, connected: Boolean) {
        gatewayStatusLabel = label; gatewayHealthy = connected
        gatewayButton?.apply { text = t(label); visibility = if (connected) View.GONE else View.VISIBLE }
    }

    private fun text(value: String, size: Float, font: Typeface = regular, onShell: Boolean = false) = TextView(this).apply {
        text = t(value); textSize = size; typeface = font
        setTextColor(if (onShell) style.shellText else style.ink)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(value: String, compact: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = t(value); textSize = if (compact) 18f else 25f; typeface = label; isAllCaps = false
        setTextColor(style.ink); gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        minHeight = dp(if (compact) HomeStyle.TOUCH_DP else HomeStyle.ROW_DP)
        minimumWidth = dp(HomeStyle.TOUCH_DP)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        val shape = GradientDrawable().apply {
            setColor(style.control); cornerRadius = dp(HomeStyle.RADIUS_DP).toFloat(); setStroke(dp(1), style.structural)
        }
        com.no5.ui.No5TouchFeedback.install(this, shape, (style.accent and 0x00ffffff) or 0x33000000, style.structural)
        setOnClickListener { action() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun rule(color: Int) = View(this).apply { setBackgroundColor(color) }

    private fun render() {
        val reopenLanguage=restoreLanguageDialog || dialog is HomeLanguageDialog && dialog?.isShowing==true
        restoreLanguageDialog=false
        dialog?.setOnDismissListener(null);dialog?.dismiss(); dialog = null
        languageChoices=null;languageNotice=null
        appScroll = if (controller.appsVisible) appList?.scrollOffset ?: appScroll else 0
        appList = null
        val root = HomeStage(this,controller.night).apply {
            background=HomePhotoBackground(boatPhoto,controller.night)
        }
        fun stageText(value:String,size:Float,font:Typeface=label,onShell:Boolean=false)=text(value,size,font,onShell).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX,size);setPadding(0,0,0,0);gravity=Gravity.CENTER_VERTICAL
        }
        fun stageButton(value:String,size:Float=30f,action:()->Unit)=button(value,true,action).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX,size);minimumHeight=0;minimumWidth=0
            // Keep the shared thin footer surface and its active rule above OEM button shadows.
            stateListAnimator=null;elevation=0f;translationZ=0f
        }
        fun footerButton(value:String,glyph:Int,action:()->Unit)=stageButton(value,30f,action).apply {
            com.no5.ui.No5TouchFeedback.install(this, GradientDrawable().apply {setColor(style.surface);cornerRadius=24f},
                (style.accent and 0x00ffffff) or 0x33000000, style.structural)
            setCompoundDrawablesWithIntrinsicBounds(HomeGlyph(glyph,style.secondary),null,null,null)
            compoundDrawablePadding=14
        }
        fun surface(x:Float,y:Float,w:Int,h:Int,radius:Float=24f) {
            root.place(View(this).apply { background=GradientDrawable().apply {
                setColor(style.surface);cornerRadius=radius;setStroke(2,style.structural)
            } },x,y,w,h)
        }
        root.place(HomeHeaderView(this, controller.night) {
            workspace=null;controller.back(); render()
        },0f,0f,2000,130)
        mediaHeader = com.no5.header.artwork.No5HeaderMediaView(this).apply {
            onOpenAudio = { report(com.no5.navigation.AppNavigation.openAudio(this@HomeActivity)) }
            render(uiStateClient.snapshot, controller.night, uiResumed, !android.animation.ValueAnimator.areAnimatorsEnabled())
        }.also { root.place(it, 282f, 4f, 1050, 104) }
        headerControls = com.no5.header.artwork.No5HeaderControlsView(this).also { controls ->
            controls.render(com.no5.releaseinfo.ReleaseInfo.shortBuildLabel(this, "HOME"), controller.night, uiResumed,
                onTheme = { night -> if (controller.night != night) { controller.toggleNight(); render() } },
                onInfo = { _ -> com.no5.releaseinfo.ReleaseInfo.showDetailsWithWifi(this, controller.night) })
            root.place(controls, 0f, 0f, 2000, 130)
        }
        gatewayButton = if (!controller.appsVisible && workspace==null) stageButton(gatewayStatusLabel, 27f) { openGateway() }.apply {
            maxLines = 2; tag = "home-gateway-notice"
        } else null
        gatewayButton?.let { root.place(it,1040f,164f,610,132) }
        updateGatewayNotice(gatewayStatusLabel, gatewayHealthy)
        if(workspace!=null) {
            val rows=if(workspace=="setup")listOf(
                com.no5.header.artwork.No5WorkspaceView.Row("Sprache für alle Apps",detail="Einmal in HOME wählen",value=No5Text.languageName(uiStateClient.snapshot.language),action=::showLanguageDialog),
                com.no5.header.artwork.No5WorkspaceView.Row("Lumina Control öffnen",detail="Verbindung und Registrierung",action=::openGateway),
                com.no5.header.artwork.No5WorkspaceView.Row("Andere HOME einmal öffnen",action={workspace="homes";render()})
            ) else {
                val homes=AppNavigation.otherHomes(this)
                if(homes.isEmpty())listOf(com.no5.header.artwork.No5WorkspaceView.Row("Kein anderer HOME-Startbildschirm verfügbar.",enabled=false))
                else homes.map{home->com.no5.header.artwork.No5WorkspaceView.Row(home.label,rawLabel=true,action={report(AppNavigation.openOtherHome(this,home))})}
            }
            root.place(HomeWorkspaceView(this,controller.night,
                if(workspace=="setup")"Gerät einrichten"else"Andere HOME einmal öffnen",
                if(workspace=="setup")"App-Einstiege und Gerätediagnose bleiben getrennt."else"Android-Standard bleibt erhalten.",rows,::backWorkspace,showBack=workspace!="setup"),0f,130f,2000,928)
        } else if(controller.appsVisible) {
            val apps=controller.apps()
            val rows=if(apps.isEmpty())listOf(com.no5.header.artwork.No5WorkspaceView.Row(
                "Keine weiteren startbaren Apps installiert.",enabled=false,key="home-apps-empty"))
            else apps.map {app->com.no5.header.artwork.No5WorkspaceView.Row(app.label,rawLabel=true,
                key="home-app-${app.component.flattenToString()}",action={report(controller.open(app))})}
            val listFrame=HomeWorkspaceView(this,controller.night,"Alle Apps","Installierte Apps",rows,
                {controller.back();render()})
            appList=listFrame;listFrame.restoreScroll(appScroll)
            root.place(listFrame,0f,130f,2000,928)
        } else {
            surface(80f,300f,780,478)
            root.place(stageText("ANWENDUNGEN",34f),112f,320f,714,64)
            fun destination(title:String,pkg:String,y:Float) {
                val available=if(AppNavigation.installed(this,pkg))t("Öffnen  ›") else t("Nicht installiert")
                root.place(stageButton("$title   /   $available",42f) {
                    report(AppNavigation.openApp(this,pkg,controller.night))
                },112f,y,714,144)
            }
            destination("No5 HELM",AppNavigation.HELM,410f)
            destination("No5 MAP",AppNavigation.MAP,586f)
            root.place(stageText("STELLENBOSCH",86f,hero,true),1040f,328f,810,126)
            root.place(stageText("AN BORD",30f,label,true),1044f,460f,720,60)
        }
        status=stageText("",24f,onShell=true).apply {
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.place(status,353f,990f,1303,58)
        surface(353f,1060f,1303,132,28f)
        root.place(footerButton(if(controller.appsVisible||workspace!=null)"Zurück" else "Alle Apps",0) {
            if(workspace!=null)backWorkspace() else {
                if (controller.appsVisible) controller.back() else controller.showApps()
                render()
            }
        },363f,1064f,417,124)
        root.place(footerButton("SYSTEM · Einstellungen",1) {report(AppNavigation.openSettings(this))},802f,1064f,417,124)
        root.place(footerButton("Wartung",2) {maintenance()},1241f,1064f,405,124)
        root.place(rule(style.structural),791f,1085f,1,80)
        root.place(rule(style.structural),1230f,1085f,1,80)
        root.place(rule(style.accent),383f,1176f,160,4)
        setContentView(root)
        if(reopenLanguage&&workspace=="setup")showLanguageDialog()
        StatusBarAppWidgetProvider.refreshBoundWidgets(this)
        val apps = controller.appsVisible
        val theme = if (controller.night) HeaderTheme.RED_NIGHT else HeaderTheme.DAY
        systemHeader.update(if (apps) HeaderPage.APPS else HeaderPage.HOME, null) { publication ->
            HomeHeaderSnapshotBuilder.build(publication, theme, apps)
        }
    }
    private fun report(error: String?) { status.text = error?.let(::t).orEmpty(); status.visibility = if (error == null) View.GONE else View.VISIBLE }
    internal fun refreshLanguageUi() {
        if (::gatewayMonitor.isInitialized) gatewayMonitor.refreshUiLabel()
        render()
    }
    private fun backWorkspace(){workspace=if(workspace=="homes")"setup"else null;render()}
    private fun maintenance(){workspace="setup";render()}
    private fun showLanguageDialog(){
        val sheet=HomeLanguageDialog(this,controller.night,uiStateClient.snapshot.language,
            t(if(requestedLanguage!=null)"Sprachwechsel angefragt · Bestätigung ausstehend"else"Einmal wählen · alle Apps übernehmen die Sprache"),::setUiLanguage)
        dialog=sheet;languageChoices=sheet.choices;languageNotice=sheet.notice
        sheet.setOnDismissListener{if(dialog===sheet){dialog=null;languageChoices=null;languageNotice=null}}
        sheet.show()
    }
}
