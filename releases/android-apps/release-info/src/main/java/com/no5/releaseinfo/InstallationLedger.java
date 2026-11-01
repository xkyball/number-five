package com.no5.releaseinfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Installation evidence, never a last-opened-version tracker. No Android dependencies. */
public final class InstallationLedger {
    private InstallationLedger() {}

    public static JSONObject observe(JSONObject saved, JSONObject current, long first, long updated, int user) throws JSONException {
        if (saved != null && saved.optLong("firstInstallTime") == first && saved.optLong("lastUpdateTime") == updated
                && saved.optInt("userId", -1) == user && same(saved.optJSONObject("current"), current)) return saved;
        // Even a replacement broadcast cannot prove that earlier replacement broadcasts were delivered.
        JSONObject next = new JSONObject().put("schema", 1).put("current", current)
                .put("firstInstallTime", first).put("lastUpdateTime", updated).put("userId", user)
                .put("challenge", UUID.randomUUID().toString()).put("baseline", "unknown");
        if (saved != null) next.put("lastObserved", saved.optJSONObject("current"));
        return next;
    }

    public static boolean same(JSONObject a, JSONObject b) {
        if (a == null || b == null) return false;
        if (!a.optString("apkSha256").isEmpty() && !b.optString("apkSha256").isEmpty()
                && !a.optString("apkSha256").equals(b.optString("apkSha256"))) return false;
        return a.optString("packageName").equals(b.optString("packageName"))
                && a.optLong("versionCode", -1) == b.optLong("versionCode", -2)
                && a.optString("versionName").equals(b.optString("versionName"))
                && a.optString("buildId").equals(b.optString("buildId"));
    }

    public static JSONObject accept(JSONObject state, JSONObject receipt, String actualApkHash) throws JSONException {
        if (receipt.getInt("schema") != 1 || !state.getString("challenge").equals(receipt.getString("challenge"))
                || receipt.getInt("userId") != state.getInt("userId")
                || receipt.getLong("firstInstallTime") != state.getLong("firstInstallTime")
                || receipt.getLong("lastUpdateTime") != state.getLong("lastUpdateTime")
                || !same(state.getJSONObject("current"), receipt.getJSONObject("current"))
                || !actualApkHash.equals(receipt.getString("apkSha256"))) throw new IllegalArgumentException("Installation evidence does not match this installation");
        String operation = receipt.getString("operation");
        JSONObject previous = receipt.optJSONObject("previous");
        if (!operation.equals("replace") && !operation.equals("fresh")) throw new IllegalArgumentException("Unsupported installation operation");
        if (operation.equals("replace")) {
            if (previous == null || !previous.getString("packageName").equals(state.getJSONObject("current").getString("packageName"))
                    || receipt.getLong("previousFirstInstallTime") != state.getLong("firstInstallTime")) throw new IllegalArgumentException("Replacement must preserve the installation epoch");
            previous = sanitize(previous);
        } else if (previous != null) throw new IllegalArgumentException("Fresh installation has no predecessor");
        String receiptId = receipt.getString("receiptId");
        if (!receiptId.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid receipt ID");
        if (state.has("receiptId") && !state.getString("receiptId").equals(receiptId)) throw new IllegalArgumentException("Installation already has evidence");
        JSONObject next = new JSONObject(state.toString());
        next.getJSONObject("current").put("apkSha256", actualApkHash);
        next.put("baseline", operation.equals("fresh") ? "first-install" : "verified-installation")
                .put("previous", previous == null ? JSONObject.NULL : previous).put("receiptId", receiptId);
        return next;
    }

    /** Whitelisted public release fields. No settings, endpoints, keys, arbitrary payloads or logs. */
    public static JSONObject sanitize(JSONObject input) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : new String[]{"packageName", "versionName", "buildId", "sourceCommit", "releaseId", "apkSha256"}) {
            String value = input.optString(key, "");
            if (!value.matches("[a-zA-Z0-9._:+-]{0,180}")) throw new IllegalArgumentException("Invalid release field");
            out.put(key, value);
        }
        if (out.getString("packageName").isEmpty() || out.getString("versionName").isEmpty()
                || out.getString("buildId").isEmpty()) throw new IllegalArgumentException("Incomplete release identity");
        out.put("versionCode", input.getLong("versionCode")).put("sequence", input.optInt("sequence", 0));
        if (input.has("sourceDirty")) out.put("sourceDirty", input.getBoolean("sourceDirty"));
        return out;
    }

    public static JSONObject changes(JSONObject state, JSONArray releases) throws JSONException {
        JSONObject current = state.getJSONObject("current"), previous = state.optJSONObject("previous");
        LinkedHashMap<String, String> notes = new LinkedHashMap<>();
        String kind = state.getString("baseline");
        int from = previous == null ? 0 : previous.optInt("sequence", 0), to = current.optInt("sequence", 0);
        boolean complete = true;
        if (!kind.equals("verified-installation")) return result(kind, notes, false);
        if (current.getLong("versionCode") < previous.getLong("versionCode")) return result("downgrade", notes, true);
        // IDs must exist in this app's catalogue; version names/codes never imply a known baseline.
        if (!catalogued(previous, releases) || !catalogued(current, releases)) return result("unmapped-release", notes, false);
        if (to < from || current.getLong("versionCode") < previous.getLong("versionCode")) return result("downgrade", notes, true);
        if (to == from) return result(same(previous, current) ? "same-build" : "different-build", notes, true);
        int found = 0;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            int seq = release.getInt("sequence");
            if (seq > from && seq <= to) {
                found++;
                JSONArray changes = release.getJSONArray("changes");
                if (release.has("historyCoverage")) complete = false;
                for (int j = 0; j < changes.length(); j++) {
                    JSONObject change = changes.getJSONObject(j);
                    notes.put(change.getString("id"), change.getString("text"));
                }
            }
        }
        complete &= found == to - from;
        return result("update", notes, complete);
    }

    private static boolean catalogued(JSONObject identity, JSONArray releases) throws JSONException {
        if (identity == null || identity.optInt("sequence", 0) < 1) return false;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.getString("id").equals(identity.optString("releaseId"))
                    && release.getInt("sequence") == identity.optInt("sequence")
                    && release.getString("versionName").equals(identity.optString("versionName"))
                    && release.getLong("versionCode") == identity.optLong("versionCode")) return true;
        }
        return false;
    }

    private static JSONObject result(String kind, LinkedHashMap<String, String> notes, boolean complete) throws JSONException {
        JSONArray array = new JSONArray();
        for (String note : notes.values()) array.put(note);
        return new JSONObject().put("kind", kind).put("complete", complete).put("notes", array);
    }
}
