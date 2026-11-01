package no5.sonar.windowrecovery;

import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import java.util.concurrent.atomic.AtomicReference;

/** Test harness only: observe this exact owned Window's real frame commit.
 * No method invokes drawing, changes eligibility, or uses a private platform API.
 */
public final class FrameCommitProbeFixture {
    public static AtomicReference<String> observe(Window window, BackingSurfaceProbe probe) {
        View decor = window.getDecorView();
        AtomicReference<String> result = new AtomicReference<>("PENDING");
        ViewTreeObserver.OnPreDrawListener[] listener = new ViewTreeObserver.OnPreDrawListener[1];
        boolean[] registered = {false};
        listener[0] = () -> {
            if (registered[0]) return true;
            registered[0] = true;
            decor.getViewTreeObserver().registerFrameCommitCallback(() -> {
                try {
                    probe.committedWindowFrame();
                    result.set(probe.baselineVerified() ? "COMMITTED_VALID" : "NOT_VALID");
                } catch (Throwable t) { result.set(t.toString()); }
            });
            decor.post(() -> {
                if (decor.getViewTreeObserver().isAlive())
                    decor.getViewTreeObserver().removeOnPreDrawListener(listener[0]);
            });
            return true;
        };
        decor.getViewTreeObserver().addOnPreDrawListener(listener[0]);
        return result;
    }
}
