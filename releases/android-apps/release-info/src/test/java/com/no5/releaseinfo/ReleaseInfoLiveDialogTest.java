package com.no5.releaseinfo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.no5.ui.No5Text;
import com.no5.ui.UiLanguage;
import com.no5.ui.WifiObserver;
import com.no5.ui.WifiUiText;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowWifiInfo;
import static org.junit.Assert.*;

/** Actual constructed native content attached to a host Activity. Real separate-window focus/IME requires instrumentation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29,manifest=Config.NONE,qualifiers="w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ReleaseInfoLiveDialogTest {
    public static final class CountingActivity extends Activity {
        int assetReads;
        @Override public AssetManager getAssets(){assetReads++;return super.getAssets();}
    }
    @Before public void freshApplicationObservation()throws Exception{
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion=35;
        // Each Robolectric method gets a new Application, while a production process has
        // only one. Clear only the test process cache so it cannot observe a previous
        // method's preferences instead of this actual Application's preferences.
        java.lang.reflect.Field preferences=UiLanguage.class.getDeclaredField("preferences");preferences.setAccessible(true);
        java.lang.reflect.Field cached=UiLanguage.class.getDeclaredField("cachedLanguage");cached.setAccessible(true);
        android.content.SharedPreferences previous=(android.content.SharedPreferences)preferences.get(null);
        android.content.SharedPreferences current=RuntimeEnvironment.getApplication().getSharedPreferences(UiLanguage.PREFERENCES,Context.MODE_PRIVATE);
        System.out.println("Previous fixture Application preferences retained: "+(previous!=null&&previous!=current));
        if(previous!=null){java.lang.reflect.Field listener=UiLanguage.class.getDeclaredField("CHANGES");listener.setAccessible(true);previous.unregisterOnSharedPreferenceChangeListener((android.content.SharedPreferences.OnSharedPreferenceChangeListener)listener.get(null));}
        preferences.set(null,null);cached.set(null,null);
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");
        java.lang.reflect.Field instance=WifiObserver.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);
    }
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(View view){view.measure(View.MeasureSpec.makeMeasureSpec(1303,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(984,View.MeasureSpec.EXACTLY));view.layout(0,0,1303,984);idle();}
    private static ViewGroup attachContent(ActivityController<CountingActivity> host,AlertDialog dialog){
        // Robolectric native Dialogs have no ViewRoot. Keep their actual title/body/actions
        // but attach that constructed content explicitly; do not call this a real Dialog-window proof.
        ViewGroup content=(ViewGroup)dialog.findViewById(android.R.id.content);
        ((ViewGroup)content.getParent()).removeView(content);host.get().setContentView(content);host.visible().windowFocusChanged(true);layout(content);return content;
    }
    @Test public void alreadyOpenGenericInfoChangesLanguageWithoutTranslatingOpaqueDataOrReplacingTheLayer(){
        try(ActivityController<CountingActivity> host=Robolectric.buildActivity(CountingActivity.class).setup().visible().windowFocusChanged(true)){
            CountingActivity activity=host.get();activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            StringBuilder raw=new StringBuilder("OPAQUE Schließen / WARTUNG / HTTP 418 ");for(int i=0;i<10000;i++)raw.append('x');final String opaque=raw.toString();
            AlertDialog dialog=ReleaseInfo.showDetails(activity,opaque,false);assertTrue(dialog.isShowing());
            assertEquals(WindowManager.LayoutParams.FLAG_SECURE,dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE);
            ViewGroup content=attachContent(host,dialog);ScrollView scroll=content.findViewWithTag("release-info-scroll");TextView body=content.findViewWithTag("release-info-body");TextView title=content.findViewWithTag("release-info-title");Button close=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            scroll.scrollTo(0,400);assertEquals(400,scroll.getScrollY());
            for(String language:new String[]{"en","af","de"}){
                UiLanguage.acceptConfirmed(activity,language);idle();layout(content);idle();
                assertTrue(dialog.isShowing());assertSame(dialog,org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
                assertEquals(No5Text.text(activity,"Version · Was ist neu"),title.getText().toString());assertEquals(No5Text.text(activity,"Schließen"),close.getText().toString());
                assertTrue(body.getText().toString().endsWith("\n\n"+opaque));assertEquals(400,scroll.getScrollY());
            }
            activity.setContentView(new FrameLayout(activity));idle();String detached=body.getText().toString();
            UiLanguage.acceptConfirmed(activity,"en");idle();assertEquals("Detached layers must not retain a language listener",detached,body.getText().toString());
            dialog.dismiss();idle();
        }
    }
    @SuppressWarnings("deprecation")
    @Test public void typedWifiUpdatesKeepOneVisibleSubscriptionAndDoNotReadHistoryAtRadioFrequency(){
        try(ActivityController<CountingActivity> host=Robolectric.buildActivity(CountingActivity.class).setup().visible().windowFocusChanged(true)){
            CountingActivity activity=host.get();ConnectivityManager connectivity=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);WifiManager wifi=(WifiManager)activity.getSystemService(Context.WIFI_SERVICE);
            Shadows.shadowOf(connectivity).clearAllNetworks();Shadows.shadowOf(wifi).setAccessWifiStatePermission(true);Shadows.shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_ENABLED);
            Network network=ShadowNetwork.newInstance(501);Shadows.shadowOf(connectivity).addNetwork(network,ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,NetworkInfo.State.CONNECTED));
            NetworkCapabilities caps=ShadowNetworkCapabilities.newInstance();Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);Shadows.shadowOf(connectivity).setNetworkCapabilities(network,caps);
            // API29 obtains primary-WLAN RSSI from WifiManager, not a later-API TransportInfo cast.
            WifiInfo info=ShadowWifiInfo.newInstance();Shadows.shadowOf(info).setRssi(-63);Shadows.shadowOf(wifi).setConnectionInfo(info);
            AlertDialog dialog=ReleaseInfo.showDetailsWithWifi(activity,false);assertTrue(Shadows.shadowOf(connectivity).getNetworkCallbacks().isEmpty());
            ViewGroup content=attachContent(host,dialog);TextView body=content.findViewWithTag("release-info-body");TextView title=content.findViewWithTag("release-info-title");
            assertEquals(1,Shadows.shadowOf(connectivity).getNetworkCallbacks().size());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertTrue(body.getText().toString().contains("-63 dBm"));
            int beforeRadio=activity.assetReads;ConnectivityManager.NetworkCallback callback=Shadows.shadowOf(connectivity).getNetworkCallbacks().iterator().next();Shadows.shadowOf(info).setRssi(-79);callback.onCapabilitiesChanged(network,caps);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue(body.getText().toString().contains("-79 dBm"));assertEquals("Radio updates must use cached history/core",beforeRadio,activity.assetReads);
            UiLanguage.acceptConfirmed(activity,"en");idle();assertEquals(No5Text.text(activity,"Version und WLAN"),title.getText().toString());assertTrue(body.getText().toString().endsWith(WifiUiText.details(activity,com.no5.ui.WifiSignal.fromObservation(true,true,-79))));assertTrue(activity.assetReads>beforeRadio);
            ScrollView radioScroll=content.findViewWithTag("release-info-scroll");
            // Deliver the same native visibility callback used by WindowRoot. This host case
            // checks callback policy; a physical background/return remains instrumentation scope.
            try {
                java.lang.reflect.Method visibility=radioScroll.getClass().getDeclaredMethod("onWindowVisibilityChanged",int.class);visibility.setAccessible(true);
                visibility.invoke(radioScroll,View.GONE);idle();assertTrue(Shadows.shadowOf(connectivity).getNetworkCallbacks().isEmpty());
                visibility.invoke(radioScroll,View.VISIBLE);idle();assertEquals(1,Shadows.shadowOf(connectivity).getNetworkCallbacks().size());
            } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
            final int[] callerDismiss={0};dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener(){public void onDismiss(android.content.DialogInterface ignored){callerDismiss[0]++;}});
            // Cleanup follows actual native view detachment, independent of any caller dismiss listener.
            activity.setContentView(new FrameLayout(activity));idle();assertTrue(Shadows.shadowOf(connectivity).getNetworkCallbacks().isEmpty());String detached=body.getText().toString();
            UiLanguage.acceptConfirmed(activity,"af");callback.onLost(network);idle();assertEquals(detached,body.getText().toString());
            activity.setContentView(content);host.visible().windowFocusChanged(true);layout(content);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,Shadows.shadowOf(connectivity).getNetworkCallbacks().size());assertEquals(No5Text.text(activity,"Version und WLAN"),title.getText().toString());
            activity.setContentView(new FrameLayout(activity));idle();dialog.dismiss();idle();assertEquals(1,callerDismiss[0]);assertTrue(Shadows.shadowOf(connectivity).getNetworkCallbacks().isEmpty());
        }
    }
}
