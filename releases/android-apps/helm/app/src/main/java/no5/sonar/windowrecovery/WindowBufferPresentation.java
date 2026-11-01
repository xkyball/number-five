package no5.sonar.windowrecovery;

import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;

/** Exactly one outstanding producer buffer. No timestamp assumption or refresh.
 * A settled hardware Window, actual TextureView update and subsequent committed
 * Window frame are all required. Resize drains the old buffer without claiming
 * it as a presentation in the new viewport. Surface destruction cancels it.
 */
public final class WindowBufferPresentation implements AutoCloseable {
    public interface Listener { void viewportReady(); void presented(long ticket); void discarded(long ticket); }
    private final TextureView view;
    private final Listener listener;
    private final boolean requireComposite;
    // A Window's commit callback owns its recording evidence. A newer draw
    // must neither invalidate that evidence nor validate an older Window.
    private static final class RecordedFrame {
        final long ticket;
        boolean composite;
        RecordedFrame(long value){ticket=value;}
    }
    private RecordedFrame recording;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean closed,ready,baselineScheduled,viewportChanged;
    private long serial,active;
    private boolean nativeDone,textureUpdated,windowCommitted;
    private final ViewTreeObserver.OnPreDrawListener before=this::beforeDraw;
    private ViewTreeObserver observed;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener(){
        @Override public void onViewAttachedToWindow(View ignored){observeWindow();}
        @Override public void onViewDetachedFromWindow(View ignored){unobserveWindow();}
    };
    public WindowBufferPresentation(TextureView owned,Listener callback){this(owned,callback,false);}
    public WindowBufferPresentation(TextureView owned,Listener callback,boolean composite){
        requireComposite=composite;view=owned;listener=callback;
        view.addOnAttachStateChangeListener(attachment);observeWindow();
    }
    private void unobserveWindow(){
        if(observed!=null&&observed.isAlive())observed.removeOnPreDrawListener(before);
        observed=null;
    }
    private void observeWindow(){
        unobserveWindow();
        if(closed)return;
        observed=view.getViewTreeObserver();
        // Android may merge the pre-attach observer into its Window observer.
        observed.removeOnPreDrawListener(before);observed.addOnPreDrawListener(before);
    }
    public synchronized long beginSubmission(){
        if(closed||!ready||active!=0)return 0;
        active=++serial;nativeDone=false;textureUpdated=false;windowCommitted=false;recording=null;return active;
    }
    public void completeSubmission(long ticket){
        synchronized(this){if(closed||active!=ticket)return;nativeDone=true;}
        main.post(this::finishIfComplete);
    }
    public synchronized void failedSubmission(long ticket){if(active==ticket){active=0;nativeDone=false;textureUpdated=false;windowCommitted=false;recording=null;}}
    public synchronized boolean ready(){return !closed&&ready&&active==0;}
    public synchronized void viewportChanged(){ready=false;viewportChanged=true;baselineScheduled=false;}
    public synchronized void surfaceDestroyed(){ready=false;viewportChanged=true;active=0;baselineScheduled=false;recording=null;}
    public synchronized void textureUpdated(){if(!closed&&active!=0)textureUpdated=true;}
    public synchronized void compositeRecorded(long ticket){
        if(!closed&&active==ticket&&textureUpdated&&recording!=null&&recording.ticket==ticket)recording.composite=true;
    }
    private boolean beforeDraw(){
        final long ticket;
        final RecordedFrame frame;
        synchronized(this){
            // TextureView creates its Surface DURING the first draw, after OnPreDraw.
            // Register the hardware Window commit before that draw; confirm the
            // actual available texture only in the committed callback.
            if(closed||!view.isHardwareAccelerated())return true;
            ticket=active;
            if(ticket==0){
                if(ready||baselineScheduled)return true;
                baselineScheduled=true;
            }
            frame=new RecordedFrame(ticket);recording=frame;
        }
        view.getViewTreeObserver().registerFrameCommitCallback(()->{
            boolean notifyReady=false;
            synchronized(this){
                if(closed)return;
                if(ticket==0){
                    baselineScheduled=false;
                    if(active==0&&view.isAvailable()){ready=true;viewportChanged=false;notifyReady=true;}
                }else if(active==ticket&&textureUpdated&&(!requireComposite||frame.composite))windowCommitted=true;
            }
            if(notifyReady)listener.viewportReady();
            finishIfComplete();
        });
        return true;
    }
    private void finishIfComplete(){
        long ticket;boolean discard;
        synchronized(this){
            if(closed||active==0||!nativeDone||!textureUpdated||!windowCommitted)return;
            ticket=active;active=0;recording=null;discard=viewportChanged;
            if(discard){ready=true;viewportChanged=false;}
        }
        if(discard){listener.discarded(ticket);listener.viewportReady();}else listener.presented(ticket);
    }
    @Override public void close(){
        synchronized(this){if(closed)return;closed=true;active=0;ready=false;recording=null;}
        view.removeOnAttachStateChangeListener(attachment);unobserveWindow();
    }
}
