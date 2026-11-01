package com.no5.helm.ocean.gl

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLUtils
import com.no5.helm.ocean.OceanFrame
import com.no5.helm.ocean.OceanQuality
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only the EGL owner thread may construct/use/close this renderer. No source or UI access. */
internal class OceanGlRenderer(private val assets: AssetManager) : AutoCloseable {
    private val fft = OceanFft(assets)
    private val programs = mutableListOf<Int>()
    private val meshes = mutableListOf<GpuMesh>()
    private val targets = mutableListOf<Target>()
    private val common = assets.open("ocean/gl/common.glsl").bufferedReader().use { it.readText() }
    private var sky = 0; private var terrain = 0; private var water = 0; private var composite = 0
    private var shadowProgram = 0
    private lateinit var shadow: Target
    private var shadowSun: List<Float>? = null
    private var maskTexture = 0
    private var width = 0; private var height = 0
    private var low = false
    private lateinit var waterMesh: GpuMesh
    private lateinit var terrainMesh: GpuMesh
    private lateinit var scene: Target
    private lateinit var reflection: Target
    private val uniforms = HashMap<Pair<Int, String>, Int>()
    fun prepare() {
        fft.prepare()
        sky = program("sky.vert", "sky.frag")
        terrain = program("terrain.vert", "terrain.frag")
        shadowProgram = program("terrain-shadow.vert", "terrain-shadow.frag")
        water = program("water.vert", "water.frag")
        composite = program("sky.vert", "composite.frag")
        maskTexture = texture()
        // Resolve once, including optimized-out uniforms; never query locations in a frame.
        for (program in programs) for (name in listOf("uHeading","uTime","uRoughness","uAspect",
            "uTerrainShadow","uShadowTexel","uDisplacement","uMirror","uDetail","uSun","uCloudCover","uFogDensity","uWindTo","uResolution","uReflection","uScene","uMask")) {
            uniforms[program to name] = glGetUniformLocation(program, name)
        }
    }
    fun resize(w: Int, h: Int, mask: Bitmap, quality: OceanQuality) {
        require(w > 0 && h > 0 && mask.width == w && mask.height == h)
        width = w; height = h
        glBindTexture(GL_TEXTURE_2D, maskTexture)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, mask, 0)
        rebuild(quality == OceanQuality.LOW)
    }
    private fun rebuild(reduced: Boolean) {
        low = reduced
        targets.forEach { it.close() }; targets.clear()
        meshes.forEach { it.close() }; meshes.clear()
        scene = target(width, height)
        shadow = target(if(low) 512 else 1024, if(low) 512 else 1024)
        glBindTexture(GL_TEXTURE_2D,shadow.color)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
        shadowSun = null
        val divisor = if (low) 4 else 2
        reflection = target((width/divisor).coerceAtLeast(1), (height/divisor).coerceAtLeast(1))
        waterMesh = mesh(OceanGlGeometry.water(low)); terrainMesh = mesh(OceanGlGeometry.terrain(low))
    }
    fun draw(frame: OceanFrame) {
        if ((frame.quality == OceanQuality.LOW) != low) rebuild(frame.quality == OceanQuality.LOW)
        fft.draw(frame.timeSeconds)
        val e=frame.environment
        val sun=listOf(e.sunEast,e.sunUp,e.sunNorth)
        if(shadowSun != sun) {
            shadow.bind(); glClearColor(1f,1f,1f,1f); glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
            use(shadowProgram,frame,1f); terrainMesh.draw(); shadowSun=sun
        }
        // Mirror the same fixed world/camera around y=0; reflections turn with heading, every frame.
        reflection.bind()
        environment(frame, -1f)
        scene.bind()
        environment(frame, 1f)
        glEnable(GL_DEPTH_TEST)
        use(water, frame, 1f)
        glUniform2f(location(water,"uResolution"), width.toFloat(), height.toFloat())
        bindTexture(water,"uReflection",reflection.color,0)
        bindTexture(water,"uDisplacement",fft.displacement,1)
        waterMesh.draw()
        glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,width,height)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND)
        glUseProgram(composite)
        bindTexture(composite,"uScene",scene.color,0)
        bindTexture(composite,"uMask",maskTexture,1)
        glBindVertexArray(0); glDrawArrays(GL_TRIANGLES,0,3)
        checkError("frame")
    }
    private fun environment(frame: OceanFrame, mirror: Float) {
        glClearColor(0f,0f,0f,0f); glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        use(sky,frame,mirror); glBindVertexArray(0); glDrawArrays(GL_TRIANGLES,0,3)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS)
        use(terrain,frame,mirror)
        bindTexture(terrain,"uTerrainShadow",shadow.color,2)
        glUniform1f(location(terrain,"uShadowTexel"),1f/(if(low) 512 else 1024))
        terrainMesh.draw()
    }
    private fun use(program: Int, frame: OceanFrame, mirror: Float) {
        glUseProgram(program)
        glUniform1f(location(program,"uHeading"),frame.headingRadians)
        glUniform1f(location(program,"uTime"),frame.timeSeconds)
        glUniform1f(location(program,"uRoughness"),frame.waveHeight)
        glUniform1f(location(program,"uAspect"),width.toFloat()/height)
        glUniform1f(location(program,"uMirror"),mirror)
        glUniform1f(location(program,"uDetail"),if(low) .45f else 1f)
        val environment=frame.environment
        glUniform3f(location(program,"uSun"),environment.sunEast,environment.sunUp,environment.sunNorth)
        glUniform1f(location(program,"uCloudCover"),environment.cloudCover)
        glUniform1f(location(program,"uFogDensity"),environment.fogDensity)
        glUniform1f(location(program,"uWindTo"),environment.windToRadians)
    }
    private fun location(p: Int,n: String) = uniforms.getValue(p to n)
    private fun bindTexture(p: Int,n: String,t: Int,unit: Int) {
        glActiveTexture(GL_TEXTURE0+unit); glBindTexture(GL_TEXTURE_2D,t); glUniform1i(location(p,n),unit)
    }
    private fun program(vertex: String, fragment: String): Int {
        fun shader(file: String, type: Int): Int {
            val source=assets.open("ocean/gl/$file").bufferedReader().use { it.readText() }.replace("// COMMON",common)
                .replace("// WATER_SURFACE",assets.open("ocean/gl/water-surface.glsl").bufferedReader().use { it.readText() })
                .replace("// TERRAIN_LIGHT",assets.open("ocean/gl/terrain-light.glsl").bufferedReader().use { it.readText() })
            val shader=glCreateShader(type)
            try {
                glShaderSource(shader,source); glCompileShader(shader)
                val status=IntArray(1); glGetShaderiv(shader,GL_COMPILE_STATUS,status,0)
                check(status[0]!=0) { "$file: ${glGetShaderInfoLog(shader)}" }
                return shader
            } catch(t: Throwable) { glDeleteShader(shader); throw t }
        }
        val v=shader(vertex,GL_VERTEX_SHADER)
        var f=0; var p=0
        try {
            f=shader(fragment,GL_FRAGMENT_SHADER); p=glCreateProgram()
            glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p)
            val status=IntArray(1); glGetProgramiv(p,GL_LINK_STATUS,status,0)
            check(status[0]!=0) { "$vertex/$fragment: ${glGetProgramInfoLog(p)}" }
            programs.add(p); return p
        } catch(t: Throwable) { if(p!=0) glDeleteProgram(p); throw t }
        finally { glDeleteShader(v); if(f!=0) glDeleteShader(f) }
    }
    private fun mesh(data: OceanMesh) = GpuMesh(data).also { meshes.add(it) }
    private fun target(w: Int,h: Int) = Target(w,h).also { targets.add(it) }
    override fun close() {
        fft.close()
        meshes.forEach { it.close() }; targets.forEach { it.close() }
        programs.forEach { glDeleteProgram(it) }; glDeleteTextures(1,intArrayOf(maskTexture),0)
        meshes.clear(); targets.clear(); programs.clear()
    }
    private class GpuMesh(data: OceanMesh) : AutoCloseable {
        private val vao=IntArray(1); private val buffers=IntArray(2); private val count=data.indices.size
        init {
            glGenVertexArrays(1,vao,0); glGenBuffers(2,buffers,0)
            glBindVertexArray(vao[0])
            glBindBuffer(GL_ARRAY_BUFFER,buffers[0])
            val vertices=ByteBuffer.allocateDirect(data.vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data.vertices).apply { position(0) }
            glBufferData(GL_ARRAY_BUFFER,data.vertices.size*4,vertices,GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0,if(data.stride==2) 2 else 3,GL_FLOAT,false,data.stride*4,0)
            if(data.stride==11) {
                glEnableVertexAttribArray(1); glVertexAttribPointer(1,3,GL_FLOAT,false,44,12)
                glEnableVertexAttribArray(2); glVertexAttribPointer(2,1,GL_FLOAT,false,44,24)
                glEnableVertexAttribArray(3); glVertexAttribPointer(3,4,GL_FLOAT,false,44,28)
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,buffers[1])
            val indices=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()).asIntBuffer().put(data.indices).apply { position(0) }
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,count*4,indices,GL_STATIC_DRAW)
            checkError("mesh upload")
        }
        fun draw() { glBindVertexArray(vao[0]); glDrawElements(GL_TRIANGLES,count,GL_UNSIGNED_INT,0) }
        override fun close() { glDeleteVertexArrays(1,vao,0); glDeleteBuffers(2,buffers,0) }
    }
    private class Target(val width: Int,val height: Int) : AutoCloseable {
        val color=texture(); private val framebuffer=IntArray(1); private val depth=IntArray(1)
        init {
            glBindTexture(GL_TEXTURE_2D,color)
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,null)
            glGenFramebuffers(1,framebuffer,0); glBindFramebuffer(GL_FRAMEBUFFER,framebuffer[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0)
            glGenRenderbuffers(1,depth,0); glBindRenderbuffer(GL_RENDERBUFFER,depth[0])
            glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,width,height)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth[0])
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE) { "Incomplete Ocean framebuffer ${width}x$height" }
        }
        fun bind() { glBindFramebuffer(GL_FRAMEBUFFER,framebuffer[0]); glViewport(0,0,width,height) }
        override fun close() { glDeleteFramebuffers(1,framebuffer,0); glDeleteRenderbuffers(1,depth,0); glDeleteTextures(1,intArrayOf(color),0) }
    }
    companion object {
        private fun texture(): Int {
            val id=IntArray(1); glGenTextures(1,id,0); glBindTexture(GL_TEXTURE_2D,id[0])
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
            return id[0]
        }
        private fun checkError(stage: String) { val error=glGetError(); check(error==GL_NO_ERROR) { "$stage GL error 0x${error.toString(16)}" } }
    }
}
