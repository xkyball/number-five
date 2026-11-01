package com.no5.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inspector.WindowInspector;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Actual foreground windows of this resumed app, including its native dialogs.
 * This is UI lifecycle observation only. Lumina still verifies package, UID, signer,
 * WMS window identity, focus age and lease on every physical input. No polling or IPC. */
public final class No5OwnedWindowFocus implements AutoCloseable {
    public interface Observer { void changed(boolean focused); }
    private final Activity activity;
    private final Observer observer;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<View,Root> roots=new IdentityHashMap<>();
    private boolean resumed,closed,posted;
    private Boolean reported;
    private final Runnable update=new Runnable(){@Override public void run(){posted=false;refresh();}};
    public No5OwnedWindowFocus(Activity activity,Observer observer){checkMain();this.activity=activity;this.observer=observer;}
    public boolean focused(){checkMain();return Boolean.TRUE.equals(reported);}
    public void onResume(){checkMain();if(closed)return;resumed=true;refresh();schedule();}
    public void onPause(){checkMain();resumed=false;handler.removeCallbacks(update);posted=false;unhook();publish(false);}
    /** Coalesce a window handoff on UI; never substitute an assumed true value. */
    public void windowChanged(){checkMain();schedule();}
    private void schedule(){if(!closed&&resumed&&!posted){posted=true;handler.post(update);}}
    private void refresh(){
        if(closed||!resumed){publish(false);return;}
        List<View> current=new ArrayList<>();
        for(View root:WindowInspector.getGlobalWindowViews())
            if(root.isAttachedToWindow()&&root.getVisibility()==View.VISIBLE&&activity.getPackageName().equals(root.getContext().getPackageName())&&windowOwner(root)==activity)current.add(root);
        for(View root:new ArrayList<>(roots.keySet()))if(!current.contains(root)){Root previous=roots.remove(root);previous.close();}
        boolean focused=false;
        for(View view:current){if(!roots.containsKey(view))roots.put(view,new Root(view));focused|=view.hasWindowFocus();}
        publish(focused);
    }
    private Activity contextOwner(Context context){
        while(context instanceof ContextWrapper){
            if(context instanceof Activity)return (Activity)context;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }
        return null;
    }
    /** DecorContext need not expose an Activity. Actual content views do; a different
     * Activity in the same package must never lend this owner's old bindings its focus. */
    private Activity windowOwner(View view){
        Activity direct=contextOwner(view.getContext());if(direct!=null)return direct;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){
            Activity found=windowOwner(group.getChildAt(i));if(found!=null)return found;
        }}
        return null;
    }
    private void publish(boolean value){if(reported==null||reported.booleanValue()!=value){reported=value;observer.changed(value);}}
    private void unhook(){for(Root root:roots.values())root.close();roots.clear();}
    @Override public void close(){checkMain();if(closed)return;onPause();closed=true;}
    private void checkMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI thread required");}
    private final class Root implements ViewTreeObserver.OnWindowFocusChangeListener,View.OnAttachStateChangeListener,AutoCloseable {
        final View view;final ViewTreeObserver tree;
        Root(View view){this.view=view;tree=view.getViewTreeObserver();tree.addOnWindowFocusChangeListener(this);view.addOnAttachStateChangeListener(this);}
        @Override public void onWindowFocusChanged(boolean focused){schedule();}
        @Override public void onViewAttachedToWindow(View view){schedule();}
        @Override public void onViewDetachedFromWindow(View view){schedule();}
        @Override public void close(){if(tree.isAlive())tree.removeOnWindowFocusChangeListener(this);view.removeOnAttachStateChangeListener(this);}
    }
}
