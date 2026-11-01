package com.no5.helm.sonar.gl

import android.content.res.AssetManager
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** EGL-thread owner only; R16UI ring + small timing texture, no per-frame CPU recoloring. */
internal class SonarReliefRenderer(private val assets:AssetManager):AutoCloseable {
    private var program=0
    private val textures=IntArray(2)
    private val buffers=IntArray(3)
    private val vao=IntArray(2)
    private val uniforms=HashMap<String,Int>()
    private var width=1;private var height=1;private var indexCount=0
    private var uploaded=LongArray(0){Long.MIN_VALUE}
    private var mappingVersion=-1L
    private var owner:com.no5.domain.SonarRingBuffer?=null
    private var generation=-1L
    private var revision=-1L
    private var capacity=0
    private val rows=ShortArray(256*1024)
    private val columns=ByteBuffer.allocateDirect(256*2048).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val timing=ByteBuffer.allocateDirect(256*4*4*2).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun prepare() {
        val max=IntArray(1);glGetIntegerv(GL_MAX_TEXTURE_SIZE,max,0);check(max[0]>=2048)
        fun shader(name:String,type:Int):Int {
            val id=glCreateShader(type)
            try {
                glShaderSource(id,assets.open("sonar/gl/$name").bufferedReader().use{it.readText()});glCompileShader(id)
                val ok=IntArray(1);glGetShaderiv(id,GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){glGetShaderInfoLog(id)}
                return id
            } catch(t:Throwable){glDeleteShader(id);throw t}
        }
        val vertex=shader("relief.vert",GL_VERTEX_SHADER);var fragment=0
        try {
            fragment=shader("relief.frag",GL_FRAGMENT_SHADER);program=glCreateProgram()
            glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program)
            val ok=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,ok,0);check(ok[0]!=0){glGetProgramInfoLog(program)}
        } finally {glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment)}
        for(name in listOf("uProfiles","uTiming","uHead","uCount","uSeconds","uStart","uEnd","uGain","uGamma","uThreshold","uYaw","uPitch","uZoom","uAspect","uPalette","uNight","uAligned","uSlice","uCapacity","uNewest","uAutoMask","uGainOffset"))uniforms[name]=glGetUniformLocation(program,name)
        glGenTextures(2,textures,0)
        glBindTexture(GL_TEXTURE_2D_ARRAY,textures[0]);glBindTexture(GL_TEXTURE_2D,textures[1])
        for(i in 0..1){val target=if(i==0)GL_TEXTURE_2D_ARRAY else GL_TEXTURE_2D
            glBindTexture(target,textures[i]);glTexParameteri(target,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
            glTexParameteri(target,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(target,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        }
        val mesh=SonarReliefGeometry.mesh();indexCount=mesh.indices.size
        glGenVertexArrays(2,vao,0);glBindVertexArray(vao[0]);glGenBuffers(3,buffers,0)
        glBindBuffer(GL_ARRAY_BUFFER,buffers[0])
        val v=ByteBuffer.allocateDirect(mesh.vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.vertices).apply{position(0)}
        glBufferData(GL_ARRAY_BUFFER,mesh.vertices.size*4,v,GL_STATIC_DRAW)
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,false,8,0)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,buffers[1])
        val ix=ByteBuffer.allocateDirect(indexCount*4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.indices).apply{position(0)}
        glBufferData(GL_ELEMENT_ARRAY_BUFFER,indexCount*4,ix,GL_STATIC_DRAW)
        glBindVertexArray(vao[1]);glBindBuffer(GL_ARRAY_BUFFER,buffers[2])
        val slice=ByteBuffer.allocateDirect(1024*2*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for(bin in 0..1023){slice.put(0f);slice.put(bin/1023f)}
        slice.flip();glBufferData(GL_ARRAY_BUFFER,1024*2*4,slice,GL_STATIC_DRAW)
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,false,8,0);checkError()
    }
    fun resize(w:Int,h:Int){require(w>0&&h>0);width=w;height=h}
    fun draw(frame:ReliefFrame):Boolean {
        val data=frame.data
        val source=data.source
        if(source.readLocked{source.lifecycleGeneration!=data.generation||source.imageMappingVersion!=data.mappingVersion})return false
        val nextCapacity=source.capacity
        if(capacity!=nextCapacity){
            capacity=nextCapacity;uploaded=LongArray(capacity){Long.MIN_VALUE};revision=-1
            val pages=(capacity+255)/256
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[0]);glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_R16UI,1024,256,pages,0,GL_RED_INTEGER,GL_UNSIGNED_SHORT,null)
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1]);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,256,pages*2,0,GL_RGBA,GL_FLOAT,null)
        }
        if(owner!==source || generation!=data.generation){uploaded.fill(Long.MIN_VALUE);owner=source;generation=data.generation;mappingVersion=-1}
        if(revision!=data.revision || mappingVersion!=data.mappingVersion){
            val allTone=mappingVersion!=data.mappingVersion
            // Contiguous page-sized staging bounds driver calls and receive-lock time.
            // It is erased after upload; the canonical ring remains the sole RAW history.
            var left=data.count
            var first=(data.head-left+capacity)%capacity
            val ids=LongArray(256)
            while(left>0){
                val count=minOf(left,256-first%256,capacity-first)
                var rawChanged=false;var toneChanged=allTone
                timing.clear()
                val exact=source.readLocked {
                    if(source.lifecycleGeneration!=data.generation||source.imageMappingVersion!=data.mappingVersion)return@readLocked false
                    for(i in 0 until count){
                        val slot=first+i
                        val age=(source.head-1-slot+capacity)%capacity
                        if(age>=source.count)return@readLocked false
                        ids[i]=source.pingIdAt(age).takeIf{it<=data.newestPingId}?:Long.MIN_VALUE
                        if(uploaded[slot]!=ids[i])rawChanged=true
                    }
                    if(!rawChanged&&!toneChanged)return@readLocked true
                    for(i in 0 until count){
                        val slot=first+i;val age=(source.head-1-slot+capacity)%capacity
                        if(rawChanged)source.copyDisplayProfileAt(age,rows,i*1024)
                        if(ids[i]==Long.MIN_VALUE){
                            if(rawChanged)rows.fill(0,i*1024,(i+1)*1024)
                            for(j in 0..3)timing.put(i*4+j,0f)
                            val at=count*4+i*4;timing.put(at,1f);timing.put(at+1,1f);timing.put(at+2,0f);timing.put(at+3,0f)
                            continue
                        }
                        val tone=source.imageMappingAt(age)
                        timing.put(i*4,Math.floorMod(source.timestampAt(age),16777216L).toFloat())
                        timing.put(i*4+1,(source.imageSegmentAt(age)*4+(source.pingSourceAt(age)?.ordinal?:3)).toFloat())
                        timing.put(i*4+2,source.amplitudeFullScaleAt(age).toFloat())
                        timing.put(i*4+3,if(source.amplitudeContextAt(age)?.usable==false||source.pingSourceAt(age)==null||source.pingSourceAt(age)==com.no5.domain.SonarPingSource.ALL)0f else 1f)
                        val at=count*4+i*4
                        timing.put(at,Math.pow(10.0,(tone?.gainDb?:0.0)/20).toFloat())
                        timing.put(at+1,(1.0/(tone?.gamma?:1.0)).toFloat());timing.put(at+2,(tone?.threshold?:0.0).toFloat());timing.put(at+3,if(tone==null)0f else 1f)
                    }
                    true
                }
                if(!exact){rows.fill(0);columns.clear();while(columns.hasRemaining())columns.put(0);return false}
                if(rawChanged||toneChanged){
                    try{
                        if(rawChanged){
                            columns.clear();columns.put(rows,0,count*1024);columns.flip()
                            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[0])
                            glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,first%256,first/256,1024,count,1,GL_RED_INTEGER,GL_UNSIGNED_SHORT,columns)
                        }
                        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1])
                        for(stripe in 0..1){
                            timing.position(stripe*count*4);timing.limit((stripe+1)*count*4)
                            glTexSubImage2D(GL_TEXTURE_2D,0,first%256,first/256+stripe*((capacity+255)/256),count,1,GL_RGBA,GL_FLOAT,timing)
                            timing.limit(timing.capacity())
                        }
                        for(i in 0 until count)uploaded[first+i]=ids[i]
                    }finally{
                        rows.fill(0);columns.clear();while(columns.hasRemaining())columns.put(0);columns.clear()
                        timing.clear();while(timing.hasRemaining())timing.put(0f);timing.clear()
                    }
                }
                left-=count;first=(first+count)%capacity
            }
            revision=data.revision;mappingVersion=data.mappingVersion
        }
        val drawableCount=com.no5.helm.gpu.SonarGpuSnapshotFence.retainedCount(data.head,data.count,uploaded)
        if(data.count>0&&drawableCount==0)return false
        glViewport(0,0,width,height);glClearColor(if(frame.night).01f else .018f,if(frame.night)0f else .035f,if(frame.night)0f else .055f,1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LESS);glDisable(GL_CULL_FACE);glDisable(GL_BLEND)
        glUseProgram(program)
        for((name,value) in listOf("uCapacity" to capacity,"uAutoMask" to frame.autoMask,"uAligned" to if(frame.aligned)1 else 0,"uSlice" to 0,"uHead" to data.head,"uCount" to drawableCount,"uPalette" to frame.palette,"uNight" to if(frame.night)1 else 0))glUniform1i(uniforms.getValue(name),value)
        for((name,value) in listOf("uNewest" to data.newest,"uGainOffset" to frame.gainOffset,"uSeconds" to data.seconds,"uStart" to frame.cropStart,"uEnd" to frame.cropEnd,"uGain" to frame.gain,"uGamma" to frame.gamma,"uThreshold" to frame.threshold,"uYaw" to frame.yaw,"uPitch" to frame.pitch,"uZoom" to frame.zoom,"uAspect" to width.toFloat()/height))glUniform1f(uniforms.getValue(name),value)
        for(i in 0..1){glActiveTexture(GL_TEXTURE0+i);glBindTexture(if(i==0)GL_TEXTURE_2D_ARRAY else GL_TEXTURE_2D,textures[i]);glUniform1i(uniforms.getValue(if(i==0)"uProfiles" else "uTiming"),i)}
        glBindVertexArray(vao[0]);glDrawElements(GL_TRIANGLES,indexCount,GL_UNSIGNED_INT,0)
        if(data.count>0){
            glUniform1i(uniforms.getValue("uSlice"),1)
            glDisable(GL_DEPTH_TEST) // View-ping edge remains identifiable through an orbit.
            glBindVertexArray(vao[1]);glDrawArrays(GL_LINE_STRIP,0,1024)
        }
        checkError();return true
    }
    private fun checkError(){val error=glGetError();check(error==GL_NO_ERROR){"SONAR GLES error 0x${error.toString(16)}"}}
    override fun close(){glDeleteBuffers(3,buffers,0);glDeleteVertexArrays(2,vao,0);glDeleteTextures(2,textures,0);if(program!=0)glDeleteProgram(program);uploaded=LongArray(0);owner=null;capacity=0;generation=-1}
}
