package com.no5.helm;
import no5.sonar.windowrecovery.BackingSurfaceProbe;
import no5.sonar.windowrecovery.FrameCommitProbeFixture;

import android.app.Instrumentation;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real API29 Window/Surface test on a private offscreen display, never HELM.
 * The deliberate release uses the public Window.takeSurface ownership contract.
 * It paints with Surface.lockHardwareCanvas, not ViewRoot's ThreadedRenderer;
 * it does NOT reproduce the initiating native HWUI fault.
 * A separate ordinary hardware Window supplies a healthy negative control.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
public final class CurrentWindowRecoveryDeviceTest {
    private final Instrumentation instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
    private Context getTargetContext(){ return instrument.getTargetContext(); }
    private no5.sonar.windowrecovery.RecoveryBudgetPolicy durablePolicy;
    private int permits;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger images = new AtomicInteger();
    private HandlerThread drainThread;
    private ImageReader output;
    private VirtualDisplay display;
    private Presentation healthy, taken;
    private final JSONObject report = new JSONObject();
    private final CountDownLatch created = new CountDownLatch(1);
    private final CountDownLatch changed = new CountDownLatch(1);
    private final AtomicReference<SurfaceHolder> holder = new AtomicReference<>();
    private final AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
    private int relayouts;
    private final java.util.Map<Window, BackingSurfaceProbe> probes = new java.util.IdentityHashMap<>();
    private AtomicReference<String> actualCommitBaseline;
    private final AtomicInteger hardwareDraws = new AtomicInteger();
    private long deadline;


