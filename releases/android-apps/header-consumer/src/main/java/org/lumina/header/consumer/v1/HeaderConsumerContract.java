package org.lumina.header.consumer.v1;

import java.util.Objects;

/** Separate read-only consumer DTOs. No Android objects, factories, commands or navigation. */
public final class HeaderConsumerContract {
    private HeaderConsumerContract() {}
    public static final String SOCKET_NAME = "org.lumina.header.consumer.v1";
    public static final int VERSION = 1, MAX_FRAME_BYTES = 16384, MAX_SNAPSHOT_BYTES = 8192;
    public static final long FRAME_DEADLINE_MS = 250, FOCUS_MAX_AGE_UPTIME_MS = 250;
    public static final long HEARTBEAT_MAX_GAP_MS = 100, MAX_TRANSPORT_LEASE_MS = 500;
    public static final long MAX_PUBLICATION_LEASE_MS = 5000;
    // Append only. HeaderFrameCodec writes ordinal bytes; existing page codes must not move.
    public enum Page { HOME, APPS, HELM, AUDIO, SONAR, SETTINGS, CHART, ROUTES, AIS, LAYERS, DASHBOARD }
    public enum Profile { BASE_SYSTEM, HOME_CONTEXT, BRAND_SOURCE, MARINE_SAFETY }
    public enum Theme { DAY, RED_NIGHT }
    public enum Quality { LIVE, STALE, LOST, INVALID, UNAVAILABLE, UNKNOWN }
    public enum Provenance { REAL, SIMULATED, UNKNOWN }
    public enum Transport { LOADING, READY, EMPTY, OFFLINE, STALE, ERROR, UNKNOWN }
    public enum Unit { RPM, KNOTS, KMH, MPH }
    public enum Reason { NONE, NO_FOREGROUND_SCOPE, NO_PUBLICATION, PRODUCER_DEAD, NOT_ALLOWED,
        CALLER_MISMATCH, WRONG_FOREGROUND, WRONG_PAGE, WRONG_SOURCE_SESSION, OUT_OF_ORDER,
        EXPIRED, INVALID_ENVELOPE, INVALID_PROFILE, INVALID_DATA }
    public enum AbortReason { NONE, NOT_ADMITTED, DISCONNECTED, MALFORMED_FRAME, FRAME_DEADLINE,
        CLOCK_ROLLBACK, SESSION_MISMATCH, FOCUS_UNAVAILABLE, FOCUS_MISMATCH, EPOCH_MAPPING,
        OUT_OF_ORDER, STATE_REWRITE, INVALID_SNAPSHOT, HEARTBEAT_TIMEOUT, TRANSPORT_EXPIRED }

    /** Android production adapter supplies three different clocks; do not substitute wall time. */
    public interface Clock { long elapsedRealtimeMs(); long uptimeMs(); long epochMs(); }

    /** Trusted OS observer input, NEVER constructed from a HELM frame by the Root receiver. */
    public interface TrustedRootFocusPort { RootFocus current(); }
    public static final class RootFocus {
        public final String rootControlSession, rootWindowIdentity, packageName;
        public final long rootEpoch, observedUptimeMs;
        public final int user, display;
        public RootFocus(String rootControlSession, long rootEpoch, String rootWindowIdentity,
                         String packageName, int user, int display, long observedUptimeMs) {
            this.rootControlSession=id(rootControlSession,80); this.rootEpoch=positive(rootEpoch);
            this.rootWindowIdentity=id(rootWindowIdentity,256); this.packageName=id(packageName,256);
            if(user!=0 || display!=0) throw new IllegalArgumentException("user/display");
            this.user=user; this.display=display; this.observedUptimeMs=nonnegative(observedUptimeMs);
        }
        public boolean sameIdentity(RootFocus other) {
            return other!=null && rootEpoch==other.rootEpoch && user==other.user && display==other.display &&
                rootControlSession.equals(other.rootControlSession) && rootWindowIdentity.equals(other.rootWindowIdentity) &&
                packageName.equals(other.packageName);
        }
    }

