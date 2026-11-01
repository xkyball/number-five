package com.no5.releaseinfo;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29,35})
public class ReleaseInfoAndroidTest {
    @Test public void packagedAssetsMatchTheInstalledPackageAndProduceAVersionLine() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JSONObject identity = ReleaseInfo.metadata(context);
        assertEquals(context.getPackageName(), identity.getString("packageName"));
        assertEquals(64, identity.getString("buildId").length());
        assertFalse(ReleaseInfo.versionLine(context).contains("fehlen"));
    }
    @Test public void unknownBaselineIsExplicitAndDoesNotOpenAnyActivity() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ReleaseInfo.metadata(context);
        String details = ReleaseInfo.details(context);
        assertTrue(details.contains("Vorher auf diesem Gerät installiert"));
        assertTrue(details.contains("Unbekannt / nicht belegt"));
        assertNull(org.robolectric.Shadows.shadowOf((android.app.Application)context).getNextStartedActivity());
    }
}
