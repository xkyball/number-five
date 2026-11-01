package no5.sonar.windowrecovery;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import com.no5.domain.SonarOriginClockProof;
import java.util.function.Consumer;

/** Candidate lifecycle adapter. No invalidation, pixel substitution, or restart.
 * Attach once per Activity Window; progress admission is supplied by the native
 * source/controller and also fenced by actual Window focus/visibility here.
 * Not installed in HELM. Native core test and host policy tests are separate.
 */
public final class WindowRecoveryBinding implements AutoCloseable {
    private final Window window;
    private final View decor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowDrawPolicy policy = new WindowDrawPolicy();
    private final BackingSurfaceProbe probe;
    public interface ProofProvider {
        boolean request(Long pingId, Long originalEpochMs, Consumer<SonarOriginClockProof> completed);
    }
    private ProofProvider proofProvider;
    public interface RepairBudget { void request(SonarOriginClockProof proof, Consumer<Boolean> answer); }
    private RepairBudget repairBudget;
    public void setRepairBudget(RepairBudget value) { mainThread(); repairBudget=value; }
    public String instanceId() { return instance; }
    public boolean isEligible() { return !closed && windowEligible() && baselineCommitted; }
    private final RecoveryProofArbiter recoveryProofs = new RecoveryProofArbiter();
    public boolean hasPendingRecoveryWork() { return recoveryProofs.busy() || proofPending || recoveryPending || verificationPending; }
    public long beginComponentInspection() {
        mainThread();if(!isEligible()||hasPendingRecoveryWork())return 0;
        long token=recoveryProofs.acquire();if(token!=0)lastSuspectSequence=drawSequence;return token;
    }
    public void endComponentInspection(long token){mainThread();recoveryProofs.release(token);}

    public String currentContext() { return sourceContext; }
    public void inspectBackingSurface(long token, SonarOriginClockProof proof, String context, Consumer<BackingSurfaceProbe.Result> answer) {
        mainThread();
        Long upper=proof==null?null:proof.upperAgeMs(SystemClock.elapsedRealtimeNanos());
        if (!recoveryProofs.owns(token) || !isEligible() || upper == null || !context.equals(sourceContext)) {
            recoveryProofs.release(token); answer.accept(BackingSurfaceProbe.Result.NOT_ELIGIBLE);return;
        }
        lastOriginUpperAgeMs=upper;
        probeVerifiedSurface(proof,context,result->{
            if(!recoveryProofs.owns(token))return;
            recoveryProofs.release(token);answer.accept(result);
        });
    }
    private String sourceContext;
    private Long originalEpochMs, currentPing;
    private boolean proofPending;
    private long proofEpoch;
    public volatile long lastOriginUpperAgeMs = -1;
    public void setProofProvider(ProofProvider provider) { mainThread(); proofProvider = provider; }
    private volatile boolean closed;
    private boolean eligible, scheduled, baselineRequested, recoveryPending, verificationPending;
    private long drawSequence, lastSuspectSequence = -1;
    private final String instance = android.os.Process.myPid() + ":" + SystemClock.elapsedRealtimeNanos();
    public volatile String lastDiagnostic = "NOT_ACTIVATED";
    public volatile int recoveryRequests, recoveryFailures, probeErrors;
    public volatile boolean baselineCommitted;

    private final Runnable check = this::checkMissingDraw;
    private final Runnable recoveryDeadline = () -> {
        if (!closed && recoveryPending) {
            recoveryPending = false; recoveryFailures++; report("RECOVERY_DRAW_TIMEOUT");
        }
    };
    private final Runnable commit = this::frameCommitted;
    private void frameCommitted() {
        if (closed) return;
        if (!windowEligible()) {
            verificationPending = false;
            baselineRequested = probe.baselineVerified();
            return;
        }
        if (!recoveryPending) {
            probe.committedWindowFrame(); verificationPending = false;
            baselineCommitted = probe.baselineVerified();
            lastDiagnostic = "WINDOW_BASELINE_COMMITTED";
            return;
        }
        probe.check(false, (result, code, detail) -> {
            if (closed) return;
            verificationPending = false;
            if (result != BackingSurfaceProbe.Result.VALID) {
                probeErrors++;
                if (recoveryPending) recoveryFailures++;
                recoveryPending = false; main.removeCallbacks(recoveryDeadline);
                report("COMMITTED_SURFACE_PROBE_" + result.name() + "_" + code);
            } else if (recoveryPending) {
                recoveryPending = false; main.removeCallbacks(recoveryDeadline);
                report("RECOVERY_FRAME_COMMITTED_AND_SURFACE_VALID");
            }
        });
    }
    private final ViewTreeObserver.OnDrawListener draws = this::drawn;
    private void drawn() {
        if (closed) return;
        drawSequence++; policy.drawn(SystemClock.uptimeMillis());
    }
    private final ViewTreeObserver.OnPreDrawListener beforeDraw = this::beforeDrawing;
    private boolean beforeDrawing() {
        if (closed) return true;
        // At most an initial commit and one recovery commit. No probe in every draw.
        // Register before performDraw captures callbacks; onDraw is too late.
        if (windowEligible() && (!baselineRequested || recoveryPending) && !verificationPending) {
            baselineRequested = true; verificationPending = true;
            decor.getViewTreeObserver().registerFrameCommitCallback(commit);
        }
        return true;
    }