    /** Mapping captured when authenticated Root focus is adopted by the existing HELM host. */
    public static final class ScopeEvidence {
        public final String rootControlSession, consumerSession, controllerSession, rootWindowIdentity, packageName;
        public final long rootEpoch, hostForegroundEpoch, focusObservedUptime;
        public final int user, display;
        public ScopeEvidence(String rootControlSession, String consumerSession, String controllerSession,
                             long rootEpoch, String rootWindowIdentity, long hostForegroundEpoch,
                             String packageName, int user, int display, long focusObservedUptime) {
            this.rootControlSession=id(rootControlSession,80); this.consumerSession=nonce(consumerSession);
            this.controllerSession=id(controllerSession,80); this.rootEpoch=positive(rootEpoch);
            this.rootWindowIdentity=id(rootWindowIdentity,256); this.hostForegroundEpoch=positive(hostForegroundEpoch);
            this.packageName=id(packageName,256); if(user!=0||display!=0) throw new IllegalArgumentException("scope display");
            this.user=user;this.display=display;this.focusObservedUptime=nonnegative(focusObservedUptime);
        }
        public boolean sameMapping(ScopeEvidence s) {
            return s!=null && rootControlSession.equals(s.rootControlSession) && consumerSession.equals(s.consumerSession) &&
                controllerSession.equals(s.controllerSession) && rootEpoch==s.rootEpoch &&
                rootWindowIdentity.equals(s.rootWindowIdentity) && hostForegroundEpoch==s.hostForegroundEpoch &&
                packageName.equals(s.packageName) && user==s.user && display==s.display;
        }
        public boolean matchesRoot(RootFocus r) {
            return r!=null && rootControlSession.equals(r.rootControlSession) && rootEpoch==r.rootEpoch &&
                rootWindowIdentity.equals(r.rootWindowIdentity) && packageName.equals(r.packageName) && user==r.user && display==r.display;
        }
        public ScopeEvidence withObservedRoot(RootFocus r) {
            if(!matchesRoot(r)) throw new IllegalArgumentException("focus identity");
            return new ScopeEvidence(rootControlSession,consumerSession,controllerSession,rootEpoch,rootWindowIdentity,
                hostForegroundEpoch,packageName,user,display,r.observedUptimeMs);
        }
    }

