package no5.sonar.windowrecovery;

/** Small persistent policy. No Android lifecycle, process, network or drawing side effects. */
public final class RecoveryBudgetPolicy {
    public enum Action { WINDOW_RELAYOUT, VIEW_REBUILD, RX_RECONNECT, APP_RESTART }
    public enum Fault { MISSING_WINDOW_SURFACE, OWN_GPU_FAILED, PRESENTATION_MISSING, RX_STALLED, COMPONENT_RECOVERY_FAILED }
    public interface Store {
        State load() throws Exception;
        /** Must durably finish before returning; a failed write must never grant an action. */
        void save(State value) throws Exception;
    }
    public static final class State {
        public final String window;
        public final int windowMask;
        public final boolean appRestartConsumed;
        public State(String window,int mask,boolean app){
            if(window==null||window.length()>128||mask<0||mask>7)throw new IllegalArgumentException("invalid budget state");
            this.window=window;windowMask=mask;appRestartConsumed=app;
        }
    }
    public static final class Evidence {
        public final String window,source;
        public final boolean ownQualified,foregroundResumedVisible,unfrozen,windowBaseline;
        public final long originUpperAgeMs,observedElapsedNanos;
        public final Fault fault;
        public Evidence(String window,String source,boolean own,boolean foreground,boolean unfrozen,
                        boolean baseline,long age,long observed,Fault fault){
            this.window=window;this.source=source;ownQualified=own;foregroundResumedVisible=foreground;
            this.unfrozen=unfrozen;windowBaseline=baseline;originUpperAgeMs=age;observedElapsedNanos=observed;this.fault=fault;
        }
    }
    private final Store store;
    private State state;
    public RecoveryBudgetPolicy(Store storage) throws Exception {
        store=storage;state=store.load();
        if(state==null)throw new IllegalStateException("missing explicitly initialized budget");
    }
    public static boolean fresh(Evidence e,long now){
        if(e==null||e.window==null||e.window.isEmpty()||e.window.length()>128||e.source==null||e.source.isEmpty()||e.source.length()>512
                ||!e.ownQualified||!e.foregroundResumedVisible||!e.unfrozen||!e.windowBaseline
                ||e.originUpperAgeMs<0||e.originUpperAgeMs>2000||e.observedElapsedNanos<0||now<e.observedElapsedNanos)return false;
        try{
            long nanos=Math.subtractExact(now,e.observedElapsedNanos);
            long elapsed=nanos/1000000+(nanos%1000000==0?0:1);
            return Math.addExact(e.originUpperAgeMs,elapsed)<=2000;
        }catch(ArithmeticException overflow){return false;}
    }
    private static boolean matches(Action action,Fault fault){
        if(action==Action.WINDOW_RELAYOUT)return fault==Fault.MISSING_WINDOW_SURFACE;
        if(action==Action.VIEW_REBUILD)return fault==Fault.OWN_GPU_FAILED||fault==Fault.PRESENTATION_MISSING;
        if(action==Action.RX_RECONNECT)return fault==Fault.RX_STALLED;
        return action==Action.APP_RESTART&&fault==Fault.COMPONENT_RECOVERY_FAILED;
    }
    /** Called by the single ledger writer only, after a fresh native identity/focus check. */
    public synchronized boolean consume(Action action,Evidence evidence,String currentWindow,String currentSource,long now) throws Exception {
        if(action==null||!fresh(evidence,now)||!evidence.window.equals(currentWindow)||!evidence.source.equals(currentSource)
                ||!matches(action,evidence.fault))return false;
        int mask=state.window.equals(currentWindow)?state.windowMask:0;
        if(action==Action.APP_RESTART){
            // A component attempt must precede the last escalation. Its outcome
            // remains a failure; restarting an app never qualifies the old run.
            if(state.appRestartConsumed||mask==0)return false;
        }else if((mask&(1<<action.ordinal()))!=0)return false;
        State next=new State(currentWindow,action==Action.APP_RESTART?mask:mask|(1<<action.ordinal()),
            state.appRestartConsumed||action==Action.APP_RESTART);
        store.save(next); // Never publish a permit before durable consumption.
        state=next;
        return true;
    }
    public synchronized State snapshot(){return state;}
}
