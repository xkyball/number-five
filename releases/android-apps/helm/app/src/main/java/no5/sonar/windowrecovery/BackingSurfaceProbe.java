package no5.sonar.windowrecovery;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Experimental Window-only compatibility primitive, not installed in HELM.
 * A caller must additionally gate fresh own data, foreground/lifecycle and draws.
 * There is no timer or invalidate/requestLayout fallback. One action per instance.
 * The instance must belong to the Window lifetime, not a source/page/ping lifetime.
 */
public final class BackingSurfaceProbe implements AutoCloseable {
    public enum Result { VALID, RECOVERY_REQUESTED, NOT_ELIGIBLE, NO_BASELINE,
        EXHAUSTED, COPY_ERROR, CALL_ERROR, UNSUPPORTED, CLOSED, BUSY }
    public interface RepairPermit { void request(java.util.function.Consumer<Boolean> answer); }
    private RepairPermit permit;
    public void setRepairPermit(RepairPermit value) { mainThread(); permit=value; }
    public interface Callback { void completed(Result result, int copyCode, String detail); }
    private final Window window;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private boolean validBaseline, attempted, closed, inFlight;
    private int attempts;

    public BackingSurfaceProbe(Window window) {
        if (window == null) throw new IllegalArgumentException("owned Window required");
        this.window = window;
    }
    private void mainThread() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("main thread required");
    }
    public int attempts() { mainThread(); return attempts; }
    public boolean baselineVerified() { mainThread(); return validBaseline; }
    // Only the Window-lifetime adapter calls this from this exact decor's public
    // frame-commit callback. It avoids a healthy-window PixelCopy poll.
    void committedWindowFrame() {
        mainThread();
        View decor = window.peekDecorView();
        if (!closed && Build.VERSION.SDK_INT == 29 && decor != null
                && decor.isAttachedToWindow() && decor.isShown()) validBaseline = true;
    }

    public void check(boolean repairAdmittedByLifecycleAndFreshData, Callback callback) {
        mainThread();
        if (closed) { callback.completed(Result.CLOSED, -1, "closed"); return; }
        if (Build.VERSION.SDK_INT != 29) { callback.completed(Result.UNSUPPORTED, -1, "API29 only"); return; }
        if (inFlight) { callback.completed(Result.BUSY, -1, "one probe already pending"); return; }
        View decor = window.peekDecorView();
        if (decor == null || !decor.isAttachedToWindow() || !decor.isShown()
                || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            callback.completed(Result.NOT_ELIGIBLE, -1, "Window not attached/shown"); return;
        }
        inFlight = true;
        try {
            PixelCopy.request(window, new Rect(0, 0, 1, 1), pixel, code -> {
                inFlight = false;
                if (closed) {
                    pixel.recycle(); callback.completed(Result.CLOSED, code, "closed during copy"); return;
                }
                if (code == PixelCopy.SUCCESS) {
                    validBaseline = true;
                    callback.completed(Result.VALID, code, "backing Surface verified");
                } else {
                    // No native callback error is converted into a relayout request.
                    callback.completed(Result.COPY_ERROR, code, "PixelCopy failed");
                }
            }, main);
        } catch (IllegalArgumentException exception) {
            inFlight = false;
            if (!"Window doesn't have a backing surface!".equals(exception.getMessage())) {
                callback.completed(Result.CALL_ERROR, -1, exception.toString()); return;
            }
            if (!validBaseline) { callback.completed(Result.NO_BASELINE, -1, exception.getMessage()); return; }
            if (!repairAdmittedByLifecycleAndFreshData) {
                callback.completed(Result.NOT_ELIGIBLE, -1, exception.getMessage()); return;
            }
            if (attempted) { callback.completed(Result.EXHAUSTED, -1, exception.getMessage()); return; }
            // Consume the lifetime budget before the action. A thrown or failed
            // relayout never enables another attempt or an application restart.
            attempted = true; attempts++;
            inFlight = true;
            java.util.function.Consumer<Boolean> answer = granted -> {
                mainThread(); inFlight = false;
                if (closed) { if (!pixel.isRecycled()) pixel.recycle(); callback.completed(Result.CLOSED,-1,"closed awaiting durable budget"); return; }
                if (!granted) { callback.completed(Result.NOT_ELIGIBLE,-1,"durable permit denied or proof expired"); return; }
                recheckBeforeRelayout(callback);
            };
            if (permit == null) answer.accept(true); // Only the standalone native primitive; production installs a durable gate.
            else permit.request(answer);
        } catch (RuntimeException exception) {
            inFlight = false;
            callback.completed(Result.CALL_ERROR, -1, exception.toString());
        }
    }
    private void recheckBeforeRelayout(Callback callback) {
        inFlight=true;
        try {
            PixelCopy.request(window,new Rect(0,0,1,1),pixel,code->{
                inFlight=false;
                if(closed){pixel.recycle();callback.completed(Result.CLOSED,code,"closed after permit");return;}
                if(code==PixelCopy.SUCCESS){validBaseline=true;callback.completed(Result.VALID,code,"Surface recovered while permit was pending; no relayout");}
                else callback.completed(Result.COPY_ERROR,code,"post-permit PixelCopy failed; no relayout");
            },main);
        }catch(IllegalArgumentException failure){
            inFlight=false;
            if(!"Window doesn't have a backing surface!".equals(failure.getMessage())){callback.completed(Result.CALL_ERROR,-1,failure.toString());return;}
            try {
                WindowManager.LayoutParams same=new WindowManager.LayoutParams();same.copyFrom(window.getAttributes());window.setAttributes(same);
                callback.completed(Result.RECOVERY_REQUESTED,-1,"one identical-attributes relayout after exact post-permit Surface check");
            }catch(RuntimeException problem){callback.completed(Result.CALL_ERROR,-1,problem.toString());}
        }catch(RuntimeException failure){inFlight=false;callback.completed(Result.CALL_ERROR,-1,failure.toString());}
    }
    @Override public void close() {
        mainThread();
        if (closed) return;
        closed = true;
        if (!inFlight) pixel.recycle(); // In-flight callback owns the final recycle.
    }
}