    public static final class Publication {
        public final String packageName, producerSession, sourceSessionId;
        public final long foregroundEpoch, pageGeneration, sequence, publishedAtElapsedMs, expiresAtElapsedMs;
        public Publication(String packageName,String producerSession,long foregroundEpoch,long pageGeneration,
                           String sourceSessionId,long sequence,long publishedAtElapsedMs,long expiresAtElapsedMs) {
            this.packageName=id(packageName,256);this.producerSession=id(producerSession,256);
            this.foregroundEpoch=nonnegative(foregroundEpoch);this.pageGeneration=nonnegative(pageGeneration);
            this.sourceSessionId=sourceSessionId==null?null:id(sourceSessionId,256);this.sequence=nonnegative(sequence);
            this.publishedAtElapsedMs=nonnegative(publishedAtElapsedMs);this.expiresAtElapsedMs=nonnegative(expiresAtElapsedMs);
        }
    }
    public static final class Freshness {
        public final long staleAfterMs,lostAfterMs;
        public Freshness(long staleAfterMs,long lostAfterMs) {
            if(staleAfterMs<=0||lostAfterMs<staleAfterMs)throw new IllegalArgumentException("freshness");
            this.staleAfterMs=staleAfterMs;this.lostAfterMs=lostAfterMs;
        }
    }
    public static final class Measurement {
        public final Double value;public final Unit unit; public final String sourceId;
        public final Long sourceObservedAtEpochMs;public final Quality quality;public final Provenance provenance;
        public final Freshness freshness;
        public Measurement(Double value,Unit unit,String sourceId,Long observed,Quality quality,Provenance provenance,Freshness freshness) {
            this.value=value;this.unit=req(unit);this.sourceId=text(sourceId,256);this.sourceObservedAtEpochMs=optionalTime(observed);
            this.quality=req(quality);this.provenance=req(provenance);this.freshness=req(freshness);
        }
        public Measurement at(long nowEpoch) {
            Quality q=age(quality,sourceId,sourceObservedAtEpochMs,freshness,nowEpoch);
            boolean usable=value!=null&&!value.isNaN()&&!value.isInfinite()&&value>=0;
            if(value!=null&&!usable)q=Quality.INVALID;
            else if(value==null&&(q==Quality.LIVE||q==Quality.STALE))q=Quality.UNKNOWN;
            return new Measurement(usable&&(q==Quality.LIVE||q==Quality.STALE)?value:null,unit,sourceId,sourceObservedAtEpochMs,q,provenance,freshness);
        }
    }
    public static final class Extras {
        public final Measurement portRpm,starboardRpm,sog;
        public Extras(Measurement portRpm,Measurement starboardRpm,Measurement sog) {
            this.portRpm=portRpm;this.starboardRpm=starboardRpm;this.sog=sog;
            if((portRpm!=null&&portRpm.unit!=Unit.RPM)||(starboardRpm!=null&&starboardRpm.unit!=Unit.RPM)||
                (sog!=null&&sog.unit==Unit.RPM))throw new IllegalArgumentException("units");
        }
        public boolean empty(){return portRpm==null&&starboardRpm==null&&sog==null;}
        public Extras at(long now){return new Extras(portRpm==null?null:portRpm.at(now),starboardRpm==null?null:starboardRpm.at(now),sog==null?null:sog.at(now));}
    }
    public static final class Context {
        public final String title,subtitle,pageStatus;
        public Context(String title,String subtitle,String pageStatus){this.title=text(title,128);this.subtitle=text(subtitle,256);this.pageStatus=text(pageStatus,512);}
    }
    public static final class Source {
        public final String label,detail,sourceId,backendSessionId;
        public final Transport transport;public final Quality quality;public final Provenance provenance;public final Freshness freshness;
        public Source(String label,String detail,String sourceId,String backendSessionId,Transport transport,Quality quality,Provenance provenance,Freshness freshness) {
            this.label=text(label,128);this.detail=text(detail,512);this.sourceId=text(sourceId,256);this.backendSessionId=text(backendSessionId,256);
            this.transport=req(transport);this.quality=req(quality);this.provenance=req(provenance);this.freshness=req(freshness);
        }
        public Source at(Long observed,long now){return new Source(label,detail,sourceId,backendSessionId,transport,age(quality,sourceId,observed,freshness,now),provenance,freshness);}
    }
    /** Mirrors all HeaderSnapshotV1 fields from feature18243b6. Publication is NEVER rewritten. */
    public static final class Snapshot {
        public final int schemaVersion;public final Publication publication;public final Page pageId;
        public final Theme theme;public final Profile profile;public final Context context;public final Source source;
        public final Long sourceObservedAtEpochMs;public final Extras extras;
        public Snapshot(Publication publication,Page pageId,Theme theme,Profile profile,Context context,Source source,Long observed,Extras extras,int schemaVersion) {
            if(schemaVersion!=1)throw new IllegalArgumentException("schema");this.schemaVersion=schemaVersion;
            this.publication=req(publication);this.pageId=req(pageId);this.theme=req(theme);this.profile=req(profile);
            this.context=req(context);this.source=req(source);this.sourceObservedAtEpochMs=optionalTime(observed);this.extras=req(extras);
            if(expectedProfile(publication.packageName,pageId)!=profile || (profile!=Profile.MARINE_SAFETY&&!extras.empty()))throw new IllegalArgumentException("profile");
        }
        public Snapshot at(long now){return new Snapshot(publication,pageId,theme,profile,context,source.at(sourceObservedAtEpochMs,now),sourceObservedAtEpochMs,extras.at(now),schemaVersion);}
        public boolean leaseValid(long elapsed){Publication p=publication;return p.publishedAtElapsedMs<=elapsed&&p.expiresAtElapsedMs>elapsed&&
            p.expiresAtElapsedMs>p.publishedAtElapsedMs&&p.expiresAtElapsedMs-p.publishedAtElapsedMs<=MAX_PUBLICATION_LEASE_MS;}
    }
    public static final class Frame {
        public final ScopeEvidence scope;public final long transportSequence,stateRevision,issuedAtElapsedMs,expiresAtElapsedMs;
        public final Theme baseTheme;public final Reason reason;public final Snapshot snapshot;
        public Frame(ScopeEvidence scope,long transportSequence,long stateRevision,long issuedAtElapsedMs,long expiresAtElapsedMs,Theme baseTheme,Reason reason,Snapshot snapshot) {
            this.scope=req(scope);this.transportSequence=positive(transportSequence);this.stateRevision=positive(stateRevision);
            this.issuedAtElapsedMs=nonnegative(issuedAtElapsedMs);this.expiresAtElapsedMs=nonnegative(expiresAtElapsedMs);
            this.baseTheme=req(baseTheme);this.reason=req(reason);this.snapshot=snapshot;
            if((snapshot!=null)!=(reason==Reason.NONE))throw new IllegalArgumentException("state/reason");
        }
    }

