package com.no5.helm.gpu

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import com.no5.domain.DepthUnit
import com.no5.helm.AudioNativeController
import com.no5.helm.SonarNativeController
import com.no5.helm.SonarNativeRenderer

/** One parent composition: background, actual consumed Texture buffer,
 * then the matching Canvas overlays in the same Window frame. The composite is acknowledged only by the SAME
 * hardware Window frame that recorded its matching overlay. No extra refresh.
 */
internal class SonarGpuSceneView(context:Context,private val controller:SonarNativeController):FrameLayout(context),AutoCloseable {
    val gpu=SonarGpuView(context,composite=true)
    private val renderer=SonarNativeRenderer(context,externalPlot=true,externalData=true)
    private var night=false
    private var dashboardPreview=false
    private var unit=DepthUnit.METERS
    private var audio:AudioNativeController?=null
    private var buffered:SonarGpuCompletion?=null
    private var closed=false
    private val updatePolicy=SonarGpuSceneUpdatePolicy()
    var onPresented:((SonarGpuCompletion)->Unit)?=null
    private val backdrop=object:View(context){override fun onDraw(canvas:Canvas){
        canvas.save();val ratio=width/2000f;canvas.scale(ratio,ratio);if(!dashboardPreview)renderer.drawBackdrop(canvas,night);canvas.restore()
    }}
    // This surface only presents data. Compose owns the controls above it.
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean=false
    private var paintingComposite=false
    override fun onDescendantInvalidated(child:View,target:View){
        super.onDescendantInvalidated(child,target)
        if(!closed&&!paintingComposite&&(child===gpu||target===gpu))invalidate()
    }
    override fun dispatchDraw(canvas:Canvas){
        paintingComposite=true
        try{
            super.dispatchDraw(canvas)
            canvas.save();val ratio=width/2000f;canvas.scale(ratio,ratio);if(dashboardPreview){
                val r=com.no5.helm.DashboardGeometry.echogram
                renderer.drawPreview(canvas,controller,night,android.graphics.RectF(r.x,r.y,r.x+r.width,r.y+r.height))
            }else renderer.draw(canvas,controller,night,unit,audio);canvas.restore()
            gpu.compositeRecorded(buffered)
        }finally{paintingComposite=false}
    }
    init {
        isClickable=false;isFocusable=false;setWillNotDraw(false)
        addView(backdrop,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        addView(gpu,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        gpu.onTextureBuffered={stamp->buffered=stamp;controller.gpuBuffered(stamp);if(!paintingComposite)invalidate()}
        gpu.onTexturePresented={stamp->controller.gpuPresented(stamp);onPresented?.invoke(stamp)}
    }
    fun update(frame:SonarGpuFrame,depthUnit:DepthUnit=DepthUnit.METERS,audioController:AudioNativeController?=null,
        visibleUi:List<Any?> = sonarSceneUi(controller,depthUnit,audioController)){
        if(closed)return
        val immediate=updatePolicy.immediate(frame,depthUnit,audioController,visibleUi)
        if(night!=frame.night||dashboardPreview!=frame.dashboardPreview){night=frame.night;dashboardPreview=frame.dashboardPreview;backdrop.invalidate()}
        unit=depthUnit;audio=audioController
        if(immediate)invalidate()
        gpu.submit(frame)
    }
    // Temporary hierarchy detach is not AndroidView.onRelease. Keep the scene's
    // callbacks/overlay owner until that explicit terminal release.
    override fun close(){
        if(closed)return;closed=true;controller.gpuBufferDiscarded(buffered);buffered=null;onPresented=null
        gpu.close();renderer.dispose()
    }
}