    private void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("fixture deadline");
    }
    private void ui(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrument.runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { error.set(t); } });
        if (error.get() != null) throw new AssertionError("main action", error.get());
    }
    private void waitFor(CountDownLatch event, String name) throws Exception {
        require(event.await(3, TimeUnit.SECONDS), "timeout: " + name);
        if (asyncFailure.get() != null) throw new AssertionError("surface callback", asyncFailure.get());
    }
    private Presentation window(boolean ownsSurface) {
        Presentation p = new Presentation(getTargetContext(), display.getDisplay());
        p.setCancelable(false);
        Window w = p.getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        w.setLayout(128, 128);
        if (ownsSurface) w.takeSurface(new SurfaceHolder.Callback2() {
            @Override public void surfaceCreated(SurfaceHolder value) {
                holder.set(value); created.countDown();
            }
            @Override public void surfaceChanged(SurfaceHolder value, int f, int x, int y) {
                try { paint(value.getSurface(), Color.RED); changed.countDown(); }
                catch (Throwable t) { asyncFailure.set(t); changed.countDown(); }
            }
            @Override public void surfaceDestroyed(SurfaceHolder value) { }
            @Override public void surfaceRedrawNeeded(SurfaceHolder value) { }
        });
        p.setContentView(new View(p.getContext()) {
            @Override protected void onDraw(Canvas c) {
                c.drawColor(Color.GREEN); hardwareDraws.incrementAndGet();
            }
        });
        if (!ownsSurface) {
            BackingSurfaceProbe core = new BackingSurfaceProbe(w);
            probes.put(w, core);
            actualCommitBaseline = FrameCommitProbeFixture.observe(w, core);
        }
        p.show();
        return p;
    }
    private static void paint(Surface source, int color) {
        Canvas c = source.lockHardwareCanvas();
        try {
            if (!c.isHardwareAccelerated()) throw new AssertionError("hardware canvas required");
            c.drawColor(color);
        } finally { source.unlockCanvasAndPost(c); }
    }
    private static WindowManager.LayoutParams attributes(Window w) {
        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
        copy.copyFrom(w.getAttributes());
        return copy;
    }
    private int copy(Window window) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        AtomicInteger result = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        try {
            ui(() -> PixelCopy.request(window, new Rect(0, 0, 1, 1), bitmap,
                code -> { result.set(code); done.countDown(); }, main));
            waitFor(done, "PixelCopy");
            require(result.get() == PixelCopy.SUCCESS, "PixelCopy result " + result.get());
            return bitmap.getPixel(0, 0);
        } finally { bitmap.recycle(); }
    }
    private BackingSurfaceProbe.Result probe(Window w, boolean admitted) throws Exception {
        AtomicReference<BackingSurfaceProbe.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ui(() -> {
            BackingSurfaceProbe p = probes.get(w);
            if (p == null) { p = new BackingSurfaceProbe(w); probes.put(w, p); }
            p.setRepairPermit(answer -> {
                try {
                    no5.sonar.windowrecovery.RecoveryBudgetPolicy.Evidence evidence = new no5.sonar.windowrecovery.RecoveryBudgetPolicy.Evidence("private-window","private-native",true,true,true,true,0,SystemClock.elapsedRealtimeNanos(),no5.sonar.windowrecovery.RecoveryBudgetPolicy.Fault.MISSING_WINDOW_SURFACE);
                    boolean granted=durablePolicy.consume(no5.sonar.windowrecovery.RecoveryBudgetPolicy.Action.WINDOW_RELAYOUT,evidence,"private-window","private-native",SystemClock.elapsedRealtimeNanos());
                    if(granted){
                        no5.sonar.windowrecovery.RecoveryBudgetPolicy loaded=new no5.sonar.windowrecovery.RecoveryBudgetPolicy(new com.no5.helm.watchdog.RecoveryLedger(getTargetContext(),"private-window-native"));
                        require(loaded.snapshot().windowMask==1,"permit durably consumed before actual relayout");permits++;
                    }
                    answer.accept(granted);
                } catch(Exception failure){ throw new AssertionError(failure); }
            });
            p.check(admitted, (value, code, detail) -> {
                result.set(value);
                if (value == BackingSurfaceProbe.Result.RECOVERY_REQUESTED) relayouts++;
                done.countDown();
            });
        });
        waitFor(done, "actual candidate probe");
        return result.get();
    }
    private void guardedProbe(Window w, boolean admitted) throws Exception {
        BackingSurfaceProbe.Result result = probe(w, admitted);
        if (result == BackingSurfaceProbe.Result.NOT_ELIGIBLE)
            throw new AssertionError("probe rejected", new IllegalArgumentException("Window doesn't have a backing surface!"));
        require(result == BackingSurfaceProbe.Result.VALID || result == BackingSurfaceProbe.Result.RECOVERY_REQUESTED,
            "actual candidate result " + result);
    }
    @org.junit.Test public void currentPublicWindowRecoveryUsesDurablePermitAndOnlyOneRelayout() {
        if(!getTargetContext().getPackageName().equals("com.no5.helm.sonarfixture")) throw new AssertionError("private package only");
        deadline = SystemClock.elapsedRealtime() + 25000;
        boolean passed = false;
        try {
            require(Build.VERSION.SDK_INT == 29, "only actual API29 reviewed");
            durablePolicy = new no5.sonar.windowrecovery.RecoveryBudgetPolicy(new com.no5.helm.watchdog.RecoveryLedger(getTargetContext(),"private-window-native"));
            report.put("schema", "no5.window-recovery-candidate-native-fixture/1");
            report.put("fingerprint", Build.FINGERPRINT);
            report.put("initiating_HWUI_fault_reproduced", false);
            report.put("HELM_modified_or_instrumented", false);
            drainThread = new HandlerThread("own-offscreen-image-drain"); drainThread.start();
            ui(() -> {
                output = ImageReader.newInstance(128, 128, PixelFormat.RGBA_8888, 2);
                output.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image != null) images.incrementAndGet();
                    }
                }, new Handler(drainThread.getLooper()));
                DisplayManager dm = (DisplayManager)getTargetContext().getSystemService(Context.DISPLAY_SERVICE);
                display = dm.createVirtualDisplay("No5-owned-offscreen-fixture", 128, 128, 160,
                    output.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                require(display != null && display.getDisplay().getDisplayId() != 0, "private display");
                healthy = window(false);
            });
            instrument.waitForIdleSync();
            SystemClock.sleep(250); // one bounded warmup, never a refresh/retry loop
            ui(() -> require(healthy.getWindow().getDecorView().isHardwareAccelerated(), "HWUI healthy control"));
            require("COMMITTED_VALID".equals(actualCommitBaseline.get()), "actual public frame commit: " + actualCommitBaseline.get());
            report.put("normal_HWUI_Window_baseline_from_actual_frame_commit_no_PixelCopy_poll", true);
            int healthyPixel = copy(healthy.getWindow());
            guardedProbe(healthy.getWindow(), true);
            require(relayouts == 0, "healthy window received a relayout");
            report.put("healthy_hardware_window_pixel", healthyPixel);
            report.put("healthy_draws", hardwareDraws.get());
            report.put("healthy_relayout_calls", relayouts);
            ui(() -> { healthy.dismiss(); healthy = null; taken = window(true); });
            waitFor(created, "own Window surfaceCreated"); waitFor(changed, "own Window surfaceChanged");
            require(copy(taken.getWindow()) == Color.RED, "red baseline");
            require(probe(taken.getWindow(), false) == BackingSurfaceProbe.Result.VALID,
                "candidate establishes own actual PixelCopy baseline");
            Surface own = holder.get().getSurface();
            require(own.isValid(), "baseline surface validity");
            WindowManager.LayoutParams[] attrs = new WindowManager.LayoutParams[1];
            ui(() -> attrs[0] = attributes(taken.getWindow()));
            ui(own::release); // only our publicly owned Window Surface
            require(!own.isValid(), "fault injection did not release own surface");
            Bundle phase = new Bundle(); phase.putString("surface_fixture_phase", "CLIENT_SURFACE_RELEASED");
            report.put("released_surface_phase",phase.getString("surface_fixture_phase")); // host may inspect our WindowManager surface, no UI interaction
            SystemClock.sleep(1500);
            require(!own.isValid(), "unexpected autonomous Surface recovery");
            report.put("client_surface_invalid_before_relayout", true);
            report.put("window_shown_after_own_surface_release", taken.isShowing());
            AtomicReference<BackingSurfaceProbe.Result> unverified = new AtomicReference<>();
            ui(() -> {
                BackingSurfaceProbe p = new BackingSurfaceProbe(taken.getWindow());
                p.check(true, (value, code, detail) -> unverified.set(value));
                p.close();
            });
            require(unverified.get() == BackingSurfaceProbe.Result.NO_BASELINE, "missing baseline denied");
            report.put("missing_valid_baseline_rejected_without_relayout", true);
            boolean rejectedWithoutHistory = false;
            try { guardedProbe(taken.getWindow(), false); }
            catch (AssertionError expected) {
                rejectedWithoutHistory = expected.getCause() instanceof IllegalArgumentException
                    && "Window doesn't have a backing surface!".equals(expected.getCause().getMessage());
            }
            require(rejectedWithoutHistory && relayouts == 0, "no previously valid Window guard");
            report.put("repair_disallowed_rejected_without_relayout", true);
            guardedProbe(taken.getWindow(), true);
            require(relayouts == 1, "exactly one conditional relayout required");
            instrument.waitForIdleSync();
            SystemClock.sleep(250);
            require(own.isValid(), "public identical-attributes relayout did not restore own Surface");
            ui(() -> {
                require(attrs[0].token == taken.getWindow().getAttributes().token
                    && attrs[0].copyFrom(taken.getWindow().getAttributes()) == 0,
                    "Window attributes changed");
                paint(own, Color.BLUE);
            });
            require(copy(taken.getWindow()) == Color.BLUE, "new blue pixels after recovery");
            guardedProbe(taken.getWindow(), true);
            require(relayouts == 1, "recovered window received another relayout");
            report.put("surface_valid_after_relayout", true);
            report.put("identical_Window_attributes", true);
            report.put("new_pixels_after_relayout", true);
            report.put("conditional_relayout_calls", relayouts);
            require(permits==1,"exactly one durable permit");
            report.put("durable_permit_consumed_before_action",true);
            report.put("qualifying_seconds",0);
            report.put("admission_scope","Synthetic private lifecycle/source evidence; real public Surface loss, hardware pixels and durable permit. Actual foreground Activity admission is independently gated, not bypassed here.");
            ui(own::release);
            require(probe(taken.getWindow(), true) == BackingSurfaceProbe.Result.EXHAUSTED,
                "second injected loss must not repeat relayout");
            require(relayouts == 1, "no restart/relayout loop");
            report.put("second_loss_explicitly_EXHAUSTED_no_retry", true);
            report.put("offscreen_display_id", display.getDisplay().getDisplayId());
            report.put("output_images_drained", images.get());
            report.put("injected_window_paint_method", "Surface.lockHardwareCanvas (hardware canvas asserted)");
            report.put("fixture_limitation", "takeSurface owns Window Surface, uses Surface HWUIContext rather than ViewRoot ThreadedRenderer; native fault initiator and HELM recovery NOT tested");
            passed = true;
        } catch (Throwable t) {
            try { report.put("error", t.toString());
                if (t.getCause() != null) report.put("cause", t.getCause().toString()); }
            catch (Exception ignored) { }
        } finally {
            try { ui(() -> {
                for (BackingSurfaceProbe p : probes.values()) p.close();
                if (taken != null) taken.dismiss();
                if (healthy != null) healthy.dismiss();
                if (display != null) display.release();
                if (output != null) output.close();
            });
                if (drainThread != null) { drainThread.quitSafely(); drainThread.join(2000); }
                require(drainThread == null || !drainThread.isAlive(), "drain cleanup");
                report.put("own_windows_display_and_reader_closed", true);
            } catch (Throwable t) {
                passed = false;
                try { report.put("cleanup_error", t.toString()); } catch (Exception ignored) { }
            }
            try { report.put("passed", passed); } catch (Exception ignored) { }
            try {
                java.io.File root=new java.io.File(getTargetContext().getFilesDir(),"sonar-private-proof");root.mkdirs();
                try(java.io.FileWriter writer=new java.io.FileWriter(new java.io.File(root,"current-window-recovery-native1.json"))){writer.write(report.toString());}
            }catch(Exception error){throw new AssertionError(error);}
            if(!passed)throw new AssertionError(report.toString());
        }
    }

    @org.junit.Test public void surfaceRestoredWhileDurablePermitPendingDoesNotRelayoutAgain() {
        if(!getTargetContext().getPackageName().equals("com.no5.helm.sonarfixture")) throw new AssertionError("private package only");
        deadline=SystemClock.elapsedRealtime()+15000;
        boolean passed=false;
        final AtomicInteger fixtureRestores=new AtomicInteger();
        try {
            require(Build.VERSION.SDK_INT==29,"actual reviewed API29");
            durablePolicy=new no5.sonar.windowrecovery.RecoveryBudgetPolicy(new com.no5.helm.watchdog.RecoveryLedger(getTargetContext(),"private-window-late-native"));
            report.put("schema","no5.late-window-permit-native/1");
            report.put("qualifying_seconds",0);
            drainThread=new HandlerThread("own-late-permit-image-drain");drainThread.start();
            ui(()->{
                output=ImageReader.newInstance(128,128,PixelFormat.RGBA_8888,2);
                output.setOnImageAvailableListener(reader->{try(Image image=reader.acquireLatestImage()){if(image!=null)images.incrementAndGet();}},new Handler(drainThread.getLooper()));
                DisplayManager dm=(DisplayManager)getTargetContext().getSystemService(Context.DISPLAY_SERVICE);
                display=dm.createVirtualDisplay("No5-owned-late-permit-fixture",128,128,160,output.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                require(display!=null&&display.getDisplay().getDisplayId()!=0,"private display");
                taken=window(true);
            });
            waitFor(created,"own created");waitFor(changed,"own changed");
            require(copy(taken.getWindow())==Color.RED,"actual valid red baseline");
            require(probe(taken.getWindow(),false)==BackingSurfaceProbe.Result.VALID,"previously valid Window");
            Surface own=holder.get().getSurface();
            ui(own::release);require(!own.isValid(),"own missing Surface injected");
            AtomicReference<BackingSurfaceProbe.Result> result=new AtomicReference<>();
            AtomicReference<Throwable> failure=new AtomicReference<>();
            CountDownLatch done=new CountDownLatch(1);
            ui(()->{
                BackingSurfaceProbe core=probes.get(taken.getWindow());
                core.setRepairPermit(answer->{
                    try {
                        no5.sonar.windowrecovery.RecoveryBudgetPolicy.Evidence evidence=new no5.sonar.windowrecovery.RecoveryBudgetPolicy.Evidence("private-window","private-late-native",true,true,true,true,0,SystemClock.elapsedRealtimeNanos(),no5.sonar.windowrecovery.RecoveryBudgetPolicy.Fault.MISSING_WINDOW_SURFACE);
                        require(durablePolicy.consume(no5.sonar.windowrecovery.RecoveryBudgetPolicy.Action.WINDOW_RELAYOUT,evidence,"private-window","private-late-native",SystemClock.elapsedRealtimeNanos()),"durable permit");
                        no5.sonar.windowrecovery.RecoveryBudgetPolicy loaded=new no5.sonar.windowrecovery.RecoveryBudgetPolicy(new com.no5.helm.watchdog.RecoveryLedger(getTargetContext(),"private-window-late-native"));
                        require(loaded.snapshot().windowMask==1,"budget consumed BEFORE external recovery");
                        // Fixture-only independent restoration during the asynchronous permit.
                        // The candidate must recheck rather than perform a second relayout.
                        taken.getWindow().setAttributes(attributes(taken.getWindow()));fixtureRestores.incrementAndGet();
                        main.postDelayed(()->{
                            try {require(own.isValid(),"independent Surface recovery");paint(own,Color.BLUE);answer.accept(true);}
                            catch(Throwable problem){failure.set(problem);done.countDown();}
                        },150);
                    }catch(Throwable problem){failure.set(problem);done.countDown();}
                });
                core.check(true,(value,code,detail)->{result.set(value);if(value==BackingSurfaceProbe.Result.RECOVERY_REQUESTED)relayouts++;done.countDown();});
            });
            waitFor(done,"post-permit actual Surface recheck");
            if(failure.get()!=null)throw new AssertionError("asynchronous permit",failure.get());
            require(result.get()==BackingSurfaceProbe.Result.VALID,"recovered Surface must be VALID without candidate action: "+result.get());
            require(relayouts==0&&fixtureRestores.get()==1,"candidate must not refresh restored Window");
            require(copy(taken.getWindow())==Color.BLUE,"actual new blue hardware pixels");
            ui(()->require(probes.get(taken.getWindow()).attempts()==1,"lifetime attempt remains consumed"));
            require(durablePolicy.snapshot().windowMask==1,"durable budget remains consumed");
            report.put("durable_permit_consumed_before_action",true);
            report.put("fixture_independent_relayout_calls",fixtureRestores.get());
            report.put("watchdog_relayout_calls",relayouts);
            report.put("actual_post_permit_PixelCopy_valid",true);
            report.put("actual_new_hardware_pixels",true);
            report.put("fixture_limitation","Private takeSurface/Surface HWUIContext; not historical HELM ViewRoot ThreadedRenderer initiator");
            passed=true;
        }catch(Throwable error){try{report.put("error",error.toString());if(error.getCause()!=null)report.put("cause",error.getCause().toString());}catch(Exception ignored){}}
        finally {
            try {
                ui(()->{for(BackingSurfaceProbe p:probes.values())p.close();if(taken!=null)taken.dismiss();if(display!=null)display.release();if(output!=null)output.close();});
                if(drainThread!=null){drainThread.quitSafely();drainThread.join(2000);require(!drainThread.isAlive(),"own thread closed");}
                report.put("own_windows_display_and_reader_closed",true);
            }catch(Throwable error){passed=false;try{report.put("cleanup_error",error.toString());}catch(Exception ignored){}}
            try {
                report.put("passed",passed);
                java.io.File root=new java.io.File(getTargetContext().getFilesDir(),"sonar-private-proof");root.mkdirs();
                try(java.io.FileWriter writer=new java.io.FileWriter(new java.io.File(root,"late-window-permit-native1.json"))){writer.write(report.toString());}
            }catch(Exception error){throw new AssertionError(error);}
            if(!passed)throw new AssertionError(report.toString());
        }
    }

}