    /** Root-UI port, also supplies independent DOWN focus when snapshot is null. */
    public static final class ConsumerState {
        public final ScopeEvidence scope;public final Snapshot snapshot;public final Theme baseTheme;public final Reason reason;
        public final AbortReason abortReason;public final long transportValidUntilElapsedMs;public final boolean transportAlive;
        public ConsumerState(ScopeEvidence scope,Snapshot snapshot,Theme baseTheme,Reason reason,AbortReason abortReason,long validUntil,boolean transportAlive) {
            this.scope=scope;this.snapshot=snapshot;this.baseTheme=req(baseTheme);this.reason=req(reason);this.abortReason=req(abortReason);
            this.transportValidUntilElapsedMs=nonnegative(validUntil);this.transportAlive=transportAlive;
        }
        public String brand(){return "NO 5";}public String subtitle(){return "STELLENBOSCH";}
        public Profile profile(){return snapshot==null?Profile.BASE_SYSTEM:snapshot.profile;}
    }

    public static long deadline(long start,long delay){return start>Long.MAX_VALUE-delay?Long.MAX_VALUE:start+delay;}
    public static boolean freshUptime(long observed,long now){return observed>=0&&now>=observed&&now-observed<=FOCUS_MAX_AGE_UPTIME_MS;}
    private static Quality age(Quality q,String source,Long observed,Freshness p,long now) {
        if(q!=Quality.LIVE&&q!=Quality.STALE)return q;
        if(source==null||source.trim().isEmpty()||observed==null||now<0)return Quality.UNKNOWN;
        double age=(double)now-(double)observed;
        if(age < -5000)return Quality.STALE;
        if(age > p.lostAfterMs)return Quality.LOST;
        if(age > p.staleAfterMs||q==Quality.STALE)return Quality.STALE;
        return Quality.LIVE;
    }
    public static Profile expectedProfile(String pkg,Page page) {
        if("com.no5.home".equals(pkg)&&(page==Page.HOME||page==Page.APPS))return Profile.HOME_CONTEXT;
        if("com.no5.map".equals(pkg)&&(page==Page.CHART||page==Page.ROUTES||page==Page.AIS||page==Page.LAYERS||page==Page.SETTINGS))return Profile.BRAND_SOURCE;
        if("com.no5.helm".equals(pkg)&&page==Page.HELM)return Profile.BRAND_SOURCE;
        if("com.no5.helm".equals(pkg)&&(page==Page.AUDIO||page==Page.SONAR||page==Page.SETTINGS||page==Page.DASHBOARD))return Profile.MARINE_SAFETY;
        return null;
    }
    static String text(String s,int max){if(s!=null&&s.length()>max)throw new IllegalArgumentException("text size");return s;}
    static String id(String s,int max){if(s==null||s.trim().isEmpty()||s.length()>max)throw new IllegalArgumentException("id");return s;}
    static String nonce(String s){if(s==null||!s.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("nonce");return s;}
    static long nonnegative(long n){if(n<0)throw new IllegalArgumentException("negative");return n;}
    static long positive(long n){if(n<=0)throw new IllegalArgumentException("positive");return n;}
    static Long optionalTime(Long n){if(n!=null)nonnegative(n);return n;}
    static <T>T req(T n){return Objects.requireNonNull(n);}
}
