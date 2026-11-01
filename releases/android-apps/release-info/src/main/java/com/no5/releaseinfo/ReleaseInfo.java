package com.no5.releaseinfo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.util.AtomicFile;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Small synchronous release records only. No credentials and no app preference copying. */
public final class ReleaseInfo {
    private ReleaseInfo() {}
    private static final Object LOCK = new Object();
    private static final int LIMIT = 131072;

    static JSONObject read(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (bytes.size() + count > LIMIT) throw new IllegalArgumentException("Release record too large");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }

    public static JSONObject metadata(Context context) throws Exception {
        JSONObject packaged = read(context.getAssets().open("no5-release/current.json"));
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (!context.getPackageName().equals(packaged.getString("packageName"))
                || installed.getLongVersionCode() != packaged.getLong("versionCode")
                || !installed.versionName.equals(packaged.getString("versionName"))) throw new IllegalStateException("Packaged release identity mismatch");
        return InstallationLedger.sanitize(packaged);
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "no5-installation-release-v1.json"));
    }

    public static JSONObject observe(Context context) throws Exception {
        synchronized (LOCK) {
            JSONObject saved = null;
            try { saved = read(file(context).openRead()); } catch (Exception ignored) { /* absent/corrupt means unknown */ }
            PackageInfo pkg = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            JSONObject next = InstallationLedger.observe(saved, metadata(context), pkg.firstInstallTime, pkg.lastUpdateTime, Process.myUid() / 100000);
            if (next != saved) write(context, next);
            return next;
        }
    }

    private static void write(Context context, JSONObject state) throws Exception {
        AtomicFile target = file(context); FileOutputStream stream = null;
        try {
            stream = target.startWrite(); stream.write(state.toString().getBytes(StandardCharsets.UTF_8)); target.finishWrite(stream);
        } catch (Exception failure) { if (stream != null) target.failWrite(stream); throw failure; }
    }

    static JSONObject snapshot(Context context) throws Exception {
        JSONObject state = observe(context);
        state.put("apkSha256", apkHash(context));
        return state;
    }

    static void accept(Context context, String encoded) throws Exception {
        if (encoded == null || encoded.length() > LIMIT) throw new IllegalArgumentException("Invalid release receipt");
        synchronized (LOCK) { write(context, InstallationLedger.accept(observe(context), new JSONObject(encoded), apkHash(context))); }
    }

    static String apkHash(Context context) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(context.getApplicationInfo().sourceDir)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    public static String versionLine(Context context) {
        try { return describe(context, metadata(context), false); }
        catch (Exception failure) {
            try { PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                return ReleaseUiText.text(context,"Version")+" " + p.versionName + " · Build " + p.getLongVersionCode() + " · "+ReleaseUiText.text(context,"Release-Metadaten fehlen");
            } catch (Exception ignored) { return ReleaseUiText.text(context,"Version unbekannt"); }
        }
    }

    /** Stable on-screen identity; errors and connection state occupy their own labels. */
    public static String compactVersionLine(Context context) {
        try {
            JSONObject value = metadata(context);
            return value.getString("versionName") + " · Build " + value.getLong("versionCode");
        } catch (Exception failure) {
            try {
                PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                return installed.versionName + " · Build " + installed.getLongVersionCode();
            } catch (Exception unknown) { return ReleaseUiText.text(context,"Version unbekannt"); }
        }
    }

    /** Header identity comes from PackageManager, including when bundled evidence is unavailable. */
    public static String shortBuildLabel(Context context, String appName) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return appName + " · B" + installed.getLongVersionCode();
        } catch (Exception unknown) { return appName + " · —"; }
    }

    /** Explicit in-app layer: stable title/actions, scrolling evidence body, no navigation detour. */
    public static android.app.AlertDialog showDetails(android.app.Activity activity, String extraDetails) {
        return showDetails(activity, extraDetails, false);
    }
    public static android.app.AlertDialog showDetails(android.app.Activity activity, String extraDetails, boolean redNight) {
        return showDetailsInternal(activity, extraDetails, redNight, false);
    }

    /** Typed local-radio evidence; its subscription exists only while this layer is attached. */
    public static android.app.AlertDialog showDetailsWithWifi(android.app.Activity activity, boolean redNight) {
        return showDetailsInternal(activity, null, redNight, true);
    }

    private static android.app.AlertDialog showDetailsInternal(android.app.Activity activity, String extraDetails, boolean redNight, boolean withWifi) {
        android.widget.TextView body = new android.widget.TextView(activity);
        body.setTag("release-info-body");
        InfoScrollView scroll=new InfoScrollView(activity);scroll.setTag("release-info-scroll");scroll.setFillViewport(false);scroll.addView(body);
        android.widget.TextView title=new android.widget.TextView(activity);title.setTag("release-info-title");
        title.setText(ReleaseUiText.text(activity,withWifi?"Version und WLAN":"Version · Was ist neu"));
        final ReleaseDetailsDialog dialog=new ReleaseDetailsDialog(activity,redNight,title,body,scroll);
        final InfoLayer layer = new InfoLayer(activity, dialog, title, body, scroll, extraDetails, withWifi);
        scroll.layer=layer;
        scroll.addOnAttachStateChangeListener(layer);
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface ignored) {
            dialog.configureWindow();
            layer.refreshLanguage();
            }
        });
        if ((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
                && dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show(); return dialog;
    }

    private static final class InfoScrollView extends android.widget.ScrollView {
        InfoLayer layer;
        InfoScrollView(Context context) { super(context); }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (layer!=null) layer.windowVisibilityChanged(visibility);
        }
    }

    /** The native view lifetime is authoritative; caller-owned dismiss listeners cannot suppress cleanup. */
    private static final class InfoLayer implements android.view.View.OnAttachStateChangeListener,
            android.content.SharedPreferences.OnSharedPreferenceChangeListener {
        private final android.app.Activity activity;
        private final android.app.AlertDialog dialog;
        private final android.widget.TextView title, body;
        private final android.widget.ScrollView scroll;
        private final String opaqueExtra;
        private final boolean withWifi;
        private final android.content.SharedPreferences preferences;
        private com.no5.ui.WifiObserver.Subscription wifiSubscription;
        private com.no5.ui.WifiSignal wifiSignal = com.no5.ui.WifiSignal.unknown();
        private String language, core;
        private boolean attached, observing;
        private int windowVisibility=android.view.View.GONE;
        private int epoch, restoreX, restoreY, restoreEpoch;
        private final Runnable restoreScroll = new Runnable() {
            @Override public void run() {
                if (attached && observing && restoreEpoch == epoch) scroll.scrollTo(restoreX, restoreY);
            }
        };
        InfoLayer(android.app.Activity activity, android.app.AlertDialog dialog, android.widget.TextView title,
                android.widget.TextView body, android.widget.ScrollView scroll, String opaqueExtra, boolean withWifi) {
            this.activity=activity; this.dialog=dialog; this.title=title; this.body=body; this.scroll=scroll;
            this.opaqueExtra=opaqueExtra; this.withWifi=withWifi;
            preferences=activity.getApplicationContext().getSharedPreferences(com.no5.ui.UiLanguage.PREFERENCES, Context.MODE_PRIVATE);
            language=com.no5.ui.UiLanguage.current(activity);
            core=details(activity, true);
            render();
        }
        @Override public void onViewAttachedToWindow(android.view.View view) {
            if (attached) return;
            attached=true; windowVisibility=scroll.getWindowVisibility(); updateVisibility();
        }
        void windowVisibilityChanged(int visibility) {
            windowVisibility=visibility; updateVisibility();
        }
        private void updateVisibility() {
            boolean visible=attached && windowVisibility==android.view.View.VISIBLE;
            if (visible && !observing) startObserving();
            else if (!visible && observing) stopObserving();
        }
        private void startObserving() {
            observing=true; final int activeEpoch=++epoch;
            preferences.registerOnSharedPreferenceChangeListener(this);
            refreshLanguage();
            if (withWifi) wifiSubscription=com.no5.ui.WifiObserver.subscribe(activity, new com.no5.ui.WifiObserver.Listener() {
                @Override public void onWifiSignal(com.no5.ui.WifiSignal value) {
                    if (!observing || activeEpoch!=epoch) return;
                    if (wifiSignal.equals(value)) return;
                    wifiSignal=value; render();
                }
            });
        }
        @Override public void onViewDetachedFromWindow(android.view.View view) {
            if (!attached) return;
            attached=false; stopObserving();
        }
        private void stopObserving() {
            if (!observing) return;
            observing=false; epoch++;
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            scroll.removeCallbacks(restoreScroll);
            if (wifiSubscription!=null) { wifiSubscription.close(); wifiSubscription=null; }
            wifiSignal=com.no5.ui.WifiSignal.unknown();
        }
        @Override public void onSharedPreferenceChanged(android.content.SharedPreferences source, String key) {
            if (observing && com.no5.ui.UiLanguage.LANGUAGE_KEY.equals(key)) refreshLanguage();
        }
        void refreshLanguage() {
            String current=com.no5.ui.UiLanguage.current(activity);
            if (!current.equals(language)) { language=current; core=details(activity, true); }
            render();
        }
        private void render() {
            // History/installation reads happen once and on language changes, never on each radio update.
            String extra=withWifi?com.no5.ui.WifiUiText.details(activity, wifiSignal):opaqueExtra;
            String next=core+(extra==null||extra.isEmpty()?"":"\n\n"+extra);
            title.setText(ReleaseUiText.text(activity, withWifi?"Version und WLAN":"Version · Was ist neu"));
            android.widget.Button close=dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            if (close!=null) close.setText(ReleaseUiText.text(activity, "Schließen"));
            if (!next.contentEquals(body.getText())) {
                restoreX=scroll.getScrollX(); restoreY=scroll.getScrollY(); restoreEpoch=epoch;
                body.setText(next);
                if (attached && observing) { scroll.removeCallbacks(restoreScroll); scroll.post(restoreScroll); }
            }
        }
    }

    private static String describe(Context context, JSONObject value, boolean full) {
        String commit = value.optString("sourceCommit", "");
        String build = value.optString("buildId", "");
        return ReleaseUiText.text(context,"Version")+" " + value.optString("versionName", "unbekannt") + " · Build " + value.optLong("versionCode")
                + (commit.isEmpty() ? " · "+ReleaseUiText.text(context,"Quelle unbekannt") : " · " + (full ? commit : commit.substring(0, Math.min(8, commit.length()))))
                + (full ? "\nRelease " + value.optString("releaseId", "unbekannt") + "\nBuild-ID " + build : "")
                + (value.optBoolean("sourceDirty") ? " · "+ReleaseUiText.text(context,"Arbeitsstand") : "");
    }

    public static String details(Context context) { return details(context, false); }

    public static String details(Context context, boolean allChanges) {
        try {
            JSONObject state = observe(context), previous = state.optJSONObject("previous");
            JSONObject catalogue = read(context.getAssets().open("no5-release/history.json"));
            JSONObject delta = InstallationLedger.changes(state, catalogue.getJSONArray("releases"));
            StringBuilder text = new StringBuilder(ReleaseUiText.text(context,"Aktuell installiert")+"\n" + describe(context, state.getJSONObject("current"), true));
            text.append("\n\n"+ReleaseUiText.text(context,"Vorher auf diesem Gerät installiert")+"\n").append(previous == null ? ReleaseUiText.text(context,"Unbekannt / nicht belegt") : describe(context, previous, true));
            text.append("\n\n"+ReleaseUiText.text(context,"Was ist neu")+"\n");
            switch (delta.getString("kind")) {
                case "first-install": text.append(ReleaseUiText.text(context,"Erstinstallation in dieser Installationsepoche. Kein Vorgänger.")); break;
                case "downgrade": text.append(ReleaseUiText.text(context,"Ältere Version installiert. Kein Upgrade-Changelog.")); break;
                case "same-build": text.append(ReleaseUiText.text(context,"Derselbe Build wurde erneut installiert.")); break;
                case "different-build": text.append(ReleaseUiText.text(context,"Anderer Build derselben Release-Version. Unterschiede sind nicht als Release-Änderungen dokumentiert.")); break;
                case "update":
                    JSONArray notes = delta.getJSONArray("notes");
                    for (int i = 0; i < (allChanges ? notes.length() : Math.min(5, notes.length())); i++) text.append("• ").append(ReleaseUiText.text(context,notes.getString(i))).append('\n');
                    if (!allChanges && notes.length() > 5) text.append("+ ").append(notes.length() - 5).append(" ").append(ReleaseUiText.text(context,"weitere belegte Änderungen · Alle Änderungen anzeigen")).append("\n");
                    if (!delta.getBoolean("complete")) text.append(ReleaseUiText.text(context,"Die ältere Release-Historie ist teilweise unbelegt; angezeigt werden ausschließlich dokumentierte Änderungen."));
                    break;
                default:
                    text.append(ReleaseUiText.text(context,"Ein Vergleich zur vorherigen Installation ist nicht belegt.")).append(" ");
                    text.append(ReleaseUiText.text(context,"Die Installationsversion wird nicht aus dem letzten App-Start abgeleitet."));
                    JSONArray releases = catalogue.getJSONArray("releases");
                    text.append("\n\n").append(ReleaseUiText.text(context,"Dokumentierte Änderungen dieser Release-Version")).append("\n");
                    JSONObject current = state.getJSONObject("current");
                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject release = releases.getJSONObject(i);
                        if (release.getString("id").equals(current.optString("releaseId"))) {
                            JSONArray changes = release.getJSONArray("changes");
                            for (int j = 0; j < changes.length(); j++) text.append("• ").append(ReleaseUiText.text(context,changes.getJSONObject(j).getString("text"))).append('\n');
                        }
                    }
            }
            if (java.util.Arrays.asList("first-install", "same-build", "different-build", "downgrade").contains(delta.getString("kind"))) {
                text.append("\n\n").append(ReleaseUiText.text(context,"Dokumentierte Änderungen dieser Release-Version")).append("\n");
                JSONArray releases = catalogue.getJSONArray("releases");
                JSONObject current = state.getJSONObject("current");
                boolean found = false;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    if (release.getString("id").equals(current.optString("releaseId"))) {
                        JSONArray changes = release.getJSONArray("changes");
                        for (int j = 0; j < changes.length(); j++) {
                            text.append("• ").append(ReleaseUiText.text(context,changes.getJSONObject(j).getString("text"))).append('\n');
                            found = true;
                        }
                    }
                }
                if (!found) text.append(ReleaseUiText.text(context,"Unbekannt / nicht belegt"));
            }
            return text.toString();
        } catch (Exception ignored) { return versionLine(context) + "\n"+ReleaseUiText.text(context,"Installationsbaseline oder Release-Metadaten nicht lesbar; kein belegter Versionsvergleich."); }
    }
}
