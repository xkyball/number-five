package com.no5.releaseinfo;

import android.os.Binder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, manifest = Config.NONE)
public class InstallationLedgerTest {
    private JSONObject build(int release) throws Exception { return build(release, "build" + release); }
    private JSONObject build(int release, String id) throws Exception {
        return new JSONObject().put("packageName", "com.no5.test").put("versionName", "0." + release)
                .put("versionCode", release).put("releaseId", "com.no5.test:r" + release).put("sequence", release)
                .put("buildId", id).put("sourceCommit", "abc123");
    }
    private JSONObject observe(JSONObject old, int release, long updated) throws Exception {
        return InstallationLedger.observe(old, build(release), 10, updated, 0);
    }
    private JSONObject receipt(JSONObject state, JSONObject previous) throws Exception {
        return new JSONObject(state.toString()).put("schema", 1).put("operation", previous == null ? "fresh" : "replace")
                .put("previous", previous == null ? JSONObject.NULL : previous).put("previousFirstInstallTime", 10)
                .put("apkSha256", "hash").put("receiptId", "receipt-1");
    }
    private JSONArray catalogue() throws Exception {
        JSONArray releases = new JSONArray();
        for (int i = 1; i <= 4; i++) {
            JSONObject r = build(i); r.put("id", r.getString("releaseId"));
            r.put("changes", new JSONArray().put(new JSONObject().put("id", "shared").put("text", "Common correction"))
                    .put(new JSONObject().put("id", "feature" + i).put("text", "Feature " + i)));
            releases.put(r);
        }
        return releases;
    }
    @Test public void firstLaunchDoesNotInventAnInstallationBaseline() throws Exception {
        JSONObject state = observe(null, 1, 10);
        assertEquals("unknown", state.getString("baseline")); assertFalse(state.has("previous"));
    }
    @Test public void lastObservedIsNeverCalledPreviousInstalled() throws Exception {
        JSONObject state = observe(observe(null, 1, 10), 4, 40);
        assertEquals(1, state.getJSONObject("lastObserved").getInt("versionCode"));
        assertEquals("unknown", state.getString("baseline")); assertFalse(state.has("previous"));
    }
    @Test public void deliveredUpdateBroadcastsStillDoNotProveContinuity() throws Exception {
        JSONObject state = observe(observe(observe(null, 1, 10), 2, 20), 4, 40);
        assertEquals("unknown", state.getString("baseline"));
    }
    @Test public void skippedReleasesAreCumulativeAndDeduplicated() throws Exception {
        JSONObject state = observe(null, 4, 40);
        JSONObject proven = InstallationLedger.accept(state, receipt(state, build(1)), "hash");
        JSONArray notes = InstallationLedger.changes(proven, catalogue()).getJSONArray("notes");
        assertEquals(4, notes.length()); assertEquals("Common correction", notes.getString(0));
        assertEquals("Feature 2", notes.getString(1)); assertEquals("Feature 4", notes.getString(3));
    }
    @Test public void multipleInstallsWithoutUiStartUseTheActualImmediatePredecessor() throws Exception {
        JSONObject second = observe(null, 2, 20);
        second = InstallationLedger.accept(second, receipt(second, build(1)), "hash");
        JSONObject fourth = observe(second, 4, 40);
        fourth = InstallationLedger.accept(fourth, receipt(fourth, build(2)), "hash");
        assertEquals(2, fourth.getJSONObject("previous").getInt("versionCode"));
        assertEquals(3, InstallationLedger.changes(fourth, catalogue()).getJSONArray("notes").length());
    }
    @Test public void appRestartsDoNotConsumeOrMoveTheBaseline() throws Exception {
        JSONObject state = observe(null, 4, 40);
        state = InstallationLedger.accept(state, receipt(state, build(1)), "hash");
        assertSame(state, observe(state, 4, 40));
    }
    @Test public void downgradeIsNotAnUpgradeChangelog() throws Exception {
        JSONObject state = observe(null, 2, 20);
        state = InstallationLedger.accept(state, receipt(state, build(4)), "hash");
        assertEquals("downgrade", InstallationLedger.changes(state, catalogue()).getString("kind"));
    }
    @Test public void differentBranchBuildsWithTheSameVersionAreDistinguished() throws Exception {
        JSONObject state = observe(null, 2, 20);
        state = InstallationLedger.accept(state, receipt(state, build(2, "anotherBranch")), "hash");
        assertEquals("different-build", InstallationLedger.changes(state, catalogue()).getString("kind"));
    }
    @Test public void reinstallSameBuildIsExplicit() throws Exception {
        JSONObject state = observe(null, 2, 20);
        state = InstallationLedger.accept(state, receipt(state, build(2)), "hash");
        assertEquals("same-build", InstallationLedger.changes(state, catalogue()).getString("kind"));
    }
    @Test public void sameBuildNewInstallationRotatesChallengeAndForgetsOldReceipt() throws Exception {
        JSONObject old = observe(null, 2, 20); old = InstallationLedger.accept(old, receipt(old, build(1)), "hash");
        JSONObject next = observe(old, 2, 30);
        assertNotEquals(old.getString("challenge"), next.getString("challenge")); assertFalse(next.has("receiptId"));
    }
    @Test public void freshInstallIsSeparateFromUnknownAndHasNoPrevious() throws Exception {
        JSONObject state = observe(null, 1, 10); state = InstallationLedger.accept(state, receipt(state, null), "hash");
        assertEquals("first-install", InstallationLedger.changes(state, catalogue()).getString("kind"));
        assertNull(state.optJSONObject("previous"));
    }
    @Test public void resetAndAnotherDeviceCannotReplayAnOldReceipt() throws Exception {
        JSONObject old = observe(null, 2, 20), reset = observe(null, 2, 20);
        rejected(reset, receipt(old, build(1)), "hash");
    }
    @Test public void anotherAndroidUserCannotAcceptTheReceipt() throws Exception {
        JSONObject state = observe(null, 2, 20); JSONObject r = receipt(state, build(1)); r.put("userId", 1);
        rejected(state, r, "hash");
    }
    @Test public void newInstallEpochCannotImportAnOldBaseline() throws Exception {
        JSONObject state = observe(null, 2, 20); JSONObject r = receipt(state, build(1)); r.put("previousFirstInstallTime", 9);
        rejected(state, r, "hash");
    }
    @Test public void artifactHashMismatchRejectsEvidence() throws Exception {
        JSONObject state = observe(null, 2, 20); rejected(state, receipt(state, build(1)), "otherHash");
    }
    @Test public void legacyVersionNameAloneDoesNotMapToRelease() throws Exception {
        JSONObject state = observe(null, 2, 20); JSONObject old = build(1); old.put("sequence", 0).put("releaseId", "");
        state = InstallationLedger.accept(state, receipt(state, old), "hash");
        assertEquals("unmapped-release", InstallationLedger.changes(state, catalogue()).getString("kind"));
    }
    @Test public void historyGapsRemainVisible() throws Exception {
        JSONObject state = observe(null, 4, 40); state = InstallationLedger.accept(state, receipt(state, build(1)), "hash");
        JSONArray releases = catalogue(); releases.getJSONObject(1).put("historyCoverage", "unknown");
        assertFalse(InstallationLedger.changes(state, releases).getBoolean("complete"));
    }
    @Test public void untrustedCallerCannotUseTheProvider() {
        assertTrue(Binder.getCallingUid() != 2000 && Binder.getCallingUid() != 0);
        try { new ReleaseProvider().call("snapshot", null, null); fail("Expected caller rejection"); }
        catch (SecurityException expected) { }
    }
    @Test public void runtimeStatsRejectEveryCallerExceptExactRootAndShell() {
        try {
            for (int uid : new int[] { 1000, 10001, 102000 }) {
                org.robolectric.shadows.ShadowBinder.setCallingUid(uid);
                try { new ReleaseProvider().call("runtime-stats", null, null); fail("Expected caller rejection"); }
                catch (SecurityException expected) { }
            }
            for (int uid : new int[] { 0, 2000 }) {
                org.robolectric.shadows.ShadowBinder.setCallingUid(uid);
                long before = android.os.SystemClock.elapsedRealtime();
                android.os.Bundle result = new ReleaseProvider().call("runtime-stats", null, null);
                long after = android.os.SystemClock.elapsedRealtime();
                assertTrue(result.get("capturedElapsedRealtimeMs") instanceof Long);
                assertTrue(result.getLong("capturedElapsedRealtimeMs") >= before);
                assertTrue(result.getLong("capturedElapsedRealtimeMs") <= after);
                assertTrue(result.size() >= 5 && result.size() <= 9);
            }
        } finally { org.robolectric.shadows.ShadowBinder.reset(); }
    }
    @Test public void runtimeStatsRejectSelectorsAndAnyExtras() {
        try {
            org.robolectric.shadows.ShadowBinder.setCallingUid(2000);
            try { new ReleaseProvider().call("runtime-stats", "1234", null); fail("No process selectors"); }
            catch (IllegalArgumentException expected) { }
            android.os.Bundle extras = new android.os.Bundle(); extras.putInt("pid", 1234);
            try { new ReleaseProvider().call("runtime-stats", null, extras); fail("No process selectors"); }
            catch (IllegalArgumentException expected) { }
        } finally { org.robolectric.shadows.ShadowBinder.reset(); }
    }
    @Test public void runtimeStatsContainOnlyTheFourBoundedCountersAndMonotonicCapture() {
        final java.util.List<String> names = java.util.Arrays.asList("art.gc.gc-count", "art.gc.gc-time",
                "art.gc.blocking-gc-count", "art.gc.blocking-gc-time");
        final java.util.List<String> requested = new java.util.ArrayList<>();
        android.os.Bundle result = ReleaseProvider.runtimeStats(42, name -> {
            requested.add(name);
            int index = names.indexOf(name);
            assertTrue("Only the fixed own-process counter allowlist may be read", index >= 0);
            return new String[] { "0", "123", "9223372036854775807", null }[index];
        });
        assertEquals(names, requested);
        assertEquals(42, result.getLong("capturedElapsedRealtimeMs"));
        assertEquals(8, result.size());
        assertTrue(result.getBoolean(names.get(0) + ".available"));
        assertEquals(Long.valueOf(0), result.get(names.get(0)));
        assertEquals(Long.valueOf(123), result.get(names.get(1)));
        assertEquals(Long.valueOf(Long.MAX_VALUE), result.get(names.get(2)));
        assertFalse(result.getBoolean(names.get(3) + ".available"));
        assertFalse(result.containsKey(names.get(3)));
        for (String key : result.keySet()) {
            assertTrue(key, key.equals("capturedElapsedRealtimeMs") || names.contains(key) ||
                    names.stream().anyMatch(name -> key.equals(name + ".available")));
        }
    }
    @Test public void unavailableMalformedOverflowAndFailingRuntimeCountersNeverBecomeZero() {
        for (String raw : new String[] { null, "", "unavailable", "-1", "+1", "1.5", " 1", "1ms",
                "9223372036854775808", "00000000000000000000" }) {
            android.os.Bundle result = ReleaseProvider.runtimeStats(1, name -> raw);
            assertEquals(5, result.size());
            assertFalse(result.getBoolean("art.gc.gc-count.available"));
            assertFalse(result.containsKey("art.gc.gc-count"));
        }
        android.os.Bundle result = ReleaseProvider.runtimeStats(1, name -> {
            if (name.equals("art.gc.gc-count")) throw new UnsupportedOperationException();
            if (name.equals("art.gc.gc-time")) throw new UnsatisfiedLinkError();
            return "7";
        });
        assertFalse(result.containsKey("art.gc.gc-count"));
        assertFalse(result.containsKey("art.gc.gc-time"));
        assertEquals(Long.valueOf(7), result.get("art.gc.blocking-gc-count"));
        assertEquals(Long.valueOf(7), result.get("art.gc.blocking-gc-time"));
    }
    @Test public void arbitrarySettingsAreNotRetainedInReleaseIdentity() throws Exception {
        JSONObject value = build(1).put("gatewayKey", "not-a-real-secret").put("endpoint", "https://example.invalid");
        JSONObject clean = InstallationLedger.sanitize(value);
        assertFalse(clean.has("gatewayKey")); assertFalse(clean.has("endpoint"));
    }
    private void rejected(JSONObject state, JSONObject receipt, String hash) throws Exception {
        try { InstallationLedger.accept(state, receipt, hash); fail("Evidence must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }
}