    public WindowRecoveryBinding(Window ownedWindow) {
        mainThread(); window = ownedWindow; decor = window.getDecorView();
        probe = new BackingSurfaceProbe(window);
        if (Build.VERSION.SDK_INT == 29) {
            decor.getViewTreeObserver().addOnDrawListener(draws);
            decor.getViewTreeObserver().addOnPreDrawListener(beforeDraw);
            lastDiagnostic = "WAITING_FOR_VALID_WINDOW_BASELINE";
        } else lastDiagnostic = "NOT_REQUIRED_ON_THIS_API";
    }
    private void mainThread() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("main thread required");
    }
    private void report(String code) {
        lastDiagnostic = code;
        if (code.contains("TIMEOUT") || code.contains("ERROR") || code.contains("EXHAUSTED")
                || code.contains("NO_BASELINE")) Log.e("No5WindowRecovery", code);
        else Log.w("No5WindowRecovery", code);
    }
    private boolean windowEligible() {
        return eligible && decor.isAttachedToWindow() && decor.isShown()
            && decor.hasWindowFocus() && decor.getWindowVisibility() == View.VISIBLE
            && decor.getDisplay() != null && decor.getDisplay().getState() == android.view.Display.STATE_ON;
    }
    public void progress(String sourceSessionKey, Long pingId, boolean ownFreshLiveVisibleUnfrozen,
                         long originalTimestampEpochMs) {
        mainThread();
        if (closed || Build.VERSION.SDK_INT != 29) return;
        if (!java.util.Objects.equals(sourceContext,sourceSessionKey)) {
            proofEpoch++; proofPending=false; recoveryProofs.cancel(); lastSuspectSequence=-1;
        }
        eligible = ownFreshLiveVisibleUnfrozen;
        sourceContext = sourceSessionKey; currentPing = pingId; originalEpochMs = originalTimestampEpochMs;
        if (!windowEligible()) {
            pause();
            return;
        }
        policy.observedProgress(sourceSessionKey, pingId, true, SystemClock.uptimeMillis());
        if (!scheduled && lastSuspectSequence != drawSequence && !recoveryPending) {
            scheduled = true; main.postDelayed(check, WindowDrawPolicy.MISSING_DRAW_MS);
        }
    }
    public void pause() {
        mainThread();
        proofEpoch++; proofPending=false; recoveryProofs.cancel(); eligible = false; policy.pause(); main.removeCallbacks(check); scheduled = false;
        if (recoveryPending) {
            recoveryPending = false; main.removeCallbacks(recoveryDeadline);
            report("RECOVERY_CANCELLED_LIFECYCLE");
        }
    }
    private void checkMissingDraw() {
        scheduled = false;
        if (closed || !windowEligible() || !policy.needsProbe(SystemClock.uptimeMillis())
                || lastSuspectSequence == drawSequence || recoveryProofs.busy()) return;
        lastSuspectSequence = drawSequence;
        if (proofPending || proofProvider == null || originalEpochMs == null || currentPing == null) {
            probeErrors++; report("ORIGIN_CLOCK_PROOF_UNAVAILABLE"); return;
        }
        final String expectedContext = sourceContext;
        final Long expectedPing = currentPing;
        final long expectedProofEpoch=proofEpoch;
        final long expectedDrawSequence=drawSequence;
        final long lease=recoveryProofs.acquire();
        proofPending = true;
        boolean queued = proofProvider.request(expectedPing, originalEpochMs, proof -> {
            Runnable accept = () -> {
                if (expectedProofEpoch != proofEpoch || closed || !recoveryProofs.owns(lease)) return;
                proofPending = false;
                Long upper = proof == null ? null : proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos());
                if (closed || !windowEligible() || !expectedContext.equals(sourceContext)
                        || currentPing == null || currentPing < expectedPing || upper == null) {
                    recoveryProofs.release(lease);probeErrors++; report("ORIGIN_CLOCK_PROOF_REJECTED_OR_LIFECYCLE_CHANGED"); return;
                }
                lastOriginUpperAgeMs = upper;
                if(!policy.stillMissingAfterProof(expectedDrawSequence,drawSequence,SystemClock.uptimeMillis())){
                    recoveryProofs.release(lease);
                    if(expectedDrawSequence!=drawSequence)report("DRAW_RESUMED_BEFORE_PROBE_NO_ACTION");
                    else {probeErrors++;report("MISSING_DRAW_ADMISSION_EXPIRED_BEFORE_PROBE");}
                    return;
                }
                probeVerifiedSurface(proof, expectedContext,result->recoveryProofs.release(lease));
            };
            if (Looper.myLooper() == main.getLooper()) accept.run(); else main.post(accept);
        });
        if (!queued) { recoveryProofs.release(lease);proofPending = false; probeErrors++; report("ORIGIN_CLOCK_PROOF_NOT_QUEUED"); }
    }
    private void probeVerifiedSurface(SonarOriginClockProof proof, String expectedContext, Consumer<BackingSurfaceProbe.Result> completed) {
        Long upper = proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos());
        if (upper == null || !windowEligible() || !expectedContext.equals(sourceContext)) {
            probeErrors++; report("ORIGIN_CLOCK_PROOF_EXPIRED_BEFORE_SURFACE_PROBE");completed.accept(BackingSurfaceProbe.Result.NOT_ELIGIBLE); return;
        }
        probe.setRepairPermit(answer -> {
            if (repairBudget == null || !isEligible() || !expectedContext.equals(sourceContext)
                    || proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos()) == null) { answer.accept(false); return; }
            repairBudget.request(proof, granted -> answer.accept(granted && isEligible()
                && expectedContext.equals(sourceContext) && proof.upperAgeMs(android.os.SystemClock.elapsedRealtimeNanos()) != null));
        });
        probe.check(true, (result, code, detail) -> {
            if (closed) return;
            if (result == BackingSurfaceProbe.Result.RECOVERY_REQUESTED) {
                recoveryRequests++; recoveryPending = true; report("RECOVERY_REQUESTED");
                main.postDelayed(recoveryDeadline, WindowDrawPolicy.MISSING_DRAW_MS);
            } else if (result == BackingSurfaceProbe.Result.VALID) {
                report("DRAW_STALLED_BUT_BACKING_SURFACE_VALID_NO_RELAYOUT");
            } else {
                probeErrors++;
                report("STALLED_SURFACE_PROBE_" + result.name() + "_" + code);
            }
            completed.accept(result);
        });
    }
    /** Bounded public Activity.dump payload; no history, ADC, source URL, or preferences. */
    public String diagnosticLine() {
        return "No5WindowRecovery: v=1 instance=" + instance + " api=" + Build.VERSION.SDK_INT
            + " baseline=" + baselineCommitted + " requested=" + recoveryRequests
            + " failures=" + recoveryFailures + " probeErrors=" + probeErrors
            + " proofPending=" + proofPending + " sharedProofPending=" + recoveryProofs.busy() + " originUpperAgeMs=" + lastOriginUpperAgeMs
            + " closed=" + closed + " outcome=" + lastDiagnostic;
    }
    @Override public void close() {
        mainThread();
        if (closed) return;
        closed = true; recoveryProofs.cancel();main.removeCallbacks(check); main.removeCallbacks(recoveryDeadline);
        if (Build.VERSION.SDK_INT == 29 && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnDrawListener(draws);
            decor.getViewTreeObserver().removeOnPreDrawListener(beforeDraw);
            decor.getViewTreeObserver().unregisterFrameCommitCallback(commit);
        }
        probe.close();
    }
}
